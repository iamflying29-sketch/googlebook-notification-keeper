package local.notifkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Test trigger for the Gmail helper, usable only from adb (the receiver requires the DUMP
 * permission, which apps don't have): does exactly what tapping a Gmail notification (or its Reply
 * button) does, for an email that's already in the mailbox.
 *
 *   adb shell am broadcast -n local.notifkeeper/.DebugReceiver --es account A --es sender S
 *       --es subject T [--es address ADDR] [--es do open|reply|delete|read] [--el when MILLIS]
 *   adb shell am broadcast -n local.notifkeeper/.DebugReceiver --ez windows true   (logs the windows it sees)
 */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i.hasExtra("readprobe")) {
            KeeperListener l = KeeperListener.current;
            if (l == null) { Log.i(Keeper.TAG, "readprobe: listener not connected"); return; }
            GmailMirror.probe(l, i.getStringExtra("readprobe"));
            return;
        }
        if (i.getStringExtra("probe") != null) {
            WebGmailHelper.probe(i.getStringExtra("probe"));
            return;
        }
        if (i.getBooleanExtra("windows", false)) {
            WebGmailHelper.logWindows();
            return;
        }

        if (i.getBooleanExtra("dumpgo", false)) {
            KeeperListener l = KeeperListener.current;
            if (l == null) { Log.i(Keeper.TAG, "dumpgo: listener not connected"); return; }
            java.util.List<StatusBarNotification> all = new java.util.ArrayList<>();
            try {
                for (StatusBarNotification x : l.getActiveNotifications()) if (Keeper.GMAIL_GO.equals(x.getPackageName())) all.add(x);
                StatusBarNotification[] sn = l.getSnoozedNotifications();
                if (sn != null) for (StatusBarNotification x : sn) if (Keeper.GMAIL_GO.equals(x.getPackageName())) all.add(x);
            } catch (RuntimeException e) { Log.w(Keeper.TAG, "dumpgo: " + e); return; }
            Log.i(Keeper.TAG, "dumpgo: " + all.size() + " Gmail Go notification(s)");
            for (StatusBarNotification x : all) {
                android.app.Notification n = x.getNotification();
                Log.i(Keeper.TAG, "dumpgo key=" + x.getKey() + " id=" + x.getId() + " posted=" + x.getPostTime() + " tag=" + x.getTag()
                        + " group=" + n.getGroup() + " shortcut=" + n.getShortcutId() + " sortKey=" + n.getSortKey());
                android.os.Bundle b = n.extras;
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    String sv = v instanceof Object[] ? java.util.Arrays.toString((Object[]) v) : String.valueOf(v);
                    if (sv.length() > 120) sv = sv.substring(0, 120) + "...";
                    Log.i(Keeper.TAG, "  extra " + k + " (" + (v == null ? "null" : v.getClass().getSimpleName()) + ") = " + sv);
                }
                scanUris("contentIntent", n.contentIntent);
                scanUris("deleteIntent", n.deleteIntent);
            }
            return;
        }
        if (i.hasExtra("query")) {
            // adb research probe: log the columns and first rows of a content URI
            // (adb shell am broadcast ... --es query <uri> [--es proj a,b] [--es sel ...] [--ei max N])
            String[] proj = i.hasExtra("proj") ? i.getStringExtra("proj").split(",") : null;
            int max = i.getIntExtra("max", 5);
            try (android.database.Cursor cur = c.getContentResolver().query(android.net.Uri.parse(i.getStringExtra("query")),
                    proj, i.getStringExtra("sel"), null, i.getStringExtra("sort"))) {
                if (cur == null) { Log.i(Keeper.TAG, "query: null cursor"); return; }
                Log.i(Keeper.TAG, "query: " + cur.getCount() + " rows; columns " + java.util.Arrays.toString(cur.getColumnNames()));
                int n = 0;
                while (cur.moveToNext() && n++ < max) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < cur.getColumnCount(); k++) {
                        String v;
                        try { v = cur.getType(k) == android.database.Cursor.FIELD_TYPE_BLOB ? "<blob>" : cur.getString(k); }
                        catch (RuntimeException e) { v = "<?>"; }
                        if (v != null && v.length() > 60) v = v.substring(0, 60) + "...";
                        sb.append(cur.getColumnName(k)).append('=').append(v).append(" | ");
                    }
                    Log.i(Keeper.TAG, "query row: " + sb);
                }
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "query failed: " + e);
            }
            return;
        }
        if (i.hasExtra("counts")) {
            // adb test: log Gmail Go's unread counts for an account (adb shell am broadcast ... --es counts <address>)
            Log.i(Keeper.TAG, "go counts for " + i.getStringExtra("counts") + ": " + GoCounts.read(c, i.getStringExtra("counts")));
            return;
        }
        if ("markread".equals(i.getStringExtra("ghost"))) {
            // Test the ghost Mark-as-read path: send it both ways, exactly as the button does.
            GhostReceiver.markReadNow(c, i.getStringExtra("account"), i.getStringExtra("address"),
                    i.getStringExtra("subject"), i.getLongExtra("when", 0), null);
            return;
        }
        String subject = i.getStringExtra("subject");
        String sender = i.getStringExtra("sender");
        String account = i.getStringExtra("account");
        String what = i.getStringExtra("do");
        WebGmailHelper.Kind kind = WebGmailHelper.Kind.valueOf(what == null ? "OPEN" : what.toUpperCase(java.util.Locale.ROOT));
        String url = GmailMirror.inboxUrl(account);
        Log.i(Keeper.TAG, "test trigger: " + kind + " in the web Gmail");
        WebGmailHelper.start(kind, subject, sender, i.getStringExtra("address"), account, i.getLongExtra("when", 0), url, null);
        if (!WebGmailHelper.isRunning()) GmailMirror.openWebGmail(c, url);
    }

    private static void scanUris(String label, android.app.PendingIntent pi) {
        if (pi == null) return;
        try {
            // PendingIntent hides its Intent; its creatorPackage and toString sometimes reveal the target.
            Log.i(Keeper.TAG, "  " + label + " -> " + pi + " creator=" + pi.getCreatorPackage());
        } catch (RuntimeException e) {
            // ignore
        }
    }
}
