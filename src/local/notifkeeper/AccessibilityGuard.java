package local.notifkeeper;

import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * Keeps the Keeper's Gmail accessibility helper (WebGmailHelper) switched on.
 *
 * Android turns an accessibility service off whenever its app is updated or reinstalled. That
 * silently broke tapping a Gmail notification to open the exact email, and the Reply and Delete
 * buttons, because those are carried out by the helper. With the WRITE_SECURE_SETTINGS permission
 * (granted once over adb, see the README), the Keeper turns its own helper back on by itself: on
 * boot, on its own update, every time the listener binds, every few hours through the guard job, and
 * the instant anything changes the accessibility setting. It never touches any other app's service.
 */
final class AccessibilityGuard {
    private static final String ENABLED = "enabled_accessibility_services"; // Settings.Secure
    private static final String ON = "accessibility_enabled";               // Settings.Secure
    private static ContentObserver observer;

    private AccessibilityGuard() {}

    private static String component(Context c) {
        return new ComponentName(c, WebGmailHelper.class).flattenToString();
    }

    /** If our helper is not in the enabled list, add it, leaving every other service untouched. */
    static void ensure(Context c) {
        try {
            String me = component(c);
            String cur = Settings.Secure.getString(c.getContentResolver(), ENABLED);
            boolean present = false;
            if (!TextUtils.isEmpty(cur)) {
                for (String s : cur.split(":")) {
                    if (me.equals(s)) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                String next = TextUtils.isEmpty(cur) ? me : cur + ":" + me;
                Settings.Secure.putString(c.getContentResolver(), ENABLED, next);
                Settings.Secure.putInt(c.getContentResolver(), ON, 1);
                Log.i(Keeper.TAG, "accessibility guard: the Gmail helper was off, turned it back on");
            }
        } catch (SecurityException e) {
            Log.w(Keeper.TAG, "accessibility guard: no WRITE_SECURE_SETTINGS permission, so the helper "
                    + "cannot turn itself back on. Grant it once over adb (see the README).");
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "accessibility guard: could not check the accessibility setting", e);
        }
    }

    /** Watch the setting so the helper returns the moment anything (an update, a reset) turns it off. */
    static synchronized void watch(Context c) {
        if (observer != null) return;
        final Context app = c.getApplicationContext();
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                ensure(app);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(ENABLED), false, observer);
            Log.i(Keeper.TAG, "accessibility guard: watching the accessibility setting");
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "accessibility guard: could not watch the accessibility setting", e);
            observer = null;
        }
    }
}
