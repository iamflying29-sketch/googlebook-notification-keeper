package local.notifkeeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows Gmail Go's notifications as the Keeper's own, linked to the web Gmail.
 *
 * On the Googlebook the full Gmail app runs in web mode and switches its own push receiver off,
 * so nothing notifies while Gmail is closed. Gmail Go (Google's native lite Gmail) still gets
 * Google's pushes and posts real Gmail notifications. For every one of them we post a copy that
 * pops up and stays; tapping it opens that email in the web Gmail, and its Reply, Delete and Mark as
 * read buttons do that in the web Gmail (see WebGmailHelper). Then Gmail Go's original is removed,
 * so there is exactly one notification. Gmail Go's own categories are kept silent (see
 * SettingsGuard), so only the copy alerts.
 *
 * While the web Gmail window is open, its own new-mail notifications would be duplicates of these,
 * so they are removed too (only the new-mail ones; Chat and Meet rings are left alone).
 */
final class GmailMirror {
    static final String CHILD_PREFIX = "gm:";
    private static final String SUMMARY_PREFIX = "gmsum:";
    private static final String CHANNEL_PREFIX = "go:";
    private static final int ID = 1;
    /** Android allows 50 notifications per app; leave room for kept copies, summaries and alerts. */
    private static final int MAX_MIRRORED = 25;
    private static final long SUMMARY_WAIT_MS = 1500;
    private static final long STALE_REPOST_MS = 10 * 60 * 1000L;
    /** Hidden Gmail Go notifications are snoozed this long (re-hidden if they ever come back). */
    private static final long HIDE_MS = 12 * 60 * 60 * 1000L;
    /** A probe re-snoozes hidden notifications this briefly; the unread ones come back, read ones do not. */
    private static final long PROBE_MS = 1200;
    private static final long PROBE_SETTLE_MS = 3000;
    /** When a read probe last ran, per account, so one account's probe never blocks another's. */
    private static final Map<String, Long> lastProbeAt = new HashMap<>();
    private static final String MAIL_CHANNEL = "^nc_1_mail_";
    private static final String ACCOUNT_GROUP = "^ncg_1_account_";

    /** The web Gmail's new-mail notification tag: "p#https://mail.google.com/#1en_<account>". */
    private static final Pattern WEB_MAIL_TAG =
            Pattern.compile("^p#https://mail\\.google\\.com/?#\\d*en_([^#\\s]+@[^#\\s]+)$");

    private static final Object LOCK = new Object();
    /** What each copy showed last (tag -> title/text), so a repeat of the same thing doesn't alert again. */
    private static final Map<String, String> shown = new HashMap<>();
    /** Tags of our Gmail copies currently in the tray (lets the web helper skip work when there are none). */
    private static final Set<String> live = new HashSet<>();
    /** A Gmail Go notification we mirrored and hid: our copy's tag, its account, when it arrived. */
    static final class Tracked {
        final String tag;
        final String account;
        final long postedAt;
        /** Gmail labels whose unread count rose when this mail arrived: its signature (see GoCounts). */
        final Set<String> labels;
        Tracked(String tag, String account, long postedAt, Set<String> labels) {
            this.tag = tag; this.account = account; this.postedAt = postedAt; this.labels = labels;
        }
    }
    /** Gmail Go message key -> our copy, so we can clear the copy once Gmail Go's counts say it was read. */
    private static final Map<String, Tracked> tracked = new HashMap<>();
    /** Gmail Go keys a read probe is currently re-snoozing, so their re-posts are hidden, not mirrored. */
    private static final Set<String> probing = new HashSet<>();

