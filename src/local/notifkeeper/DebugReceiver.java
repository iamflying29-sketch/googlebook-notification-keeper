package local.notifkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        if (i.getStringExtra("probe") != null) {
            WebGmailHelper.probe(i.getStringExtra("probe"));
            return;
        }
        if (i.getBooleanExtra("windows", false)) {
            WebGmailHelper.logWindows();
            return;
        }
        if ("markread".equals(i.getStringExtra("ghost"))) {
            // Test the ghost Mark-as-read path: emit the same logcat command the button does.
            GhostReceiver.emit(GhostReceiver.markReadJson(i.getStringExtra("account"),
                    i.getStringExtra("address"), i.getStringExtra("subject"), i.getLongExtra("when", 0)));
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
        GmailMirror.openWebGmail(c, url);
    }
}
