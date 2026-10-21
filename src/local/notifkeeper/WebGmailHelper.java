package local.notifkeeper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carries out a Gmail notification's tap or button in the web Gmail's Inbox, the way you would by
 * hand. The Keeper opens the web Gmail on the Inbox (first page); then this helper finds that email
 * in the list - scrolling to it, refreshing once if the Inbox hasn't caught up yet, and going to
 * older pages if needed - and
 *  - Open (tapping the notification): clicks the email, so it opens;
 *  - Reply / Delete / Mark as read: right-clicks the email (a long press, which the web Gmail
 *    treats as a right-click) and picks that item in the web Gmail's own menu.
 *
 * It also clears a Gmail notification once you've opened that email in the web Gmail (like the
 * Gmail app does when you read an email elsewhere).
 *
 * The email must match the notification's subject and arrival time (within 2 minutes), so an older
 * email with the same subject is never touched. The helper only touches the web Gmail window while
 * that window is the active one, and only at a point no other window covers. Every job gives up
 * after 45 s, leaving the notification in place. It only receives events from Chrome / Gmail
 * (res/xml/web_gmail_helper.xml), stores nothing and logs no email content.
 */
public class WebGmailHelper extends AccessibilityService {
    enum Kind { OPEN, REPLY, DELETE, READ, INBOX }

    private static final long JOB_MS = 45_000;
    private static final long RETRY_MS = 300;
    /** The email must show in the same place this long before it is touched (the list has settled). */
    private static final long SETTLE_MS = 300;
    /** The list must show without that email this long before we refresh / go to an older page. */
    private static final long NOT_HERE_MS = 1_000;
    private static final long SCROLL_AGAIN_MS = 1_500;
    /** Keep looking for an already-open web Gmail window this long before ever opening one. The window
     *  list is briefly incomplete right after the shade closes, so a too-early look can miss it. */
    private static final long LAUNCH_GRACE_MS = 1_800;
    /** After clicking Inbox (because the web Gmail was on another view), give it this long to open. */
    private static final long INBOX_SETTLE_MS = 2_500;
    private static final int MAX_PAGES = 5;
    private static final long MENU_WAIT_MS = 2_500;
    private static final long CHECK_WAIT_MS = 3_000;
    private static final long LONG_PRESS_MS = 800;
    private static final long TAP_MS = 60;
    private static final int MAX_TRIES = 2;
    private static final int TIME_SLACK_MIN = 2;
    private static final long WATCH_EVERY_MS = 2_000;
    private static final int MAX_NODES = 8000;

    private enum Stage { FIND, MENU, CHECK_OPEN, CHECK_REPLY }

    /** One notification tap or button (one at a time; a newer one replaces it). */
    private static final class Job {
        final Kind kind;
        final String subject, sender, address, account, url, tag;
        final long when;
        final long started = SystemClock.uptimeMillis();
        final long until = started + JOB_MS;
        Stage stage = Stage.FIND;
        String seenWhere; // where the email showed last, to tell the list has settled
        String listSeen;  // which list page showed last without the email
        long seenAt, listSeenAt, actedAt, scrolledAt, inboxTapAt;
        int tries, pages;
        boolean touching, refreshed, launched, everSawWindow;

        Job(Kind kind, String subject, String sender, String address, String account, long when, String url, String tag) {
            this.kind = kind;
            this.subject = subject;
            this.sender = sender;
            this.address = address == null ? null : address.toLowerCase(Locale.ROOT);
            this.account = account;
            this.when = when;
            this.url = url;
            this.tag = tag;
        }
    }

    private static volatile WebGmailHelper running;
    private static volatile Job job;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::step;
    private long lastWatch;

    static boolean isRunning() {
        return running != null;
    }

