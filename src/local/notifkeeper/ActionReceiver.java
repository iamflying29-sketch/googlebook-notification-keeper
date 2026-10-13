package local.notifkeeper;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * A background button on one of our copies of Gmail Go's other (non-email) notifications: runs
 * Gmail Go's own action (stored in the button itself, so it still works after we restart), then
 * removes the copy. Email buttons go through the web Gmail instead (WebGmailHelper).
 */
public class ActionReceiver extends BroadcastReceiver {
    static final String EXTRA_ACTION = "action";
    static final String EXTRA_TAG = "tag";

    @Override
    public void onReceive(Context c, Intent i) {
        PendingIntent action = i.getParcelableExtra(EXTRA_ACTION, PendingIntent.class);
        if (action != null) {
            try {
                action.send();
                Log.i(Keeper.TAG, "gmail mirror: ran Gmail Go's button");
            } catch (PendingIntent.CanceledException e) {
                Log.w(Keeper.TAG, "gmail mirror: Gmail Go's button is no longer valid", e);
            }
        }
        GmailMirror.removeCopy(c, i.getStringExtra(EXTRA_TAG));
    }
}
