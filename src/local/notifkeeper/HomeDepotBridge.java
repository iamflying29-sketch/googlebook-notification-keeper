package local.notifkeeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shows Home Depot order updates on the Googlebook as their own notifications.
 *
 * Why this exists: the Home Depot app's own push notifications never reach this Googlebook. Google's
 * push service on the laptop was watched across two sessions and clean reinstalls and it never once
 * received a message for com.thehomedepot, while every other app's pushes arrived normally. So there
 * is nothing from the Home Depot app itself for a notification listener to catch. What does arrive,
 * reliably, is Home Depot's order emails (order confirmed, shipped, out for delivery, delivered,
 * ready for pickup).
 *
 * This module watches mail notifications, recognizes the ones from Home Depot by the sender, and
 * shows each as its own Home Depot order notification: it pops up, stays until you deal with it, and
 * opens the Home Depot app when tapped (where the phone-window fix keeps it usable).
 *
 * It works with any email app on the device, not only the Keeper's Gmail setup. Gmail Go mail comes
 * through the Keeper's Gmail mirror, and other email apps such as Outlook post their own mail
 * notifications; this module reads them all the same way, by the sender's address or display name.
 *
 * It is purely additive. It never hides, cancels, snoozes or changes the email's own notification:
 * the normal email notification still arrives exactly as before, and the Home Depot card is shown in
 * addition to it. It uses its own notification channel, its own tags and its own id, so it never
 * touches the Gmail mirror's copies or anything else. For every notification that is not a Home Depot
 * order email it does nothing at all.
 */
final class HomeDepotBridge {
    static final String CHANNEL = "homedepot";
    /** Our Home Depot copies are tagged "hd:" + the source email notification's key. */
    private static final String TAG_PREFIX = "hd:";
    /** A fixed id, distinct from the Gmail mirror's (1) and the alerts (1, 2); the tag makes each unique. */
    private static final int ID = 7;
    private static final String GROUP = "hd";
    /** The Home Depot Helper app, which takes a tap to the exact place in the Home Depot app (optional). */
    private static final String HELPER_PACKAGE = "local.hdhelper";
    /** Home Depot's brand orange, as the app itself sets for its notifications (airshipconfig.properties). */
    private static final int HD_ORANGE = 0xFFF96302;
    /** Gmail Go's per-account mail category id prefix; the account follows it. */
    private static final String MAIL_CHANNEL = "^nc_1_mail_";
    /** A live post older than this is an old email re-appearing (an app reconnect), not new mail. */
    private static final long STALE_REPOST_MS = 10 * 60 * 1000L;
    private static final String PREF_DISMISSED = "hd_dismissed_keys";
    private static final int MAX_DISMISSED = 200;