    /**
     * Called right before the Keeper opens the web Gmail's Inbox (url). when = the email's arrival
     * time (0 = unknown). tag is our notification for it, removed once the action is done (for Open,
     * tapping removes it).
     */
    static void start(Kind kind, CharSequence subject, CharSequence sender, String address, String account, long when,
            String url, String tag) {
        if (kind == null || subject == null || subject.toString().trim().isEmpty()) return;
        job = new Job(kind, subject.toString().trim(), sender == null ? null : sender.toString().trim(), address, account,
                when, url, tag);
        WebGmailHelper h = running;
        if (h != null) h.handler.post(h.retry);
    }

    /**
     * A notification or the "N new messages" summary that just opens the Inbox (no specific email):
     * reuse an open web Gmail window if there is one, going to the Inbox there, and only open a new
     * window when none is found. This keeps even a plain tap from ever adding a second window.
     */
    static void openInbox(String url) {
        job = new Job(Kind.INBOX, "", null, null, null, 0, url, null);
        WebGmailHelper h = running;
        if (h != null) h.handler.post(h.retry);
    }

    /** For the adb test trigger: the windows the helper sees, top first (to check its cover test). */
    static void logWindows() {
        WebGmailHelper h = running;
        if (h == null) {
            Log.i(Keeper.TAG, "gmail helper: off");
            return;
        }
        h.handler.post(() -> {
            Rect r = new Rect();
            for (AccessibilityWindowInfo w : h.getWindows()) {
                w.getBoundsInScreen(r);
                Log.i(Keeper.TAG, "gmail helper: window layer " + w.getLayer() + " type " + w.getType() + " title "
                        + w.getTitle() + " " + r.toShortString() + (w.isActive() ? " active" : ""));
            }
        });
    }

    /**
     * For the adb test trigger: logs everything the web Gmail page exposes about the items whose text
     * contains needle and the items around them (to see what the helper can match on).
     */
    static void probe(String needle) {
        WebGmailHelper h = running;
        if (h == null || needle == null) return;
        h.handler.post(() -> {
            Gmail g = h.gmail(null);
            if (g == null) {
                Log.i(Keeper.TAG, "probe: no web Gmail window");
                return;
            }
            String want = norm(needle);
            ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
            todo.add(g.window.getRoot());
            int seen = 0, hits = 0;
            while (!todo.isEmpty() && seen++ < MAX_NODES && hits < 10) {
                AccessibilityNodeInfo n = todo.removeFirst();
                if (norm(str(n.getText()) + " " + str(n.getContentDescription())).contains(want)) {
                    hits++;
                    AccessibilityNodeInfo row = n.getParent();
                    Log.i(Keeper.TAG, "probe: HIT " + describe(n));
                    if (row != null) {
                        Log.i(Keeper.TAG, "probe:  parent " + describe(row));
                        for (int i = 0; i < row.getChildCount(); i++) {
                            AccessibilityNodeInfo c = row.getChild(i);
                            if (c == null) continue;
                            Log.i(Keeper.TAG, "probe:   child " + describe(c));
                            for (int k = 0; k < c.getChildCount() && k < 8; k++) {
                                AccessibilityNodeInfo cc = c.getChild(k);
                                if (cc != null) Log.i(Keeper.TAG, "probe:     grandchild " + describe(cc));
                            }
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) todo.addLast(c);
                }
            }
            Log.i(Keeper.TAG, "probe: done, " + seen + " items, " + hits + " hits");
        });
    }

    private static String describe(AccessibilityNodeInfo n) {
        StringBuilder b = new StringBuilder();
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        b.append(n.getClassName()).append(' ').append(r.toShortString()).append(n.isVisibleToUser() ? " vis" : " HIDDEN")
                .append(" text=").append(n.getText()).append(" desc=").append(n.getContentDescription())
                .append(" hint=").append(n.getHintText()).append(" tip=").append(n.getTooltipText())
                .append(" state=").append(n.getStateDescription()).append(" id=").append(n.getViewIdResourceName());
        android.os.Bundle x = n.getExtras();
        if (x != null) for (String k : x.keySet()) b.append(" [").append(k).append('=').append(x.get(k)).append(']');
        b.append(" extraData=").append(n.getAvailableExtraData());
        return b.toString();
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        job = null; // drop any job left over from a previous connection, so no stale action runs
        Log.i(Keeper.TAG, "gmail helper connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (job != null) {
                if (!handler.hasCallbacks(retry)) handler.post(retry);
            } else {
                long now = SystemClock.uptimeMillis();
                if (now - lastWatch >= WATCH_EVERY_MS) {
                    lastWatch = now;
                    watchForReadEmail();
                }
            }
        } catch (RuntimeException ex) {
            Log.w(Keeper.TAG, "gmail helper: event failed", ex);
        }
    }

