package local.notifkeeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole keeping logic, shared by the listener, the status window and the guard job.
 *
 * Websites shown through Chrome (and web apps built on Chrome, like Gmail's web version on the
 * Googlebook) often withdraw their own notifications a few seconds after showing them. Android
 * then removes them from the tray with reason "app cancel". When that happens to a web
 * notification, we post a silent copy that stays until the user taps or clears it.
 */
final class Keeper {
    static final String TAG = "NotifKeeper";

    static final String CH_KEPT = "kept";
    static final String CH_ALERTS = "alerts";
    static final String CH_TEST = "test";

    static final String GMAIL = "com.google.android.gm";
    static final String GMAIL_GO = "com.google.android.gm.lite";
    static final String TEST_ORIGIN = "https://keeper.test";
    private static final String KEPT_TAG_PREFIX = "kept:";
    /** Android allows 50 notifications per app; Gmail copies (GmailMirror) take up to 25 + summaries. */
    private static final int MAX_KEPT = 15;

    /** Chrome's web notification tags: "p#<origin>#..." (persistent) or "n#<origin>#..." (page). */
    private static final Pattern WEB_TAG = Pattern.compile("^[pn]#(https?://[^/#]+)");

    private static final Object LOCK = new Object();
    /** Keys of copies we posted (oldest first), to cap how many we keep in the tray. */
    private static final ArrayDeque<String> keptTags = new ArrayDeque<>();
    /** Originals we already copied (key + post time), so one removal never yields two copies. */
    private static final ArrayDeque<String> copied = new ArrayDeque<>();
    private static int nextId = (int) (System.currentTimeMillis() & 0x3fffffff);

