package local.notifkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an update of this app: re-check Gmail/access and keep the guard job. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Keeper.runGuard(c);
        GuardJob.schedule(c);
    }
}