    // ---- the job ------------------------------------------------------------------------------------

    private void step() {
        handler.removeCallbacks(retry);
        Job j = job;
        if (j == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now > j.until) {
                finish(j, false, "gave up at step " + j.stage + " (tries " + j.tries + ", older pages " + j.pages + ")");
                return;
            }
            if (j.touching) { // the gesture's end calls us again; this is only the backstop
                handler.postDelayed(retry, LONG_PRESS_MS + RETRY_MS);
                return;
            }
            Gmail g = gmail(j);
            if (g != null) {
                j.everSawWindow = true;
                if (j.kind == Kind.INBOX) {
                    reuseForInbox(j, g, now);
                } else {
                    act(j, g, now);
                }
            } else if (!j.launched && !j.everSawWindow && now - j.started > LAUNCH_GRACE_MS) {
                // Only after looking for a while with NO web Gmail window ever seen in this job do we
                // open one. This is the single place we launch. The grace covers a window that is slow
                // to appear right after the shade closes, and everSawWindow makes sure that once we have
                // seen your window we never open a second one, even if a later scan briefly misses it.
                j.launched = true;
                logWindowsNow("no web Gmail window found, about to open one");
                GmailMirror.openWebGmail(this, j.url);
                Log.i(Keeper.TAG, "gmail helper: no web Gmail window found after " + (now - j.started) + " ms, opened one");
            }
        } catch (RuntimeException ex) {
            Log.w(Keeper.TAG, "gmail helper: step failed", ex);
        }
        if (job == j && !j.touching) handler.postDelayed(retry, RETRY_MS); // the page may still be loading
    }

    private void act(Job j, Gmail g, long now) {
        Page p = g.page;
        switch (j.stage) {
            case FIND:
                find(j, g, now);
                return;
            case MENU: {
                Rect item = p.menuItem(menuLabel(j.kind));
                if (item == null && j.kind == Kind.READ && p.menuItem("Mark as unread") != null) {
                    finish(j, true, "already read");
                    return;
                }
                if (item != null) {
                    if (touch(j, g.window, item.centerX(), item.centerY(), TAP_MS)) {
                        j.actedAt = now;
                        if (j.kind == Kind.REPLY) {
                            j.stage = Stage.CHECK_REPLY;
                        } else {
                            finish(j, true, "picked " + menuLabel(j.kind));
                        }
                    }
                } else if (now - j.actedAt > MENU_WAIT_MS) {
                    j.stage = Stage.FIND; // no menu: right-click again
                    j.seenWhere = null;
                }
                return;
            }
            case CHECK_OPEN:
                if (p.isOpen(j.subject)) {
                    finish(j, true, "email open");
                } else if (now - j.actedAt > CHECK_WAIT_MS) {
                    j.stage = Stage.FIND;
                    j.seenWhere = null;
                }
                return;
            case CHECK_REPLY:
                if (p.composing) {
                    finish(j, true, "reply window open");
                } else if (now - j.actedAt > CHECK_WAIT_MS) {
                    finish(j, false, "picked Reply, but no reply window showed");
                }
                return;
        }
    }

    /** Finds the email in the Inbox list (scrolling, refreshing, older pages) and clicks / right-clicks it. */
    private void find(Job j, Gmail g, long now) {
        Page p = g.page;
        if (!p.isList() || !p.accountIs(j.account)) { // an open email, a search, or another view
            if (!p.isList()) goToInbox(j, g, now);
            j.seenWhere = null;
            j.listSeen = null;
            return;
        }
        Row row = p.row;
        if (row == null) {
            notOnThisPage(j, g, now);
            return;
        }
        j.listSeen = null;
        if (row.target == null) { // in the list, but not on screen: scroll it into view
            if (now - j.scrolledAt > SCROLL_AGAIN_MS) {
                j.scrolledAt = now;
                row.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                Log.i(Keeper.TAG, "gmail helper: scrolling to the email");
            }
            j.seenWhere = null;
            return;
        }
        String where = row.target.toShortString();
        if (!where.equals(j.seenWhere)) {
            j.seenWhere = where;
            j.seenAt = now;
            return;
        }
        if (now - j.seenAt < SETTLE_MS) return;
        if (j.kind == Kind.READ && !row.unread) {
            finish(j, true, "already read");
            return;
        }
        if (j.tries >= MAX_TRIES) {
            finish(j, false, "the web Gmail didn't respond to " + j.tries + " tries");
            return;
        }
        int x = row.target.left + Math.min(row.target.width() / 3, 300), y = row.target.centerY();
        boolean open = j.kind == Kind.OPEN;
        if (touch(j, g.window, x, y, open ? TAP_MS : LONG_PRESS_MS)) {
            j.tries++;
            j.stage = open ? Stage.CHECK_OPEN : Stage.MENU;
            j.actedAt = now;
            Log.i(Keeper.TAG, "gmail helper: " + (open ? "clicked" : "right-clicked") + " the email (page " + (j.pages + 1) + ")");
        }
    }

    /**
     * The Inbox list shows, without that email: once it has looked the same for a moment, press
     * Refresh once (the Inbox may not have caught up with a brand-new email), then go to older pages.
     */
    private void notOnThisPage(Job j, Gmail g, long now) {
        Page p = g.page;
        if (!p.listId.equals(j.listSeen)) {
            j.listSeen = p.listId;
            j.listSeenAt = now;
            return;
        }
        if (now - j.listSeenAt < NOT_HERE_MS) return;
        Rect button;
        String what;
        if (!j.refreshed && j.pages == 0 && p.refresh != null) {
            button = p.refresh;
            what = "refreshed the Inbox";
        } else if (j.pages < MAX_PAGES && p.older != null) {
            button = p.older;
            what = "went to the next older page";
        } else {
            finish(j, false, "the email isn't in the Inbox (looked at " + (j.pages + 1) + " page(s))");
            return;
        }
        if (touch(j, g.window, button.centerX(), button.centerY(), TAP_MS)) {
            if (button == p.refresh) j.refreshed = true;
            else j.pages++;
            j.listSeen = null;
            Log.i(Keeper.TAG, "gmail helper: not on this page, " + what);
        }
    }

    /**
     * The web Gmail is not showing the Inbox list (an email is open, a search, or another view). Click
     * the Inbox item in the left menu, exactly as you would by hand, then look for the email again.
     * Without this, tapping a notification did nothing until you opened the Inbox yourself.
     */
    private void goToInbox(Job j, Gmail g, long now) {
        Rect inbox = g.page.inbox;
        if (inbox == null) return;                          // the menu is not readable yet; keep waiting
        if (now - j.inboxTapAt < INBOX_SETTLE_MS) return;   // tapped already, let the Inbox open
        if (touch(j, g.window, inbox.centerX(), inbox.centerY(), TAP_MS)) {
            j.inboxTapAt = now;
            Log.i(Keeper.TAG, "gmail helper: the web Gmail was not on the Inbox, clicked Inbox to go there");
        }
    }

    /**
     * A plain "open the Inbox" job (Kind.INBOX): a web Gmail window exists, so reuse it rather than
     * ever opening a second one. If it is already on the Inbox we are done; otherwise click Inbox in
     * the existing window, then finish. We never launch for this kind once a window has been seen.
     */
    private void reuseForInbox(Job j, Gmail g, long now) {
        Page p = g.page;
        if (p.isList()) {
            finish(j, true, "web Gmail already open on the Inbox, reused it");
            return;
        }
        if (p.inbox == null) {
            // Cannot find the Inbox item to click yet. The window is open, which is what matters (no
            // second window); give the menu a moment to load, then stop without launching anything.
            if (now - j.started > INBOX_SETTLE_MS) finish(j, true, "web Gmail already open, reused it");
            return;
        }
        goToInbox(j, g, now);
        if (j.inboxTapAt > 0 && now - j.inboxTapAt >= INBOX_SETTLE_MS) {
            finish(j, true, "reused the open window and went to the Inbox");
        }
    }

    /**
     * Logs every window the helper can see right now (package, title, bounds), with a reason. Called
     * just before the helper opens a window, so a real test leaves evidence of what was actually open
     * at that moment and whether the existing web Gmail window was visible to the helper.
     */
    private void logWindowsNow(String why) {
        try {
            Rect r = new Rect();
            StringBuilder sb = new StringBuilder("gmail helper: windows (").append(why).append("):");
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                CharSequence pkg = root == null ? null : root.getPackageName();
                w.getBoundsInScreen(r);
                sb.append("\n  layer ").append(w.getLayer()).append(" type ").append(w.getType())
                        .append(" pkg ").append(pkg).append(" title ").append(w.getTitle())
                        .append(' ').append(r.toShortString()).append(w.isActive() ? " active" : "");
            }
            Log.i(Keeper.TAG, sb.toString());
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail helper: could not list windows", e);
        }
    }

    private void finish(Job j, boolean done, String what) {
        if (job == j) job = null;
        handler.removeCallbacks(retry);
        if (done) {
            Log.i(Keeper.TAG, "gmail helper: " + j.kind + ": " + what);
            if (j.kind != Kind.OPEN) GmailMirror.removeCopy(this, j.tag);
        } else {
            Log.w(Keeper.TAG, "gmail helper: " + j.kind + ": " + what + " - notification left in place");
        }
    }

    private static String menuLabel(Kind k) {
        switch (k) {
            case REPLY: return "Reply";
            case DELETE: return "Delete";
            case READ: return "Mark as read";
            default: return null;
        }
    }

    /**
     * A short tap or a long press (the web Gmail's right-click) at x,y in the web Gmail window - only
     * if that window is the active one and nothing else on screen covers that point.
     */
    private boolean touch(Job j, AccessibilityWindowInfo w, int x, int y, long ms) {
        if (!uncovered(w, x, y)) {
            Log.i(Keeper.TAG, "gmail helper: the web Gmail isn't in front at that spot, waiting");
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build();
        j.touching = true;
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                j.touching = false;
                handler.post(retry);
            }

            @Override
            public void onCancelled(GestureDescription d) {
                j.touching = false;
                j.stage = Stage.FIND; // something interrupted it: look again
                j.seenWhere = null;
                j.listSeen = null;
                Log.i(Keeper.TAG, "gmail helper: touch interrupted");
                handler.post(retry);
            }
        }, handler);
        if (!sent) j.touching = false;
        return sent;
    }

    /**
     * Is it safe to tap the web Gmail at this point? Yes when the point is inside the web Gmail
     * window and no other window sits on top of it there. We do NOT require the web Gmail to be the
     * focused window: right after you tap a notification the web Gmail is on screen but not yet
     * focused, and the old focus check made the helper wait until you clicked the inbox yourself.
     * The cover test below is what keeps the tap from ever landing on another app.
     */
    private boolean uncovered(AccessibilityWindowInfo w, int x, int y) {
        Rect r = new Rect();
        w.getBoundsInScreen(r);
        if (!r.contains(x, y)) return false;
        for (AccessibilityWindowInfo o : getWindows()) {
            if (o.getId() == w.getId() || o.getLayer() <= w.getLayer()) continue;
            o.getBoundsInScreen(r);
            if (r.contains(x, y)) return false;
        }
        return true;
    }

    // ---- clearing notifications of emails opened in the web Gmail -----------------------------------

    private void watchForReadEmail() {
        if (!GmailMirror.hasCopies()) return; // nothing that could be cleared
        Gmail g = gmail(null);
        if (g == null || g.page.reply == null || g.page.headings.isEmpty()) return; // no email open
        GmailMirror.clearOpenedInWeb(this, g.page.headings, g.page.account);
    }

    /** "Re: Fwd: Lunch" -> "lunch", so a reply's subject matches its conversation's. */
    static String bareSubject(String s) {
        String t = norm(s);
        while (true) {
            String u = t.replaceFirst("^(re|fwd|fw)\\s*:\\s*", "");
            if (u.equals(t)) return t;
            t = u;
        }
    }

    /** Lower case, with the web page's special spaces as plain single spaces. */
    private static String norm(String s) {
        return s.replace('\u00a0', ' ').replace('\u202f', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2}) (am|pm)$");

    /**
     * Does the list's time label ("2:34 am" today, "oct 11" this year, "10/11/25" before) fit an
     * email that arrived at when? Unknown when: anything fits.
     */
    private static boolean timeFits(String label, long when) {
        if (when <= 0) return true;
        if (DateUtils.isToday(when)) {
            Matcher m = CLOCK.matcher(label);
            if (!m.matches()) return false;
            int h = Integer.parseInt(m.group(1)) % 12 + (m.group(3).equals("pm") ? 12 : 0);
            int listed = h * 60 + Integer.parseInt(m.group(2));
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(when);
            int arrived = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            return Math.abs(listed - arrived) <= TIME_SLACK_MIN;
        }
        Calendar c = Calendar.getInstance(), now = Calendar.getInstance();
        c.setTimeInMillis(when);
        String f = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "MMM d" : "M/d/yy";
        return label.equals(norm(new SimpleDateFormat(f, Locale.US).format(new Date(when))));
    }

    // ---- reading the web Gmail window -----------------------------------------------------------

    private static final class Gmail {
        AccessibilityWindowInfo window;
        Page page;
    }

    /** One email in the list: its summary item, where to touch it (null = not on screen), unread. */
    private static final class Row {
        AccessibilityNodeInfo node;
        int top, bottom;     // the row's place in the whole list (also when scrolled out of view)
        boolean onScreen;
        Rect target;
        long time;           // the row's exact date and time (to the minute), 0 = not read
        boolean labelFits;   // its short time label ("2:34 AM") fits the arrival time
        boolean unread;
        int score;
    }

    /** A row's full date ("Mon, Oct 12, 2026, 2:14 AM") at its place in the list. */
    private static final class Stamp {
        int top, bottom;
        long time;
    }

    private static final Pattern STAMP = Pattern.compile("^[A-Z][a-z]{2}, [A-Z][a-z]{2} \\d{1,2}, \\d{4}, \\d{1,2}:\\d{2} [AP]M$");

    private static final class Page {
        boolean gmail;                 // this is the web Gmail ("Search mail" box)
        boolean searching;             // something is in the search box (a search, not the Inbox)
        String account;                // "Google Account: Name (address)"
        int rows;                      // emails in the list
        String listId = "";            // the first email in the list (tells pages apart)
        Row row;                       // the job's email in the list
        Rect refresh, older;           // the list's Refresh and Older buttons
        Rect inbox;                    // the "Inbox" item in the left menu (to go there from elsewhere)
        AccessibilityNodeInfo reply;   // the lowest "Reply" button (an email is open)
        int replyTop = -1;
        boolean back, composing;       // "Back to Inbox"; a reply/compose window with Send
        final List<String> headings = new ArrayList<>(); // incl. the open email's subject
        final List<String> texts = new ArrayList<>();    // short texts (the open email's subject is one)
        final List<AccessibilityNodeInfo> menu = new ArrayList<>();

        /** The Inbox list (no email open, nothing searched). */
        boolean isList() {
            return rows > 0 && !back && !searching;
        }

        boolean accountIs(String a) {
            return a == null || account == null || account.equalsIgnoreCase(a);
        }

        /** That email is open: its subject as a heading / title, with the email's buttons. */
        boolean isOpen(String subject) {
            if (!back && reply == null) return false;
            String want = bareSubject(subject);
            for (String h : headings) if (bareSubject(h).equals(want)) return true;
            for (String t : texts) if (bareSubject(t).equals(want)) return true;
            return false;
        }

        Rect menuItem(String label) {
            for (AccessibilityNodeInfo n : menu) {
                if (label.equals(str(n.getText())) && n.isVisibleToUser()) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (!r.isEmpty()) return r;
                }
            }
            return null;
        }
    }

    /**
     * The web Gmail page (a Chrome window showing mail.google.com) - the active window first - or
     * null. With a job, also finds that email in it.
     */
    private Gmail gmail(Job j) {
        Gmail found = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            // The web Gmail window can show up under Chrome's package (a Chrome tab or app window) or
            // under Gmail's own package (the TWA at com.google.android.gm/...TwaActivity, which Chrome
            // renders). Accept both; scan() still confirms it is really the web Gmail (the search box).
            CharSequence rp = r.getPackageName();
            if (!"com.android.chrome".contentEquals(rp) && !Keeper.GMAIL.contentEquals(rp)) continue;
            Page p = scan(r, j);
            if (!p.gmail) continue;
            Gmail g = new Gmail();
            g.window = w;
            g.page = p;
            if (w.isActive()) return g;
            if (found == null) found = g;
        }
        return found;
    }

    private static Page scan(AccessibilityNodeInfo root, Job j) {
        Page p = new Page();
        String subj = j == null ? null : bareSubject(j.subject);
        Rect page = new Rect();
        root.getBoundsInScreen(page);
        List<Row> rows = new ArrayList<>();
        List<Rect> cells = new ArrayList<>();
        List<Stamp> stamps = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>();
        todo.add(root);
        int seen = 0;
        while (!todo.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = todo.removeFirst();
            String text = str(n.getText());
            String desc = str(n.getContentDescription());
            String both = (text + " " + desc).trim();
            String low = norm(both);
            String cls = String.valueOf(n.getClassName());

            if (desc.startsWith("Google Account:") || text.startsWith("Google Account:")) {
                int a = both.lastIndexOf('('), b = both.lastIndexOf(')');
                if (a >= 0 && b > a && both.substring(a + 1, b).contains("@")) p.account = both.substring(a + 1, b);
            }
            if (low.equals("search mail")) p.gmail = true;
            if (cls.endsWith("EditText") && !text.isEmpty()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.top < page.top + 200) p.searching = true; // the search box is at the top
            }
            if (low.startsWith("back to ") && cls.endsWith("Button")) p.back = true;
            if (p.inbox == null && n.isVisibleToUser()) {
                // The "Inbox" item in the left menu (its own label "Inbox", or the row's description
                // "Inbox 14886 unread"), not the "AI Inbox" item or an "Inbox" label button. Used to
                // go to the Inbox when the web Gmail is showing something else.
                String nd = norm(desc), nt = norm(text);
                if (nd.equals("inbox") || nd.matches("inbox [\\d,]+ unread") || nt.equals("inbox")) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (!r.isEmpty() && r.left < page.left + 500) p.inbox = r;
                }
            }
            if (low.startsWith("send") && cls.endsWith("Button")) p.composing = true;
            if (cls.endsWith("Button") && (low.equals("refresh") || low.equals("older"))) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (n.isEnabled() && n.isVisibleToUser() && !r.isEmpty()) {
                    if (low.equals("refresh")) p.refresh = r;
                    else p.older = r;
                }
            }
            if (cls.endsWith("MenuItem")) p.menu.add(n);
            if (both.equals("Reply") && !cls.endsWith("MenuItem")) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.top > p.replyTop) {
                    p.replyTop = r.top;
                    p.reply = n;
                }
            }
            if (n.isHeading() && !text.isEmpty() && p.headings.size() < 20) p.headings.add(text);
            if (!text.isEmpty() && text.length() <= 300 && p.texts.size() < 400) p.texts.add(text);

            if (text.contains(" , ") && !cls.endsWith("EditText")) {
                // "unread, Sender , Subject , 2:34 AM , preview": one email in the list. Its own item
                // is tiny and sits on the row's checkbox, so we touch the row's subject instead.
                if (p.rows++ == 0) p.listId = text;
                if (subj != null) {
                    Row s = summary(norm(text), subj, j);
                    if (s != null) {
                        s.node = n;
                        s.top = unclipped(n, "Top");
                        s.bottom = unclipped(n, "Bottom");
                        s.onScreen = n.isVisibleToUser();
                        rows.add(s);
                    }
                }
            } else if (j != null && j.when > 0 && text.length() < 40 && STAMP.matcher(text.replace('\u202f', ' ').replace('\u00a0', ' ')).matches()) {
                Stamp t = new Stamp();
                t.top = unclipped(n, "Top");
                t.bottom = unclipped(n, "Bottom");
                t.time = parseStamp(text);
                if (t.time > 0) stamps.add(t);
            } else if (subj != null && !subj.isEmpty() && low.contains(subj) && !cls.endsWith("EditText")
                    && n.isVisibleToUser()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() >= 150 && page.contains(r)) cells.add(r); // "Inbox Test 04 - preview": the row's subject
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.addLast(c);
            }
        }
        if (!p.gmail) return new Page(); // not the web Gmail
        Row best = null;
        for (Row s : rows) {
            for (Stamp t : stamps) {
                int mid = (t.top + t.bottom) / 2;
                if (mid >= s.top && mid <= s.bottom) {
                    s.time = t.time;
                    break;
                }
            }
            // The arrival time must fit: the exact date and time if the row shows it, else its label.
            boolean fits = j.when <= 0 || (s.time > 0 ? Math.abs(s.time - j.when) <= TIME_SLACK_MIN * 60_000L + 59_999L : s.labelFits);
            if (!fits) continue;
            if (s.onScreen) {
                for (Rect c : cells) {
                    if (c.centerY() >= s.top && c.centerY() <= s.bottom) {
                        s.target = c;
                        break;
                    }
                }
            }
            if (best == null || s.score > best.score) best = s; // ties: the first (newest) in the list
        }
        p.row = best;
        return p;
    }

    /** Chrome's own bounds of a page item, also for items scrolled out of view ("Top" / "Bottom"). */
    private static int unclipped(AccessibilityNodeInfo n, String side) {
        android.os.Bundle x = n.getExtras();
        if (x != null && x.containsKey("AccessibilityNodeInfo.unclipped" + side)) {
            return x.getInt("AccessibilityNodeInfo.unclipped" + side);
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return side.equals("Top") ? r.top : r.bottom;
    }

    /** "Mon, Oct 12, 2026, 2:14 AM" -> time in ms (this device's time zone), 0 if unreadable. */
    private static long parseStamp(String s) {
        try {
            Date d = new SimpleDateFormat("EEE, MMM d, yyyy, h:mm a", Locale.US).parse(s.replace('\u202f', ' ').replace('\u00a0', ' '));
            return d == null ? 0 : d.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    /** Reads a list row's summary; null unless it's that email's subject (the time is checked after). */
    private static Row summary(String low, String subj, Job j) {
        boolean unread = low.startsWith("unread, ");
        String s = unread ? low.substring(8) : low;
        String[] f = s.split(" , ");
        if (f.length < 3 || !bareSubject(f[1]).equals(subj)) return null;
        Row r = new Row();
        r.unread = unread;
        r.labelFits = timeFits(f[2], j.when);
        if (unread) r.score += 2;
        if (j.sender != null && (f[0].contains(norm(j.sender)) || norm(j.sender).contains(f[0]))) r.score += 1;
        return r;
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }
}
