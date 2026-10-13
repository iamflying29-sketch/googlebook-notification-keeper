package local.notifkeeper;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Android binds this service as long as Notification Keeper has notification access. When a web
 * notification is withdrawn by the site itself, it keeps a copy; Gmail Go's notifications are
 * shown as copies linked to the web Gmail (GmailMirror). It does no polling and holds no wake locks.
 */
public class KeeperListener extends NotificationListenerService {
    private BroadcastReceiver packageReceiver;

    @Override
    public void onListenerConnected() {
        Keeper.createChannels(this);
        Keeper.runGuard(this);
        GuardJob.schedule(this);
        SettingsGuard.sync(this);
        try {
            GmailMirror.reloadLive(this);
            GmailMirror.mirrorExisting(this);
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "gmail mirror: catching up failed", e);
        }
        if (packageReceiver == null) {
            // Notices the Play Store swapping Gmail back to the web version right away.
            packageReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
                    if (Keeper.GMAIL.equals(pkg) || Keeper.GMAIL_GO.equals(pkg)) {
                        Keeper.runGuard(c);
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REPLACED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            registerReceiver(packageReceiver, f, Context.RECEIVER_EXPORTED);
        }
        Log.i(Keeper.TAG, "listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        unregisterPackageReceiver();
        // Ask Android to bind us again (it normally does this by itself).
        requestRebind(new ComponentName(this, KeeperListener.class));
    }

    @Override
    public void onDestroy() {
        unregisterPackageReceiver();
        super.onDestroy();
    }

    private void unregisterPackageReceiver() {
        if (packageReceiver != null) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            packageReceiver = null;
        }
    }

    // Only delivered to companion-device listeners: Gmail (or Gmail Go) created, changed or
    // deleted a category.
    @Override
    public void onNotificationChannelModified(String pkg, UserHandle user, NotificationChannel channel,
            int modificationType) {
        if (SettingsGuard.guards(pkg)) syncSafely(pkg);
        // Auto-tie: when a Gmail Go mail category is turned on/off, enable/disable ghost mark-read.
        if (Keeper.GMAIL_GO.equals(pkg)) {
            try {
                GhostReceiver.onGmailGoChannel(this, channel);
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "auto-tie failed", e);
            }
        }
    }

    @Override
    public void onNotificationChannelGroupModified(String pkg, UserHandle user, NotificationChannelGroup group,
            int modificationType) {
        if (SettingsGuard.guards(pkg)) syncSafely(pkg);
    }

    private void syncSafely(String pkg) {
        try {
            SettingsGuard.sync(this, pkg);
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "settings guard failed", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        try {
            String pkg = sbn.getPackageName();
            if (Keeper.GMAIL_GO.equals(pkg)) {
                GmailMirror.onPosted(this, sbn, rankingMap, true);
            } else if (Keeper.GMAIL.equals(pkg)) {
                GmailMirror.onWebGmailPosted(this, sbn);
            }
        } catch (RuntimeException e) {
            // Never let one odd notification take the keeper down.
            Log.w(Keeper.TAG, "could not handle a posted notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        try {
            if (getPackageName().equals(sbn.getPackageName())) GmailMirror.onCopyRemoved(this, sbn);
            if (Keeper.shouldKeep(this, sbn, reason)) Keeper.keep(this, sbn);
        } catch (RuntimeException e) {
            // Never let one odd notification take the keeper down.
            Log.w(Keeper.TAG, "could not handle a removed notification", e);
        }
    }
}