    /**
     * Email apps whose mail notifications we read. Gmail Go is handled separately below (only its
     * per-account mail categories count, never its other notifications). Any other app that marks a
     * notification as an email (category "email") is also read, so this list does not have to be
     * complete; it just names the common apps so they are always recognized.
     */
    private static final Set<String> EMAIL_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.gm",                 // Gmail (the full app)
            "com.microsoft.office.outlook",          // Microsoft Outlook
            "com.microsoft.outlooklite",             // Outlook Lite
            "com.yahoo.mobile.client.android.mail",  // Yahoo Mail
            "com.samsung.android.email.provider",    // Samsung Email
            "com.android.email",                     // the stock Email app
            "ch.protonmail.android",                 // Proton Mail
            "org.kman.AquaMail",                     // Aqua Mail
            "eu.faircode.email",                     // FairEmail
            "com.fsck.k9",                           // K-9 Mail
            "me.bluemail.mail",                      // BlueMail
            "com.zoho.mail"                          // Zoho Mail
    ));

    private static final Object LOCK = new Object();
    /** Tags of our Home Depot copies currently in the tray. */
    private static final Set<String> live = new HashSet<>();
    /** Tags we are removing ourselves, so our own removal is never taken for a swipe you made. */
    private static final Set<String> selfRemoving = new HashSet<>();
    /**
     * The email behind each of our cards in the tray (tag to subject key). One email often arrives
     * through two email apps at once (web Gmail and Gmail Go), and it must still give one card only.
     */
    private static final Map<String, String> liveSubjects = new HashMap<>();
    /** Emails whose card you just tapped or swiped away (subject key to time), so a late copy of the
     *  same email from another email app does not bring the card back. */
    private static final Map<String, Long> recentlyDismissed = new HashMap<>();
    private static final long DISMISS_MEMORY_MS = 30 * 60 * 1000L;

    private HomeDepotBridge() {}

    /** Our Home Depot order channel. Pops on screen, like the app's own order alerts would. */
    static void createChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                c.getString(R.string.channel_homedepot), NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(c.getString(R.string.channel_homedepot_desc));
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    // ---- is this a Home Depot order signal? ----------------------------------------------------

    /**
     * Whether this notification is a mail notification from an email app. Gmail Go only counts for its
     * per-account mail categories (so its sign-in and sync notices are ignored). Any app in the known
     * list counts, and so does any app that marks its notification as an email, which covers the rest.
     */
    private static boolean isEmailApp(String pkg, String category, String channelId) {
        if (pkg == null) return Notification.CATEGORY_EMAIL.equals(category);
        if (Keeper.GMAIL_GO.equals(pkg)) return channelId != null && channelId.startsWith(MAIL_CHANNEL);
        if (EMAIL_PACKAGES.contains(pkg)) return true;
        return Notification.CATEGORY_EMAIL.equals(category);
    }

    /**
     * True if this notification is a Home Depot order email: a single mail notification (not a group
     * header) from an email app whose sender is Home Depot.
     */
    private static boolean isHomeDepotMail(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (!isEmailApp(sbn.getPackageName(), n.category, n.getChannelId())) return false;
        Bundle x = n.extras;
        if (x == null) return false;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        if (title == null) return false; // no sender to read
        return isHomeDepotSender(emailAddress(sbn), title);
    }

    /**
     * The full recognition test, from plain values, so the adb test trigger can check it without a
     * real email (see DebugReceiver): an email app and a Home Depot sender.
     */
    static boolean looksLikeHomeDepotEmail(String pkg, String category, String channelId,
            String address, CharSequence displayName) {
        return isEmailApp(pkg, category, channelId) && isHomeDepotSender(address, displayName);
    }

    /**
     * Whether an email's sender is Home Depot, by its address (any homedepot.com address or subdomain,
     * which only Home Depot can send from) or, failing that, a sender display name that says Home Depot.
     * Home Depot's order emails always use the display name "The Home Depot", which is what email apps
     * that do not expose the raw address (Outlook, for one) show. Kept simple and conservative on
     * purpose: a wrong guess would only show a normal email as a Home Depot card, never lose it. Public
     * so the adb test trigger can check a sender without a real email.
     */
    static boolean isHomeDepotSender(String address, CharSequence displayName) {
        if (address != null) {
            int at = address.indexOf('@');
            if (at >= 0) {
                String host = address.substring(at + 1).toLowerCase(Locale.ROOT).trim();
                if (host.equals("homedepot.com") || host.endsWith(".homedepot.com")) return true;
            }
        }
        if (displayName != null) {
            String d = displayName.toString().toLowerCase(Locale.ROOT);
            if (d.contains("home depot") || d.contains("homedepot")) return true;
        }
        return false;
    }

    /**
     * The sender's email address, if the notification carries it. Gmail attaches it as a mailto
     * contact (read exactly the way the Gmail mirror reads it); some apps instead put it in the title
     * as "Name &lt;address&gt;". Many apps (Outlook, for one) give only the display name, so this
     * returns null and the display name carries the match.
     */
    private static String emailAddress(StatusBarNotification sbn) {
        Bundle x = sbn.getNotification().extras;
        String a = GmailMirror.senderAddress(x);
        if (a != null) return a;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null) {
            String t = title.toString();
            int lt = t.indexOf('<');
            int gt = lt >= 0 ? t.indexOf('>', lt + 1) : -1;
            if (lt >= 0 && gt > lt) {
                String inner = t.substring(lt + 1, gt).trim();
                if (inner.indexOf('@') > 0) return inner;
            }
        }
        return null;
    }

    // ---- a mail notification was posted --------------------------------------------------------

    /**
     * Called for every posted notification. When it is a Home Depot order email, this shows the Home
     * Depot card, in addition to the email's own notification, which it never touches. For anything
     * else it does nothing. Our own notifications are skipped so we never react to our own cards.
     */
    static void onPosted(NotificationListenerService s, StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(s.getPackageName())) return;
        if (!isHomeDepotMail(sbn)) return;
        try {
            handle(s, sbn, true);
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "home depot: could not show an order card (the email itself is untouched)", e);
        }
    }

    /** Adds the Home Depot card for any Home Depot mail already showing when the listener (re)binds, quietly. */
    static void catchUp(NotificationListenerService s) {
        StatusBarNotification[] all;
        try {
            all = s.getActiveNotifications();
        } catch (RuntimeException e) {
            return;
        }
        if (all == null) return;
        List<String> duplicates = new ArrayList<>();
        synchronized (LOCK) {
            live.clear();
            liveSubjects.clear();
            for (StatusBarNotification x : all) {
                if (x.getTag() != null && x.getTag().startsWith(TAG_PREFIX)
                        && x.getPackageName().equals(s.getPackageName())) {
                    live.add(x.getTag());
                    String key = subjectKey(x.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
                    if (key != null && liveSubjects.containsValue(key)) {
                        duplicates.add(x.getKey()); // a second card for an email that already has one
                        live.remove(x.getTag());
                        selfRemoving.add(x.getTag());
                    } else if (key != null) {
                        liveSubjects.put(x.getTag(), key);
                    }
                }
            }
        }
        for (String k : duplicates) {
            try {
                s.cancelNotification(k);
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "home depot: could not remove a duplicate card", e);
            }
        }
        if (!duplicates.isEmpty()) Log.i(Keeper.TAG, "home depot: removed " + duplicates.size() + " duplicate card(s)");
        int n = 0;
        for (StatusBarNotification sbn : all) {
            if (sbn.getPackageName().equals(s.getPackageName())) continue;
            if (isHomeDepotMail(sbn)) {
                try {
                    if (handle(s, sbn, false)) n++;
                } catch (RuntimeException e) {
                    Log.w(Keeper.TAG, "home depot: catch-up failed for one email", e);
                }
            }
        }
        if (n > 0) Log.i(Keeper.TAG, "home depot: caught up on " + n + " order email(s)");
    }

    /** Shows the Home Depot card for one order email. Returns true if a card was posted. */
    private static boolean handle(NotificationListenerService s, StatusBarNotification sbn, boolean mayAlert) {
        String srcKey = sbn.getKey();
        String tag = TAG_PREFIX + srcKey;
        Notification n = sbn.getNotification();

        if (isDismissed(s, srcKey)) {
            // You swiped this Home Depot card away before. The email's own notification stays; we just
            // do not put our card back when the email notification re-posts.
            return false;
        }
        String subjectKey = subjectKey(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        synchronized (LOCK) {
            if (live.contains(tag)) return false; // our card is already up
            if (subjectKey != null && liveSubjects.containsValue(subjectKey)) {
                // The same email through a second email app (web Gmail and Gmail Go): one card only.
                Log.i(Keeper.TAG, "home depot: this email already has a card (it also came from "
                        + sbn.getPackageName() + "), so no second card");
                return false;
            }
            Long gone = subjectKey != null ? recentlyDismissed.get(subjectKey) : null;
            if (gone != null && System.currentTimeMillis() - gone < DISMISS_MEMORY_MS) {
                addDismissed(s, srcKey);
                return false; // you already tapped or cleared this email's card
            }
        }
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return false; // a sync or sending status, not a received email
        }
        if (mayAlert && System.currentTimeMillis() - sbn.getPostTime() > STALE_REPOST_MS) {
            // An old email re-appearing (an email app reconnecting), not new mail: show it, but quietly.
            mayAlert = false;
        }

        Bundle x = n.extras;
        String account = channelAccount(n.getChannelId());
        if (account == null) {
            CharSequence sub = x.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (sub != null) account = sub.toString();
        }
        CharSequence sender = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence subject = x.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        long when = n.when != 0 ? n.when : sbn.getPostTime();

        show(s, tag, sender, subject, big, when, mayAlert);
        synchronized (LOCK) {
            live.add(tag);
            if (subjectKey != null) liveSubjects.put(tag, subjectKey);
        }
        if (mayAlert) {
            SharedPreferences p = Keeper.prefs(s);
            p.edit().putInt("hd_count", p.getInt("hd_count", 0) + 1)
                    .putLong("hd_last_time", System.currentTimeMillis()).apply();
        }
        Log.i(Keeper.TAG, "home depot: showing an order card" + (mayAlert ? " (pop-up)" : " (quiet)")
                + " from " + sbn.getPackageName() + (account != null ? " / " + account : ""));
        return true;
    }

    /** Builds and posts the Home Depot order notification. */
    private static void show(Context c, String tag, CharSequence sender, CharSequence subject, CharSequence big,
            long when, boolean alert) {
        createChannel(c);
        // Tap: with the Home Depot Helper app installed, the tap goes to the exact place in the Home
        // Depot app (the order itself, the purchase history, or the sign-in code field); without it,
        // the tap opens the Home Depot app as before, or homedepot.com if the app is not installed.
        // Auto-cancel clears this card when you tap it.
        Intent open = tapIntent(c, subject, big);
        PendingIntent tap = PendingIntent.getActivity(c, tag.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // The whole card body is our own layout, filled Home Depot orange with white text and the
        // Home Depot logo on a white tile, so the card reads in Home Depot colours. Android only
        // colours a whole ordinary notification's background for foreground service or media
        // notifications, so a custom body is the only way to colour this one. The card never shows the
        // email address or the account, and it is not marked as an email notification, because this is
        // an order update, not mail. All of this applies only to the Home Depot card.
        CharSequence brand = (sender != null ? sender : c.getString(R.string.hd_brand))
                .toString().toUpperCase(Locale.ROOT);
        CharSequence headline = subject != null ? subject
                : (big != null ? big : c.getString(R.string.hd_brand));
        CharSequence detail = (big != null && !big.toString().trim().equals(String.valueOf(subject).trim()))
                ? big : null;
        Icon logo = homeDepotIcon(c);

        RemoteViews collapsed = new RemoteViews(c.getPackageName(), R.layout.hd_card);
        collapsed.setImageViewIcon(R.id.hd_logo, logo);
        collapsed.setTextViewText(R.id.hd_brand, brand);
        collapsed.setTextViewText(R.id.hd_headline, headline);

        RemoteViews expanded = new RemoteViews(c.getPackageName(), R.layout.hd_card_big);
        expanded.setImageViewIcon(R.id.hd_logo, logo);
        expanded.setTextViewText(R.id.hd_brand, brand);
        expanded.setTextViewText(R.id.hd_headline, headline);
        if (detail != null) {
            expanded.setTextViewText(R.id.hd_detail, detail);
        } else {
            expanded.setViewVisibility(R.id.hd_detail, android.view.View.GONE);
        }
        expanded.setTextViewText(R.id.hd_hint, c.getString(R.string.hd_tap_hint));

        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_stat))
                .setColor(HD_ORANGE)
                .setContentTitle(sender != null ? sender : c.getString(R.string.hd_brand))
                .setContentText(subject)
                .setWhen(when)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setGroup(GROUP)
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setCustomHeadsUpContentView(collapsed)
                .setContentIntent(tap);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.notify(tag, ID, b.build());
    }

    /**
     * What a tap on the card opens. The Home Depot Helper app (local.hdhelper, a separate small app)
     * reads the email's subject and preview and takes the Home Depot app to the exact place: the order
     * named in the email, the purchase history when no order number is named, or the sign-in code
     * field for a verification code. The Keeper only hands it the subject and preview, nothing else.
     * When the helper is not installed, the tap opens the Home Depot app itself, exactly as before.
     */
    private static Intent tapIntent(Context c, CharSequence subject, CharSequence big) {
        Intent helper = new Intent()
                .setClassName(HELPER_PACKAGE, HELPER_PACKAGE + ".OpenActivity")
                .putExtra("subject", subject)
                .putExtra("text", big)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (c.getPackageManager().resolveActivity(helper, 0) != null) return helper;
        } catch (RuntimeException e) {
            // fall through to opening the app itself
        }
        return new Intent(c, OpenActivity.class)
                .putExtra(OpenActivity.EXTRA_PACKAGE, "com.thehomedepot")
                .putExtra(OpenActivity.EXTRA_ORIGIN, "https://www.homedepot.com")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * The icon for the card. It shows the real Home Depot logo by reading the installed Home Depot
     * app's own launcher icon at runtime, so the genuine logo appears and this project ships no
     * copyrighted image of its own. If the Home Depot app is not installed, it falls back to a plain
     * Home Depot orange tile with a simple package outline, which is our own generic drawing.
     */
    private static Icon homeDepotIcon(Context c) {
        int size = Math.round(96 * c.getResources().getDisplayMetrics().density);
        try {
            Drawable logo = c.getPackageManager().getApplicationIcon("com.thehomedepot");
            if (logo != null) {
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                logo.setBounds(0, 0, size, size);
                logo.draw(new Canvas(bmp));
                return Icon.createWithBitmap(bmp);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // The Home Depot app is not installed; use the plain orange tile below.
        }
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(HD_ORANGE);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, size, size, p);
        // A white package: a box outline with a flap seam near the top and a short tape line.
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.07f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float l = size * 0.30f, t = size * 0.33f, r = size * 0.70f, bot = size * 0.70f;
        float seam = t + (bot - t) * 0.34f;
        canvas.drawRect(l, t, r, bot, p);
        canvas.drawLine(l, seam, r, seam, p);
        canvas.drawLine((l + r) / 2f, t, (l + r) / 2f, seam, p);
        return Icon.createWithBitmap(bmp);
    }

    // ---- one of our copies was removed ---------------------------------------------------------

    /**
     * A Home Depot card left the tray. If you removed it (a swipe or clearing the shade), remember it
     * so our card does not come back when the email's own notification re-posts. The email's own
     * notification is never touched. A removal the Keeper itself made is ignored.
     */
    static void onCopyRemoved(Context c, StatusBarNotification sbn, int reason) {
        String tag = sbn.getTag();
        if (tag == null || !tag.startsWith(TAG_PREFIX)) return;
        String srcKey = tag.substring(TAG_PREFIX.length());
        boolean weRemoved;
        String key;
        synchronized (LOCK) {
            live.remove(tag);
            key = liveSubjects.remove(tag);
            weRemoved = selfRemoving.remove(tag);
        }
        // The same reasoning as the Gmail mirror: decide by whether we asked for it, not by the reason
        // code (which differs between devices and between a single card and a stack). Anything removed
        // without the Keeper asking, other than the system's own churn, is your dismissal.
        boolean systemChurn = (reason >= 4 && reason <= 11) || (reason >= 13 && reason <= 22);
        boolean youDismissed = !weRemoved && !systemChurn;
        if (youDismissed) {
            addDismissed(c, srcKey);
            if (key != null) {
                // The same email may also be showing from another email app; its card must not come
                // back either, now or when that app re-posts it.
                synchronized (LOCK) {
                    long now = System.currentTimeMillis();
                    recentlyDismissed.entrySet().removeIf(e -> now - e.getValue() > DISMISS_MEMORY_MS);
                    recentlyDismissed.put(key, now);
                }
                KeeperListener l = KeeperListener.current;
                try {
                    if (l != null) {
                        for (StatusBarNotification x : l.getActiveNotifications()) {
                            if (x.getPackageName().equals(c.getPackageName()) || !isHomeDepotMail(x)) continue;
                            if (key.equals(subjectKey(x.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT)))) {
                                addDismissed(c, x.getKey());
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    Log.w(Keeper.TAG, "home depot: could not check for other copies of a dismissed email", e);
                }
            }
            Log.i(Keeper.TAG, "home depot: you dismissed an order card (reason " + reason
                    + "); it stays gone. Your email notification is untouched.");
        }
    }

    /** The email's subject reduced to a comparable key: case and spacing ignored. Null when empty. */
    private static String subjectKey(CharSequence subject) {
        if (subject == null) return null;
        String k = subject.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return k.isEmpty() ? null : k;
    }

    private static String channelAccount(String channelId) {
        return channelId != null && channelId.startsWith(MAIL_CHANNEL)
                ? channelId.substring(MAIL_CHANNEL.length()) : null;
    }

    // ---- order cards you dismissed on purpose (kept across restarts) ---------------------------

    static boolean isDismissed(Context c, String srcKey) {
        if (srcKey == null) return false;
        String s = Keeper.prefs(c).getString(PREF_DISMISSED, "");
        if (s.isEmpty()) return false;
        for (String k : s.split("\n")) if (k.equals(srcKey)) return true;
        return false;
    }

    private static synchronized void addDismissed(Context c, String srcKey) {
        SharedPreferences p = Keeper.prefs(c);
        String s = p.getString(PREF_DISMISSED, "");
        List<String> keys = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String k : s.split("\n")) if (!k.equals(srcKey)) keys.add(k);
        }
        keys.add(srcKey);
        while (keys.size() > MAX_DISMISSED) keys.remove(0);
        p.edit().putString(PREF_DISMISSED, String.join("\n", keys)).apply();
    }

    // ---- status window -------------------------------------------------------------------------

    static int liveCount(Context c) {
        int n = 0;
        for (StatusBarNotification x : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (x.getTag() != null && x.getTag().startsWith(TAG_PREFIX)) n++;
        }
        return n;
    }

    // ---- the adb test trigger (DebugReceiver) --------------------------------------------------

    /**
     * Posts a Home Depot order notification directly, to check that it renders, pops up, stays and
     * opens the app, without waiting for a real order email. Used only from adb (see DebugReceiver).
     */
    static void showTest(Context c, String sender, String subject, String account) {
        String tag = TAG_PREFIX + "test-" + System.currentTimeMillis();
        show(c, tag, sender != null ? sender : c.getString(R.string.hd_brand),
                subject != null ? subject : "Your order is out for delivery",
                null, System.currentTimeMillis(), true);
        synchronized (LOCK) {
            live.add(tag);
        }
        Log.i(Keeper.TAG, "home depot: posted a test order notification");
    }

    /**
     * Removes any test order notifications this module posted (adb only, see DebugReceiver). Prefers
     * the listener, whose getActiveNotifications is reliable; a plain app context's is not on this
     * desktop. Real cards are never cleared this way; they are only cleared by you or by the app.
     */
    static void clearTests(Context c) {
        int n = 0;
        KeeperListener l = KeeperListener.current;
        try {
            if (l != null) {
                for (StatusBarNotification x : l.getActiveNotifications()) {
                    if (x.getTag() != null && x.getTag().startsWith(TAG_PREFIX + "test-")) {
                        synchronized (LOCK) {
                            live.remove(x.getTag());
                            selfRemoving.add(x.getTag());
                        }
                        l.cancelNotification(x.getKey());
                        n++;
                    }
                }
            } else {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                for (StatusBarNotification x : nm.getActiveNotifications()) {
                    if (x.getTag() != null && x.getTag().startsWith(TAG_PREFIX + "test-")) {
                        synchronized (LOCK) {
                            live.remove(x.getTag());
                        }
                        nm.cancel(x.getTag(), ID);
                        n++;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "home depot: could not clear test notifications", e);
        }
        Log.i(Keeper.TAG, "home depot: cleared " + n + " test order notification(s)");
    }
}
