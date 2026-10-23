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

    /** Where the durable copy of each command goes, as the Linux VM sees it:
     *  /mnt/shared/Documents/Notification Keeper/cmd/<id>.json . Its own subfolder, so the daemon only
     *  ever looks at command files and never at anything else in the Notification Keeper folder. */
    static final String CMD_DIR = android.os.Environment.DIRECTORY_DOCUMENTS + "/Notification Keeper/cmd/";

    @Override
    public void onReceive(Context c, Intent i) {
        markReadNow(c, i.getStringExtra(EXTRA_ACCOUNT), i.getStringExtra(EXTRA_ADDRESS),
                i.getStringExtra(EXTRA_SUBJECT), i.getLongExtra(EXTRA_WHEN, 0), i.getStringExtra(EXTRA_CANCEL_TAG));
    }

    /**
     * Sends a Mark-as-read to the ghost daemon two ways at once, then removes our copy. The logcat line
     * is the instant path. The durable file is the backstop: the daemon drains it whenever it runs,
     * with no time limit, so a tap survives a Wi-Fi outage, the adb port moving, a daemon restart or a
     * reboot. Whichever arrives second is ignored, because the daemon dedupes by the command id.
     */
    static void markReadNow(Context c, String account, String address, String subject, long when, String cancelTag) {
        String id = id();
        String json = markReadJson(id, account, address, subject, when);
        emit(json);
        dropCmdFile(c, id, json);
        GmailMirror.removeCopy(c, cancelTag);
    }

    /** Emit a command on logcat for the ghost daemon (the instant path). */
    static void emit(String json) {
        Log.i(TAG, json);
    }

    /**
     * Writes the command as a durable file in the shared Documents folder via MediaStore, exactly the
     * way the Sleep Guard helper publishes its port file. If this fails we have already emitted on
     * logcat, so the normal case loses nothing; the file only has to carry a tap the daemon missed.
     */
    private static void dropCmdFile(Context c, String id, String json) {
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            android.net.Uri files = android.provider.MediaStore.Files.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, id + ".json");
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, CMD_DIR);
            android.net.Uri uri = cr.insert(files, v);
            if (uri == null) {
                Log.w(Keeper.TAG, "ghost: the Documents folder refused a command file (logcat was still sent)");
                return;
            }
            try (java.io.OutputStream out = cr.openOutputStream(uri, "wt")) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Log.i(Keeper.TAG, "ghost: wrote a durable command file for the daemon (" + id + ".json)");
        } catch (Exception e) {
            Log.w(Keeper.TAG, "ghost: could not write a durable command file (logcat was still sent)", e);
        }
    }

    static String markReadJson(String account, String address, String subject, long when) {
        return markReadJson(id(), account, address, subject, when);
    }

    static String markReadJson(String id, String account, String address, String subject, long when) {
        return "{" + kv("id", id) + "," + num("ts", System.currentTimeMillis()) + ","
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