    private Keeper() {}

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel kept = new NotificationChannel(CH_KEPT,
                c.getString(R.string.channel_kept), NotificationManager.IMPORTANCE_DEFAULT);
        kept.setDescription(c.getString(R.string.channel_kept_desc));
        kept.setSound(null, null); // the original already alerted; the copy just stays
        kept.enableVibration(false);
        nm.createNotificationChannel(kept);
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS,
                c.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(alerts);
        NotificationChannel test = new NotificationChannel(CH_TEST,
                c.getString(R.string.channel_test), NotificationManager.IMPORTANCE_HIGH);
        test.setSound(null, null);
        nm.createNotificationChannel(test);
    }

    /** The website origin if this is a web notification, else null. */
    static String webOrigin(StatusBarNotification sbn) {
        String tag = sbn.getTag();
        if (tag != null) {
            Matcher m = WEB_TAG.matcher(tag);
            if (m.find()) return m.group(1);
        }
        String group = sbn.getNotification().getGroup();
        if (group != null && group.startsWith("Web:") && group.length() > 4) {
            return "https://" + group.substring(4);
        }
        return null;
    }

    /** Should a copy be kept for this removed notification? */
    static boolean shouldKeep(Context c, StatusBarNotification sbn, int reason) {
        if (reason != NotificationListenerService.REASON_APP_CANCEL
                && reason != NotificationListenerService.REASON_APP_CANCEL_ALL) {
            return false; // the user tapped/swiped/cleared it, or the system removed it
        }
        String origin = webOrigin(sbn);
        if (origin == null) return false;
        String pkg = sbn.getPackageName();
        if (pkg.equals(c.getPackageName())) {
            return origin.equals(TEST_ORIGIN); // only our own "Run test" notifications
        }
        Notification n = sbn.getNotification();
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return false;
        }
        // With the full Gmail app installed, Gmail's own (native) notifications already stay in the
        // tray, so a copy of its web notification would only be a duplicate.
        if (pkg.equals(GMAIL) && gmailState(c) == GmailState.FULL) return false;
        // Same for the web Gmail's new-mail notifications while Gmail Go's are shown (GmailMirror).
        if (GmailMirror.isWebGmailMail(c, sbn)) return false;
        return true;
    }

    /** Posts a silent copy of a withdrawn web notification. */
    static void keep(Context c, StatusBarNotification sbn) {
        String once = sbn.getKey() + "@" + sbn.getPostTime();
        String keptTag;
        int id;
        synchronized (LOCK) {
            if (copied.contains(once)) return;
            copied.addLast(once);
            while (copied.size() > 200) copied.removeFirst();
            id = nextId++;
            keptTag = KEPT_TAG_PREFIX + id;
        }

        Notification orig = sbn.getNotification();
        Bundle x = orig.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String origin = webOrigin(sbn);
        String source = sourceLabel(c, sbn.getPackageName(), origin);
        if (title == null && text == null) title = source;

        Intent open = new Intent(c, OpenActivity.class)
                .putExtra(OpenActivity.EXTRA_PACKAGE, sbn.getPackageName())
                .putExtra(OpenActivity.EXTRA_ORIGIN, origin)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(c, CH_KEPT)
                .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_stat))
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(source)
                .setWhen(sbn.getPostTime())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tap)
                .setGroup(KEPT_TAG_PREFIX + sbn.getPackageName());
        if (big != null || text != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(big != null ? big : text));
        }
        Icon large = orig.getLargeIcon();
        if (large != null && (large.getType() == Icon.TYPE_BITMAP
                || large.getType() == Icon.TYPE_ADAPTIVE_BITMAP)) {
            b.setLargeIcon(large);
        }
        if (orig.color != Notification.COLOR_DEFAULT) b.setColor(orig.color);
        if (orig.category != null) b.setCategory(orig.category);

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try {
            nm.notify(keptTag, id, b.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not post kept copy", e);
            return;
        }
        String drop = null;
        synchronized (LOCK) {
            keptTags.addLast(keptTag + "|" + id);
            if (keptTags.size() > MAX_KEPT) drop = keptTags.removeFirst();
        }
        if (drop != null) {
            int bar = drop.lastIndexOf('|');
            nm.cancel(drop.substring(0, bar), Integer.parseInt(drop.substring(bar + 1)));
        }
        SharedPreferences p = prefs(c);
        p.edit().putInt("kept_count", p.getInt("kept_count", 0) + 1)
                .putLong("last_kept_time", System.currentTimeMillis())
                .putString("last_kept_source", source)
                .apply();
        Log.i(TAG, "kept a copy of a withdrawn notification from " + source);
    }

    /** "Gmail", or "Chrome · example.com". */
    static String sourceLabel(Context c, String pkg, String origin) {
        String app = appLabel(c, pkg);
        if (TEST_ORIGIN.equals(origin)) return c.getString(R.string.test_source);
        if (origin == null) return app;
        String host = origin.replaceFirst("^https?://", "");
        // A web app (wrapper package) is best described by its own name.
        if (!pkg.equals("com.android.chrome") && !pkg.startsWith("com.chrome.")) return app;
        return app + " · " + host;
    }

    static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Removes every copy we posted. */
    static void clearKept(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (StatusBarNotification s : nm.getActiveNotifications()) {
            if (s.getTag() != null && s.getTag().startsWith(KEPT_TAG_PREFIX)) {
                nm.cancel(s.getTag(), s.getId());
            }
        }
        synchronized (LOCK) {
            keptTags.clear();
        }
    }

    static int keptInTray(Context c) {
        int n = 0;
        for (StatusBarNotification s : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (s.getTag() != null && s.getTag().startsWith(KEPT_TAG_PREFIX)) n++;
        }
        return n;
    }

    // ---- Gmail guard -------------------------------------------------------------------------

    enum GmailState { FULL, WEB, MISSING }

    /** FULL = the real Gmail app (receives Google's new-mail pushes); WEB = the web-only wrapper. */
    static GmailState gmailState(Context c) {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(GMAIL, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return GmailState.MISSING;
        }
        if (pi.applicationInfo == null || !pi.applicationInfo.enabled) return GmailState.MISSING;
        boolean twa = pi.versionName != null && pi.versionName.endsWith(".twa");
        boolean pushReceiver = !pm.queryBroadcastReceivers(
                new Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(GMAIL), 0).isEmpty();
        return (!twa && pushReceiver) ? GmailState.FULL : GmailState.WEB;
    }

    static String gmailVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(GMAIL, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    static boolean listenerAllowed(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                new ComponentName(c, KeeperListener.class));
    }

    /**
     * Checks Gmail and our own notification access; alerts once per problem (again only after it
     * was fixed and came back). Safe to call often.
     */
    static void runGuard(Context c) {
        createChannels(c);
        SharedPreferences p = prefs(c);
        GmailState g = gmailState(c);
        GmailMirror.Engine go = GmailMirror.engine(c);
        // Fine if either the full Gmail gets pushes itself, or Gmail Go does (then the full Gmail
        // being in web mode is expected). Without Gmail Go installed, the Gmail setup is not in use,
        // so there is nothing to warn about.
        boolean ok = g == GmailState.FULL || go == GmailMirror.Engine.OK
                || go == GmailMirror.Engine.MISSING;
        String gmailKey = ok ? "" : g + ":" + gmailVersion(c) + "/go:" + go + ":" + GmailMirror.goVersion(c);
        if (!gmailKey.equals(p.getString("gmail_alerted", ""))) {
            if (!ok) {
                alert(c, 1, c.getString(R.string.alert_go_title), c.getString(R.string.alert_go_blocked));
            } else {
                c.getSystemService(NotificationManager.class).cancel(1);
            }
            p.edit().putString("gmail_alerted", gmailKey).apply();
        }
        boolean access = listenerAllowed(c);
        if (access != p.getBoolean("access_ok", true)) {
            if (!access) {
                alert(c, 2, c.getString(R.string.alert_access_title), c.getString(R.string.alert_access_text));
            } else {
                c.getSystemService(NotificationManager.class).cancel(2);
            }
            p.edit().putBoolean("access_ok", access).apply();
        }
    }

    private static void alert(Context c, int id, String title, String text) {
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(c, CH_ALERTS)
                .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_stat))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(c, 100 + id, open, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not post alert", e);
        }
    }

    // ---- Self-test ---------------------------------------------------------------------------

    /**
     * Posts a fake website notification (tagged like Chrome's) and withdraws it after 7 seconds,
     * exactly like Gmail's web page does. If everything works, a kept copy appears right after.
     */
    static void postTest(Context c, android.os.Handler h) {
        createChannels(c);
        final String tag = "p#" + TEST_ORIGIN + "#1test-" + System.currentTimeMillis();
        Notification n = new Notification.Builder(c, CH_TEST)
                .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_stat))
                .setContentTitle(c.getString(R.string.test_title))
                .setContentText(c.getString(R.string.test_text))
                .setGroup("Web:keeper.test")
                .build();
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.notify(tag, -1, n);
        h.postDelayed(() -> nm.cancel(tag, -1), 7000);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("keeper", Context.MODE_PRIVATE);
    }
}
