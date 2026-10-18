package local.notifkeeper;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Gmail Go's unread counts, read from its public content provider (the one launchers use for badge
 * numbers): one row per label with numUnreadConversations. Reading it needs Gmail Go's own permission
 * com.google.android.gm.permission.READ_CONTENT_PROVIDER, which the manifest declares so it is granted on install.
 *
 * We keep the last counts seen per account. A rise tells which labels a new mail carries (its
 * signature); a fall tells that something was read, archived or deleted on some device.
 */
final class GoCounts {
    static final String PERMISSION = "com.google.android.gm.permission.READ_CONTENT_PROVIDER";
    static final String INBOX = "^i";
    private static final String AUTHORITY = "com.google.android.gm.lite";

    private static final Object LOCK = new Object();
    private static final Map<String, Map<String, Integer>> last = new HashMap<>();
    private static final Set<String> observed = new HashSet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean deniedLogged;

    private GoCounts() {}

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private static Uri labels(String account) {
        return Uri.parse("content://" + AUTHORITY + "/" + account + "/labels");
    }

    /** Current unread count per label (canonical names such as ^i, ^iim, ^sq_ig_i_personal), or null. */
    static Map<String, Integer> read(Context c, String account) {
        if (account == null) return null;
        try (Cursor cur = c.getContentResolver().query(labels(account),
                new String[] {"canonicalName", "numUnreadConversations"}, null, null, null)) {
            if (cur == null) return null;
            int ci = cur.getColumnIndex("canonicalName");
            int cu = cur.getColumnIndex("numUnreadConversations");
            if (ci < 0 || cu < 0) return null;
            Map<String, Integer> m = new HashMap<>();
            while (cur.moveToNext()) {
                String name = cur.getString(ci);
                if (name != null && name.startsWith("^")) m.put(name, cur.getInt(cu));
            }
            return m;
        } catch (SecurityException e) {
            if (!deniedLogged) {
                deniedLogged = true;
                Log.w(Keeper.TAG, "go counts: not allowed to read Gmail Go's counts; grant " + PERMISSION);
            }
            return null;
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "go counts: could not read Gmail Go's counts", e);
            return null;
        }
    }

    /** Remember the current counts as the baseline. */
    static void snapshot(Context c, String account) {
        Map<String, Integer> now = read(c, account);
        if (now == null) return;
        synchronized (LOCK) {
            last.put(account, now);
        }
    }

    /** Labels whose unread count rose since the last look (a new mail's signature). Updates the baseline. */
    static Set<String> arrived(Context c, String account) {
        Set<String> sig = new HashSet<>();
        Map<String, Integer> now = read(c, account);
        if (now != null) {
            Map<String, Integer> before;
            synchronized (LOCK) {
                before = last.put(account, now);
            }
            if (before != null) {
                for (Map.Entry<String, Integer> e : now.entrySet()) {
                    Integer b = before.get(e.getKey());
                    if (b != null && e.getValue() > b) sig.add(e.getKey());
                }
            }
        }
        if (sig.isEmpty()) sig.add(INBOX);
        Log.i(Keeper.TAG, "go counts: new mail fingerprint " + sig);
        return sig;
    }

    /** Labels whose unread count fell since the last look, with how much. Updates the baseline. Null = unreadable. */
    static Map<String, Integer> dropped(Context c, String account) {
        Map<String, Integer> now = read(c, account);
        if (now == null) return null;
        Map<String, Integer> before;
        synchronized (LOCK) {
            before = last.put(account, now);
        }
        Map<String, Integer> drops = new HashMap<>();
        if (before == null) return drops;
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            Integer n = now.get(e.getKey());
            if (n != null && n < e.getValue()) drops.put(e.getKey(), e.getValue() - n);
        }
        return drops;
    }

    /** Be told by Gmail Go when an account's counts change, so a read is caught without waiting. */
    static void observe(NotificationListenerService s, String account) {
        if (account == null) return;
        synchronized (LOCK) {
            if (!observed.add(account)) return;
        }
        try {
            s.getContentResolver().registerContentObserver(labels(account), true, new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    ReadSense.poke(s);
                }
            });
            Log.i(Keeper.TAG, "go counts: watching Gmail Go's counts for " + account);
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "go counts: could not watch Gmail Go's counts", e);
        }
    }
}