    /**
     * Hides Gmail Go's own notification without dismissing it: a long snooze. Android keeps the
     * record (updated in place if Gmail Go changes it, flagged if Gmail Go cancels it) and never shows
     * it. Cancelling instead would fire Gmail Go's dismiss intent and lose the read signal for good.
     */
    private static void hideGo(NotificationListenerService s, StatusBarNotification sbn) {
        try {
            s.snoozeNotification(sbn.getKey(), HIDE_MS);
            Log.i(Keeper.TAG, "gmail mirror: hid Gmail Go's notification " + sbn.getId()
                    + ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0 ? " (header)" : ""));
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: could not hide Gmail Go's notification", e);
        }
    }

    /**
     * The read test. Android flags a snoozed notification that its app cancelled but keeps it listed,
     * so presence tells nothing. Re-snoozing every hidden notification of the account for about a
     * second does: the ones still unread come back (and are hidden again at once, before they can be
     * seen), the ones Gmail Go cancelled because they were read never come back. After a short settle
     * time, any tracked mail missing from both lists was read, and its copy is cleared.
     */
    static void probe(NotificationListenerService s, String account) {
        long now = System.currentTimeMillis();
        String probeKey = account == null ? "" : account;
        synchronized (LOCK) {
            Long prev = lastProbeAt.get(probeKey);
            if (prev != null && now - prev < PROBE_MS + PROBE_SETTLE_MS) return; // one at a time per account
            lastProbeAt.put(probeKey, now);
        }
        List<String> keys = new ArrayList<>();
        try {
            StatusBarNotification[] snoozed = s.getSnoozedNotifications();
            if (snoozed != null) {
                for (StatusBarNotification x : snoozed) {
                    if (!Keeper.GMAIL_GO.equals(x.getPackageName())) continue;
                    String a = accountOf(x.getNotification(), null);
                    if (account == null || a == null || account.equals(a)) keys.add(x.getKey());
                }
            }
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: probe could not list hidden notifications", e);
            return;
        }
        if (keys.isEmpty()) return;
        Log.i(Keeper.TAG, "gmail mirror: probing " + keys.size() + " hidden Gmail Go notification(s) for " + account);
        synchronized (LOCK) {
            probing.addAll(keys);
        }
        for (String k : keys) {
            try {
                s.snoozeNotification(k, PROBE_MS);
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "gmail mirror: probe re-snooze failed for " + k, e);
            }
        }
        main.postDelayed(() -> settleProbe(s, account, keys), PROBE_MS + PROBE_SETTLE_MS);
    }

    private static void settleProbe(NotificationListenerService s, String account, List<String> probed) {
        Set<String> present = new HashSet<>();
        try {
            for (StatusBarNotification x : s.getActiveNotifications()) present.add(x.getKey());
            StatusBarNotification[] snoozed = s.getSnoozedNotifications();
            if (snoozed != null) for (StatusBarNotification x : snoozed) present.add(x.getKey());
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: probe settle failed", e);
            return;
        }
        List<Tracked> read = new ArrayList<>();
        int back = 0;
        synchronized (LOCK) {
            for (String k : probed) {
                if (present.contains(k)) { back++; continue; }
                Tracked t = tracked.remove(k);
                if (t != null) read.add(t);
                else Log.i(Keeper.TAG, "gmail mirror: probe: hidden notification " + k.substring(k.indexOf('|') + 1, Math.min(k.length(), k.indexOf('|') + 40)) + " is gone (header or untracked)");
            }
        }
        long now = System.currentTimeMillis();
        List<Long> latencies = new ArrayList<>();
        for (Tracked t : read) {
            removeCopy(s, t.tag);
            latencies.add(now - t.postedAt);
            Log.i(Keeper.TAG, "gmail mirror: READ: Gmail Go dropped its hidden notification for copy "
                    + t.tag.substring(0, Math.min(40, t.tag.length())) + " (" + (now - t.postedAt) / 1000 + " s after arrival)");
        }
        Log.i(Keeper.TAG, "gmail mirror: probe done: " + back + " came back (unread), " + read.size() + " read");
        if (!read.isEmpty()) {
            main.postDelayed(() -> {
                try {
                    fixSummary(s, account);
                } catch (RuntimeException e) {
                    Log.w(Keeper.TAG, "gmail mirror: fixing the summary failed", e);
                }
            }, 400);
            ReadSense.learned(s, latencies);
        }
        synchronized (LOCK) {
            probing.removeAll(probed);
        }
    }

    /** Anything we still wait on? (ReadSense stops when there is nothing.) */
    static boolean watching() {
        synchronized (LOCK) {
            return !tracked.isEmpty();
        }
    }

    /** Accounts that have copies waiting for a read. */
    static Set<String> watchedAccounts() {
        Set<String> out = new HashSet<>();
        synchronized (LOCK) {
            for (Tracked t : tracked.values()) if (t.account != null) out.add(t.account);
        }
        return out;
    }

    /**
     * After a restart: rebuild what we are watching from our copies still in the tray, and drop any
     * Gmail Go notifications left snoozed by older builds (they would come back visible otherwise).
     */
    static void reloadTracking(NotificationListenerService s) {
        synchronized (LOCK) {
            for (StatusBarNotification x : s.getSystemService(NotificationManager.class).getActiveNotifications()) {
                String tag = x.getTag();
                if (tag == null || !tag.startsWith(CHILD_PREFIX) || tag.startsWith(SUMMARY_PREFIX)) continue;
                String goKey = tag.substring(CHILD_PREFIX.length());
                if (tracked.containsKey(goKey)) continue;
                CharSequence sub = x.getNotification().extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                String account = sub != null && sub.toString().contains("@") ? sub.toString() : null;
                Set<String> sig = new HashSet<>();
                sig.add(GoCounts.INBOX);
                tracked.put(goKey, new Tracked(tag, account, x.getPostTime(), sig));
            }
        }
        Set<String> accounts = watchedAccounts();
        int n;
        synchronized (LOCK) {
            n = tracked.size();
        }
        Log.i(Keeper.TAG, "gmail mirror: watching " + n + " copies for " + accounts.size() + " account(s); counts "
                + (GoCounts.allowed(s) ? "allowed" : "NOT allowed yet"));
        for (String a : accounts) {
            GoCounts.snapshot(s, a);
            GoCounts.observe(s, a);
        }
        // Headers left behind without copies (for example after a crash) go away.
        for (StatusBarNotification x : s.getSystemService(NotificationManager.class).getActiveNotifications()) {
            String tag = x.getTag();
            if (tag != null && tag.startsWith(SUMMARY_PREFIX + CHILD_PREFIX)) {
                fixSummary(s, tag.substring((SUMMARY_PREFIX + CHILD_PREFIX).length()));
            }
        }
        ReadSense.watch(s);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Icon gmailIcon;

    private GmailMirror() {}

    // ---- is the engine there? ------------------------------------------------------------------

    enum Engine { OK, MISSING, BLOCKED }

    /** OK = Gmail Go is installed, may post notifications and receives Google's pushes. */
    static Engine engine(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(Keeper.GMAIL_GO, 0);
            if (!ai.enabled) return Engine.MISSING;
        } catch (PackageManager.NameNotFoundException e) {
            return Engine.MISSING;
        }
        boolean mayPost = pm.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, Keeper.GMAIL_GO)
                == PackageManager.PERMISSION_GRANTED;
        boolean push = !pm.queryBroadcastReceivers(
                new Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(Keeper.GMAIL_GO), 0).isEmpty();
        return mayPost && push ? Engine.OK : Engine.BLOCKED;
    }

    static boolean engineOk(Context c) {
        return engine(c) == Engine.OK;
    }

    static String goVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(Keeper.GMAIL_GO, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ---- Gmail Go posted something ---------------------------------------------------------------

    /** Mirrors Gmail Go's notifications that were posted while we weren't running (without alerting). */
    static void mirrorExisting(NotificationListenerService s) {
        if (!engineOk(s)) return;
        StatusBarNotification[] all;
        try {
            all = s.getActiveNotifications();
        } catch (RuntimeException e) {
            return;
        }
        if (all == null) return;
        int n = 0;
        for (StatusBarNotification sbn : all) {
            if (Keeper.GMAIL_GO.equals(sbn.getPackageName())) { onPosted(s, sbn, null, false); n++; }
        }
        Log.i(Keeper.TAG, "gmail mirror: caught up on " + n + " Gmail Go notification(s)");
    }

    static void onPosted(NotificationListenerService s, StatusBarNotification sbn,
            NotificationListenerService.RankingMap ranking, boolean mayAlert) {
        if (!engineOk(s)) return;
        if (isDismissed(s, sbn.getKey())) {
            // You swiped this mail's notification away before. Gmail Go brought its own back (a probe,
            // a snooze ending, or a reconnect). Remove it and never show our copy again.
            try {
                s.cancelNotification(sbn.getKey());
            } catch (RuntimeException e) {
                // snoozed right now: it will be removed when it next re-posts
            }
            Log.i(Keeper.TAG, "gmail mirror: a dismissed mail came back from Gmail Go; removed it, not re-showing");
            return;
        }
        boolean beingProbed;
        synchronized (LOCK) {
            beingProbed = probing.contains(sbn.getKey());
        }
        if (beingProbed) {
            // A read probe re-posted this hidden notification to test it. It came back, so it is still
            // unread: hide it again. Never mirror it, because the probe is a check, not new mail.
            hideGo(s, sbn);
            return;
        }
        boolean known;
        synchronized (LOCK) {
            known = tracked.containsKey(sbn.getKey());
        }
        if (known) {
            hideGo(s, sbn); // came back from a snooze (probe or expiry): still unread, hide it again
            return;
        }
        Notification n = sbn.getNotification();
        if (mayAlert && System.currentTimeMillis() - sbn.getPostTime() > STALE_REPOST_MS) {
            // A live post that is old: a notification an earlier build snoozed is coming back. It was
            // shown (and read or dismissed) long ago, so remove it quietly instead of alerting again.
            Log.i(Keeper.TAG, "gmail mirror: removed a stale Gmail Go notification that came back from snooze");
            hideGo(s, sbn);
            return;
        }
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return; // e.g. "Sending…": Android doesn't let us remove those, so leave Gmail Go's own
        }
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            // Gmail Go's "2 new messages" summary: its messages arrive as separate notifications right
            // after it, and we build our own summary. Only if no messages follow (Gmail sometimes
            // posts just a summary) do we show the summary itself.
            final String key = sbn.getKey();
            boolean copies;
            try {
                copies = hasCopiesFor(s, sbn);
            } catch (RuntimeException e) {
                copies = false;
            }
            if (copies) {
                hideGo(s, sbn); // its mails already have copies (Android re-posts the header with each new child)
                return;
            }
            main.postDelayed(() -> summaryCheck(s, key), SUMMARY_WAIT_MS);
            return;
        }
        mirror(s, sbn, ranking, mayAlert);
    }

    private static void summaryCheck(NotificationListenerService s, String key) {
        try {
            StatusBarNotification summary = null;
            boolean children = false;
            for (StatusBarNotification x : s.getActiveNotifications()) {
                if (!Keeper.GMAIL_GO.equals(x.getPackageName())) continue;
                if (x.getKey().equals(key)) {
                    summary = x;
                } else if ((x.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) {
                    children = true; // still waiting to be mirrored
                }
            }
            if (summary == null) return; // already gone
            if (children || hasCopiesFor(s, summary)) {
                hideGo(s, summary); // its mails have copies (or are about to): Gmail Go's header goes
            } else if (isDismissed(s, key) || System.currentTimeMillis() - summary.getPostTime() > STALE_REPOST_MS) {
                // A leftover header of a group whose mails you dismissed or read, or an old one coming
                // back from snooze. There is nothing to summarize, so remove it instead of a bare header.
                try {
                    s.cancelNotification(key);
                } catch (RuntimeException e) {
                    // already gone
                }
            } else {
                mirror(s, summary, null, true);
            }
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: summary check failed", e);
        }
    }

    private static boolean hasCopiesFor(Context c, StatusBarNotification goSummary) {
        String group = groupFor(accountOf(goSummary.getNotification(), null), goSummary.getNotification().getChannelId());
        for (StatusBarNotification x : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX) && group.equals(x.getNotification().getGroup())) {
                return true;
            }
        }
        return false;
    }

    private static void mirror(NotificationListenerService s, StatusBarNotification sbn,
            NotificationListenerService.RankingMap ranking, boolean mayAlert) {
        Notification orig = sbn.getNotification();
        Bundle x = orig.extras;
        NotificationChannel goChannel = goChannel(s, sbn, ranking);
        if (goChannel != null && goChannel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;

        String account = accountOf(orig, goChannel);
        String channelId = ensureChannel(s, orig.getChannelId(), goChannel, account);
        String group = groupFor(account, orig.getChannelId());
        String tag = CHILD_PREFIX + sbn.getKey();

        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        boolean mail = orig.getChannelId() != null && orig.getChannelId().startsWith(MAIL_CHANNEL)
                && (orig.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
        String url = inboxUrl(account);
        String address = mail ? senderAddress(x) : null;

        String seen = String.valueOf(title) + "\n" + text + "\n" + big;
        boolean alert;
        synchronized (LOCK) {
            alert = mayAlert && !seen.equals(shown.get(tag));
            shown.put(tag, seen);
            if (shown.size() > 200) shown.clear();
        }

        int code = tag.hashCode();
        Notification.Builder b = new Notification.Builder(s, channelId)
                .setSmallIcon(gmailIcon(s, orig))
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(account != null ? account : x.getCharSequence(Notification.EXTRA_SUB_TEXT))
                .setWhen(when(orig, sbn))
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setCategory(orig.category != null ? orig.category : Notification.CATEGORY_EMAIL)
                .setGroup(group)
                .setGroupAlertBehavior(alert ? Notification.GROUP_ALERT_CHILDREN : Notification.GROUP_ALERT_SUMMARY)
                .setContentIntent(mail ? open(s, code, url, null, text, title, address, account, when(orig, sbn), WebGmailHelper.Kind.OPEN)
                        : open(s, code, url, null));
        if (lines != null && lines.length > 0) {
            Notification.InboxStyle st = new Notification.InboxStyle();
            for (CharSequence l : lines) st.addLine(l);
            b.setStyle(st);
        } else if (big != null || text != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(big != null ? big : text));
        }
        Icon large = orig.getLargeIcon();
        if (large != null && (large.getType() == Icon.TYPE_BITMAP || large.getType() == Icon.TYPE_ADAPTIVE_BITMAP)) {
            b.setLargeIcon(large);
        }
        b.setColor(orig.color != Notification.COLOR_DEFAULT ? orig.color : 0xffd93025);

        if (mail) {
            long when = when(orig, sbn);
            // Reply and Delete are done on screen in the web Gmail (WebGmailHelper).
            b.addAction(new Notification.Action.Builder(null, s.getString(R.string.action_reply),
                    open(s, code + 100, url, tag, text, title, address, account, when, WebGmailHelper.Kind.REPLY)).build());
            b.addAction(new Notification.Action.Builder(null, s.getString(R.string.action_delete),
                    open(s, code + 101, url, tag, text, title, address, account, when, WebGmailHelper.Kind.DELETE)).build());
            // Mark as read is done instantly in the background by the ghost daemon (GhostReceiver).
            b.addAction(new Notification.Action.Builder(null, s.getString(R.string.action_mark_read),
                    ghostMarkRead(s, code + 102, account, address, text, when, tag)).build());
        } else if (orig.actions != null) {
            // Anything else from Gmail Go: buttons that open a screen open the web Gmail instead;
            // background ones run Gmail Go's own action.
            int i = 0;
            for (Notification.Action a : orig.actions) {
                if (a == null || a.actionIntent == null || a.title == null) continue;
                PendingIntent pi;
                if (a.actionIntent.isActivity()) {
                    pi = open(s, code + 100 + i, url, tag);
                } else {
                    Intent run = new Intent(s, ActionReceiver.class)
                            .setData(Uri.parse("keeper://action/" + code + "/" + i))
                            .putExtra(ActionReceiver.EXTRA_ACTION, a.actionIntent)
                            .putExtra(ActionReceiver.EXTRA_TAG, tag);
                    pi = PendingIntent.getBroadcast(s, code + 100 + i, run,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                }
                b.addAction(new Notification.Action.Builder(null, a.title, pi).build());
                i++;
            }
        }

        NotificationManager nm = s.getSystemService(NotificationManager.class);
        try {
            nm.notify(tag, ID, b.build());
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: could not post copy, leaving Gmail Go's own", e);
            return;
        }
        // Our copy is up, so hide Gmail Go's own right now, before the count query below. The sooner
        // it is snoozed the less chance of seeing it next to our copy, even for a split second. (If
        // the copy had failed to post we returned above, leaving Gmail Go's own in place as a fallback.)
        hideGo(s, sbn);
        synchronized (LOCK) {
            live.add(tag);
        }
        // Remember this mail with its label fingerprint (which counts rose when it arrived), so a later
        // drop of exactly those counts tells us this one was read. Then remove Gmail Go's original.
        Set<String> signature = GoCounts.arrived(s, account);
        synchronized (LOCK) {
            tracked.put(sbn.getKey(), new Tracked(tag, account, System.currentTimeMillis(), signature));
        }
        GoCounts.observe(s, account);
        ReadSense.noteNewMail(s);
        refreshSummary(s, group, channelId, account);
        trim(s);
        if (alert) {
            SharedPreferences p = Keeper.prefs(s);
            p.edit().putInt("mirrored_count", p.getInt("mirrored_count", 0) + 1)
                    .putLong("last_mirrored_time", System.currentTimeMillis()).apply();
            // New mail just popped up: warm the ghost browser now, so a Mark-as-read tap is instant.
            if (mail && account != null) GhostReceiver.emit(GhostReceiver.prewarmJson(account));
        }
        Log.i(Keeper.TAG, "gmail mirror: showing Gmail Go notification" + (alert ? " (pop-up)" : " (quiet update)")
                + " in " + channelId + (mail ? ", sender address " + (address != null ? "known" : "NOT given") : ""));
    }

    // ---- our copies ------------------------------------------------------------------------------

    /** A copy was acted on (Reply, Delete, ...): remove it. */
    static void removeCopy(Context c, String tag) {
        if (tag == null || !tag.startsWith(CHILD_PREFIX)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String group = null, channel = null;
        CharSequence account = null;
        for (StatusBarNotification x : nm.getActiveNotifications()) {
            if (tag.equals(x.getTag())) {
                group = x.getNotification().getGroup();
                channel = x.getNotification().getChannelId();
                account = x.getNotification().extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            }
        }
        nm.cancel(tag, ID);
        synchronized (LOCK) {
            live.remove(tag);
        }
        if (group != null) refreshSummary(c, group, channel, account == null ? null : account.toString());
    }

    /**
     * Gmail Go removed one of its own notifications. Not a read signal: we remove Gmail Go's
     * notifications ourselves, and Gmail Go then cancels its own group header right away (that used
     * to be mistaken for "all mails read" and wiped a fresh copy a millisecond after it appeared).
     * Reads come from GoCounts now.
     */
    static void onGoNotificationRemoved(Context c, StatusBarNotification sbn, int reason) {
        if (!Keeper.GMAIL_GO.equals(sbn.getPackageName())) return;
        if (reason != 10 && Log.isLoggable(Keeper.TAG, Log.DEBUG)) {
            Log.d(Keeper.TAG, "gmail mirror: Gmail Go removed a notification, reason=" + reason);
        }
    }

    /** Make the account's group header match its copies: gone when none are left, else up to date. */
    private static void fixSummary(Context c, String account) {
        if (account == null) return;
        String group = CHILD_PREFIX + account;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String channel = null;
        for (StatusBarNotification x : nm.getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX) && !x.getTag().startsWith(SUMMARY_PREFIX)
                    && group.equals(x.getNotification().getGroup())) {
                channel = x.getNotification().getChannelId();
            }
        }
        if (channel == null) {
            nm.cancel(SUMMARY_PREFIX + group, ID);
        } else {
            refreshSummary(c, group, channel, account);
        }
    }

    /** Remove all our Gmail copies for one account (used when Gmail Go clears the whole group). */
    private static void clearAccountCopies(Context c, String account) {
        String group = CHILD_PREFIX + account;
        List<String> tags = new ArrayList<>();
        for (StatusBarNotification x : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX)
                    && group.equals(x.getNotification().getGroup())) {
                tags.add(x.getTag());
            }
        }
        for (String t : tags) removeCopy(c, t);
    }

    /** One of our copies went away (tapped, swiped, archived): keep its group's summary right. */
    static void onCopyRemoved(Context c, StatusBarNotification sbn, int reason) {
        if (sbn.getTag() == null || !sbn.getTag().startsWith(CHILD_PREFIX)) return;
        // A copy's tag is CHILD_PREFIX + the Gmail Go notification's key, so the key is the rest of it.
        String goKey = sbn.getTag().substring(CHILD_PREFIX.length());
        synchronized (LOCK) {
            live.remove(sbn.getTag());
            tracked.remove(goKey);
        }
        // reason 1 (tapped), 2 (swiped) and 3 (cleared all) mean you got rid of it on purpose, so it
        // must not come back. reason 8/10/12 are our own removals (a read, or acting on a button), which
        // are already handled. On a real dismissal we also remove Gmail Go's own copy in case it shows.
        boolean youDismissed = reason == 1 || reason == 2 || reason == 3;
        if (youDismissed) {
            addDismissed(c, goKey);
            if (c instanceof NotificationListenerService) {
                try {
                    ((NotificationListenerService) c).cancelNotification(goKey);
                } catch (RuntimeException e) {
                    // snoozed right now: onPosted removes it when it next re-posts
                }
            }
            Log.i(Keeper.TAG, "gmail mirror: you dismissed a mail (reason " + reason + "); it will not come back");
        }
        Notification n = sbn.getNotification();
        CharSequence account = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (n.getGroup() != null) {
            refreshSummary(c, n.getGroup(), n.getChannelId(), account == null ? null : account.toString());
        }
    }

    // ---- mails you dismissed on purpose, so they never come back (kept across restarts) ---------

    private static final String PREF_DISMISSED = "dismissed_go_keys";
    private static final int MAX_DISMISSED = 200;

    /** Did you dismiss this Gmail Go notification yourself? Remembered in the app's settings file. */
    static boolean isDismissed(Context c, String goKey) {
        if (goKey == null) return false;
        String s = Keeper.prefs(c).getString(PREF_DISMISSED, "");
        if (s.isEmpty()) return false;
        for (String k : s.split("\n")) {
            if (k.equals(goKey)) return true;
        }
        return false;
    }

    /** Remember a dismissed notification, newest last, capped so the list can never grow without bound. */
    private static synchronized void addDismissed(Context c, String goKey) {
        SharedPreferences p = Keeper.prefs(c);
        String s = p.getString(PREF_DISMISSED, "");
        List<String> keys = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String k : s.split("\n")) {
                if (!k.equals(goKey)) keys.add(k);
            }
        }
        keys.add(goKey);
        while (keys.size() > MAX_DISMISSED) keys.remove(0);
        p.edit().putString(PREF_DISMISSED, String.join("\n", keys)).apply();
    }

    static boolean hasCopies() {
        synchronized (LOCK) {
            return !live.isEmpty();
        }
    }

    /** After a restart: our copies still in the tray. */
    static void reloadLive(Context c) {
        synchronized (LOCK) {
            live.clear();
            for (StatusBarNotification x : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX)) live.add(x.getTag());
            }
        }
    }

    /**
     * An email is open in the web Gmail (its subject is one of these page headings): clear our
     * notification for it, like the Gmail app does once you've read an email.
     */
    static void clearOpenedInWeb(Context c, List<String> headings, String account) {
        Set<String> open = new HashSet<>();
        for (String h : headings) open.add(WebGmailHelper.bareSubject(h));
        for (StatusBarNotification x : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (x.getTag() == null || !x.getTag().startsWith(CHILD_PREFIX)) continue;
            Bundle e = x.getNotification().extras;
            CharSequence subject = e.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence acct = e.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (subject == null || !open.contains(WebGmailHelper.bareSubject(subject.toString()))) continue;
            if (account != null && acct != null && !account.equalsIgnoreCase(acct.toString())) continue;
            removeCopy(c, x.getTag());
            Log.i(Keeper.TAG, "gmail mirror: email opened in the web Gmail, cleared its notification");
        }
    }

    /** Our own "N new messages" summary for one account, like Gmail's. Never alerts by itself. */
    private static void refreshSummary(Context c, String group, String channelId, String account) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        List<StatusBarNotification> kids = new ArrayList<>();
        for (StatusBarNotification x : nm.getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX) && group.equals(x.getNotification().getGroup())) {
                kids.add(x);
            }
        }
        String tag = SUMMARY_PREFIX + group;
        if (kids.isEmpty() || channelId == null) {
            nm.cancel(tag, ID);
            return;
        }
        kids.sort((a, b) -> Long.compare(b.getNotification().when, a.getNotification().when));
        Notification.InboxStyle st = new Notification.InboxStyle();
        for (StatusBarNotification k : kids) {
            CharSequence t = k.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence s = k.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
            st.addLine(t == null ? String.valueOf(s) : s == null ? t : t + "  " + s);
        }
        String title = c.getResources().getQuantityString(R.plurals.new_messages, kids.size(), kids.size());
        st.setBigContentTitle(title);
        if (account != null) st.setSummaryText(account);
        Notification n = new Notification.Builder(c, channelId)
                .setSmallIcon(gmailIcon(c, null))
                .setContentTitle(title)
                .setSubText(account)
                .setStyle(st)
                .setGroup(group)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setColor(0xffd93025)
                .setCategory(Notification.CATEGORY_EMAIL)
                .setContentIntent(open(c, tag.hashCode(), inboxUrl(account), null))
                .build();
        try {
            nm.notify(tag, ID, n);
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: could not post summary", e);
        }
    }

    /** Keeps us under Android's per-app limit by dropping the oldest copies. */
    private static void trim(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        List<StatusBarNotification> copies = new ArrayList<>();
        for (StatusBarNotification x : nm.getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX)) copies.add(x);
        }
        if (copies.size() <= MAX_MIRRORED) return;
        copies.sort((a, b) -> Long.compare(a.getPostTime(), b.getPostTime()));
        for (int i = 0; i < copies.size() - MAX_MIRRORED; i++) removeCopy(c, copies.get(i).getTag());
    }

    // ---- the web Gmail's own duplicates ------------------------------------------------------------

    /** How long to wait for Gmail Go's copy before giving up and leaving the web notification up. */
    private static final long WEB_DUP_GRACE_MS = 700;

    /**
     * The web Gmail (open window) posted a new-mail notification. It's a duplicate of our Gmail Go
     * copy, so we remove it - but only ONCE our copy is actually up, so there's never a gap where you
     * see nothing. If Gmail Go hasn't posted within a short grace period (a rare transient), we leave
     * the web notification in place, so you always see something.
     */
    static void onWebGmailPosted(NotificationListenerService s, StatusBarNotification sbn) {
        if (!isWebGmailMail(s, sbn)) return;
        Matcher m = WEB_MAIL_TAG.matcher(sbn.getTag());
        final String account = m.find() ? m.group(1) : null;
        final String key = sbn.getKey();
        if (hasCopyFor(s, account)) {
            removeWebDuplicate(s, key);
            return;
        }
        // our copy isn't up yet: re-check after a short grace period
        main.postDelayed(() -> {
            try {
                if (hasCopyFor(s, account)) {
                    removeWebDuplicate(s, key);
                } else {
                    Log.i(Keeper.TAG, "gmail mirror: kept web Gmail's notification (no Gmail Go copy yet)");
                }
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "gmail mirror: web duplicate re-check failed", e);
            }
        }, WEB_DUP_GRACE_MS);
    }

    private static void removeWebDuplicate(NotificationListenerService s, String key) {
        try {
            s.cancelNotification(key);
            Log.i(Keeper.TAG, "gmail mirror: removed the web Gmail's duplicate new-mail notification");
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: could not remove web duplicate", e);
        }
    }

    /** Do we have a live Gmail Go copy for this account (so it's safe to drop the web duplicate)? */
    private static boolean hasCopyFor(Context c, String account) {
        String group = account != null ? CHILD_PREFIX + account : null;
        for (StatusBarNotification x : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(CHILD_PREFIX)
                    && (group == null || group.equals(x.getNotification().getGroup()))) {
                return true;
            }
        }
        return false;
    }

    /** A web Gmail new-mail notification for an account that Gmail Go covers (while Gmail Go works)? */
    static boolean isWebGmailMail(Context c, StatusBarNotification sbn) {
        if (!Keeper.GMAIL.equals(sbn.getPackageName()) || sbn.getTag() == null) return false;
        Matcher m = WEB_MAIL_TAG.matcher(sbn.getTag());
        if (!m.find()) return false;
        if (!(c instanceof NotificationListenerService) || !engineOk(c)) return false;
        return goAccounts((NotificationListenerService) c).contains(m.group(1).toLowerCase());
    }

    /** Accounts whose mail Gmail Go notifies about (category on). */
    private static Set<String> goAccounts(NotificationListenerService s) {
        Set<String> out = new HashSet<>();
        try {
            for (NotificationChannel ch : s.getNotificationChannels(Keeper.GMAIL_GO, Process.myUserHandle())) {
                if (ch.getId().startsWith(MAIL_CHANNEL) && ch.getImportance() != NotificationManager.IMPORTANCE_NONE) {
                    out.add(ch.getId().substring(MAIL_CHANNEL.length()).toLowerCase());
                }
            }
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: can't read Gmail Go's categories", e);
        }
        return out;
    }

    // ---- opening the web Gmail -----------------------------------------------------------------

    private static PendingIntent open(Context c, int code, String url, String cancelTag) {
        return open(c, code, url, cancelTag, null, null, null, null, 0, null);
    }

    /**
     * Opens the web Gmail at url; with a kind, the web helper then does it to that email there (opens
     * it, or picks Reply / Delete / Mark as read in its right-click menu) and removes cancelTag's
     * notification once done. Without one, cancelTag's notification is removed right away.
     */
    private static PendingIntent open(Context c, int code, String url, String cancelTag, CharSequence subject,
            CharSequence sender, String address, String account, long when, WebGmailHelper.Kind kind) {
        Intent i = new Intent(c, OpenActivity.class)
                .setData(Uri.parse("keeper://open/" + code))
                .putExtra(OpenActivity.EXTRA_URL, url)
                .putExtra(OpenActivity.EXTRA_CANCEL_TAG, cancelTag)
                .putExtra(OpenActivity.EXTRA_SUBJECT, subject)
                .putExtra(OpenActivity.EXTRA_SENDER, sender)
                .putExtra(OpenActivity.EXTRA_ADDRESS, address)
                .putExtra(OpenActivity.EXTRA_ACCOUNT, account)
                .putExtra(OpenActivity.EXTRA_WHEN, when)
                .putExtra(OpenActivity.EXTRA_KIND, kind == null ? null : kind.name())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** When the email arrived (as the web Gmail's list shows it). */
    private static long when(Notification n, StatusBarNotification sbn) {
        return n.when != 0 ? n.when : sbn.getPostTime();
    }

    /** Mark-as-read button: hands off to the ghost daemon via GhostReceiver (background, instant). */
    private static PendingIntent ghostMarkRead(Context c, int code, String account, String address,
            CharSequence subject, long when, String cancelTag) {
        Intent i = new Intent(c, GhostReceiver.class)
                .setData(Uri.parse("keeper://ghost/" + code))
                .putExtra(GhostReceiver.EXTRA_ACCOUNT, account)
                .putExtra(GhostReceiver.EXTRA_ADDRESS, address)
                .putExtra(GhostReceiver.EXTRA_SUBJECT, subject == null ? null : subject.toString())
                .putExtra(GhostReceiver.EXTRA_WHEN, when)
                .putExtra(GhostReceiver.EXTRA_CANCEL_TAG, cancelTag);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Brings the web Gmail to the front, reusing the window that is already open instead of ever
     * adding a second one. Launching the Gmail web app's own launcher activity (ACTION_MAIN with
     * CATEGORY_LAUNCHER), exactly the way tapping its icon does, brings its existing task to the front
     * when one is open and starts it when none is, so it never makes a duplicate. The web Gmail now
     * runs as a Chrome Custom Tab, and a plain ACTION_VIEW of the inbox link opens a brand new Custom
     * Tab task every time, which was the second window. Once Gmail is in front the helper goes to the
     * Inbox and acts on the email itself, so the account's inbox link is needed only as a fallback.
     */
    static void openWebGmail(Context c, String url) {
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(Keeper.GMAIL);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(launch);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(Keeper.TAG, "web Gmail: the launcher intent failed, falling back to the link", e);
            }
        }
        // Fallback, only when Gmail exposes no launcher activity to reuse: open the inbox link. This
        // one can add a window, but it is the last resort so you can still act on the email.
        Uri u = Uri.parse(url);
        Intent view = new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(Keeper.GMAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        List<android.content.pm.ResolveInfo> screens = c.getPackageManager().queryIntentActivities(view, 0);
        android.content.pm.ResolveInfo pick = null;
        for (android.content.pm.ResolveInfo r : screens) {
            if (pick == null || r.activityInfo.name.endsWith("TwaActivity")) pick = r;
        }
        if (pick != null) view.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
        try {
            c.startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(Keeper.TAG, "web Gmail app can't open the link, using the browser", e);
            c.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * The web Gmail's Inbox (first page) for this account: https://mail.google.com/mail/?authuser=<account>#inbox.
     * Every Gmail tap and button starts there, wherever the web Gmail was before.
     */
    static String inboxUrl(String account) {
        StringBuilder u = new StringBuilder("https://mail.google.com/mail/");
        if (account != null) u.append("?authuser=").append(Uri.encode(account));
        return u.append("#inbox").toString();
    }

    /** The sender's email address, from the "mailto:" contact Gmail attaches to its notification; null if none. */
    static String senderAddress(Bundle x) {
        try {
            ArrayList<android.app.Person> people = x.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person.class);
            if (people != null) {
                for (android.app.Person p : people) {
                    String uri = p.getUri();
                    if (uri != null && uri.startsWith("mailto:") && uri.indexOf('@') > 0) return uri.substring(7).trim();
                }
            }
        } catch (RuntimeException e) {
            // not there or not readable
        }
        return null;
    }

    // ---- accounts, categories, icon ------------------------------------------------------------

    /** The Gmail address a Gmail Go notification belongs to, or null (Miscellaneous etc.). */
    private static String accountOf(Notification n, NotificationChannel goChannel) {
        String ch = n.getChannelId();
        if (ch != null && ch.startsWith(MAIL_CHANNEL)) return ch.substring(MAIL_CHANNEL.length());
        if (goChannel != null && goChannel.getGroup() != null && goChannel.getGroup().startsWith(ACCOUNT_GROUP)) {
            return goChannel.getGroup().substring(ACCOUNT_GROUP.length());
        }
        CharSequence sub = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (sub != null && sub.toString().matches("[^\\s@]+@[^\\s@]+")) return sub.toString();
        return null;
    }

    private static String groupFor(String account, String goChannelId) {
        return CHILD_PREFIX + (account != null ? account : String.valueOf(goChannelId));
    }

    private static NotificationChannel goChannel(NotificationListenerService s, StatusBarNotification sbn,
            NotificationListenerService.RankingMap ranking) {
        try {
            if (ranking == null) ranking = s.getCurrentRanking();
            NotificationListenerService.Ranking r = new NotificationListenerService.Ranking();
            if (ranking != null && ranking.getRanking(sbn.getKey(), r) && r.getChannel() != null) return r.getChannel();
        } catch (RuntimeException e) {
            // fall through
        }
        return null;
    }

    /** Our own category matching Gmail Go's (same name, same account group); pops up by default. */
    private static String ensureChannel(NotificationListenerService s, String goId, NotificationChannel goChannel,
            String account) {
        String id = CHANNEL_PREFIX + (goId != null ? goId : "other");
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(id) != null) return id;

        String groupId = null;
        if (goChannel != null && goChannel.getGroup() != null) {
            groupId = CHANNEL_PREFIX + goChannel.getGroup();
            CharSequence groupName = account;
            try {
                for (NotificationChannelGroup g : s.getNotificationChannelGroups(Keeper.GMAIL_GO, Process.myUserHandle())) {
                    if (g.getId().equals(goChannel.getGroup())) groupName = g.getName();
                }
            } catch (RuntimeException e) {
                // not a companion app: name the group after the account
            }
            nm.createNotificationChannelGroup(new NotificationChannelGroup(groupId,
                    groupName != null ? groupName : s.getString(R.string.channel_gmail_other)));
        }
        CharSequence name = goChannel != null ? goChannel.getName() : s.getString(R.string.channel_gmail_other);
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(s.getString(R.string.channel_gmail_desc));
        if (groupId != null) ch.setGroup(groupId);
        if (goChannel != null) {
            if (goChannel.getSound() != null) ch.setSound(goChannel.getSound(), goChannel.getAudioAttributes());
            ch.enableVibration(goChannel.shouldVibrate());
            ch.enableLights(goChannel.shouldShowLights());
        }
        nm.createNotificationChannel(ch);
        Log.i(Keeper.TAG, "gmail mirror: created category " + id);
        return id;
    }

    /** Gmail's own small icon (from Gmail Go), drawn into a bitmap; our envelope if that fails. */
    private static Icon gmailIcon(Context c, Notification orig) {
        synchronized (LOCK) {
            if (gmailIcon != null) return gmailIcon;
            try {
                if (orig != null && orig.getSmallIcon() != null) {
                    Drawable d = orig.getSmallIcon().loadDrawable(c);
                    if (d != null) {
                        int px = (int) (24 * c.getResources().getDisplayMetrics().density);
                        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                        Canvas cv = new Canvas(bmp);
                        d.setBounds(0, 0, px, px);
                        d.draw(cv);
                        gmailIcon = Icon.createWithBitmap(bmp);
                        return gmailIcon;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "gmail mirror: can't load Gmail Go's icon", e);
            }
            return Icon.createWithResource(c, R.drawable.ic_mail);
        }
    }
}
