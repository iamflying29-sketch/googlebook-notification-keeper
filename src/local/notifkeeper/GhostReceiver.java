package local.notifkeeper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Locale;

/**
 * The Mark-as-read button on a Gmail notification. Instead of driving the web Gmail on screen (the
 * WebGmailHelper, used for Reply/Delete/tap), this emits a one-line command on logcat under the tag
 * "GmailGhostCmd". The ghost daemon on the Linux side (ghostd.py --adb) is listening, and marks that
 * exact email read in a hidden, already-logged-in Chromium - instantly, with no window.
 *
 * The command carries the account, the sender's email address, the subject and the arrival time, so
 * the daemon can pin the exact message. The Keeper then removes its own copy right away.
 */
public class GhostReceiver extends BroadcastReceiver {
    static final String TAG = "GmailGhostCmd";
    static final String EXTRA_ACCOUNT = "account";
    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_SUBJECT = "subject";
    static final String EXTRA_WHEN = "when";
    static final String EXTRA_CANCEL_TAG = "cancel";

    @Override
    public void onReceive(Context c, Intent i) {
        String account = i.getStringExtra(EXTRA_ACCOUNT);
        String address = i.getStringExtra(EXTRA_ADDRESS);
        String subject = i.getStringExtra(EXTRA_SUBJECT);
        long when = i.getLongExtra(EXTRA_WHEN, 0);
        emit(markReadJson(account, address, subject, when));
        GmailMirror.removeCopy(c, i.getStringExtra(EXTRA_CANCEL_TAG));
    }

    /** Emit a command on logcat for the ghost daemon. */
    static void emit(String json) {
        Log.i(TAG, json);
    }

    static String markReadJson(String account, String address, String subject, long when) {
        return "{" + kv("id", id()) + "," + num("ts", System.currentTimeMillis()) + ","
                + kv("op", "markread") + "," + kv("account", account) + "," + kv("address", address) + ","
                + kv("subject", subject) + "," + num("when", when) + "}";
    }

    /** Ask the daemon to warm its browser for this account (sent when new mail arrives). */
    static String prewarmJson(String account) {
        return "{" + kv("id", id()) + "," + num("ts", System.currentTimeMillis()) + ","
                + kv("op", "prewarm") + "," + kv("account", account) + "}";
    }

    private static String accountJson(String op, String account) {
        return "{" + kv("id", id()) + "," + num("ts", System.currentTimeMillis()) + ","
                + kv("op", op) + "," + kv("account", account) + "}";
    }

    private static final String MAIL_CHANNEL = "^nc_1_mail_";

    /**
     * Auto-tie: a Gmail Go mail category was turned on or off. On -> ask the daemon to set that
     * account up for instant Mark-as-read (it pops a one-time sign-in). Off -> ask the daemon to sign
     * it out and set its login aside. Only Gmail accounts (the ghost browser is Gmail web); debounced
     * so it only fires on an actual on<->off change.
     */
    static void onGmailGoChannel(Context c, NotificationChannel channel) {
        if (channel == null) return;
        String id = channel.getId();
        if (id == null || !id.startsWith(MAIL_CHANNEL)) return;
        String account = id.substring(MAIL_CHANNEL.length());
        String low = account.toLowerCase(Locale.ROOT);
        if (!(low.endsWith("@gmail.com") || low.endsWith("@googlemail.com"))) return; // ghost = Gmail only
        boolean on = channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        SharedPreferences p = Keeper.prefs(c);
        String key = "ghost_on_" + account;
        if (p.contains(key) && p.getBoolean(key, false) == on) return; // no change
        p.edit().putBoolean(key, on).apply();
        emit(accountJson(on ? "account_enable" : "account_disable", account));
        Log.i(Keeper.TAG, "auto-tie: " + account + " notifications " + (on ? "ON -> enable ghost" : "OFF -> disable ghost"));
    }

    private static String id() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String kv(String k, String v) {
        return "\"" + k + "\":\"" + esc(v) + "\"";
    }

    private static String num(String k, long v) {
        return "\"" + k + "\":" + v;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n' || ch == '\r' || ch == '\t') b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }
}
