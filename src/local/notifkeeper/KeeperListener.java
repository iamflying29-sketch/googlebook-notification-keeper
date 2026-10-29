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
    static volatile KeeperListener current;
    private BroadcastReceiver packageReceiver;

    @Override
    public void onListenerConnected() {
        Keeper.createChannels(this);
        Keeper.runGuard(this);
        AccessibilityGuard.watch(this);
        GuardJob.schedule(this);
        SettingsGuard.sync(this);
        // A fresh service instance: drop watchers and ticks tied to any previous instance so they
        // re-register against this one. Otherwise read detection goes dead after the first reconnect.
        GoCounts.reset(this);
        ReadSense.reset();
        // Home Depot order emails already showing (from any email app): add the Home Depot card for
        // each, quietly. It only adds cards and never touches the email notifications. Its own try, so
        // a failure here can never stop the Gmail catch-up below.
        try {
            HomeDepotBridge.catchUp(this);
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "home depot: catching up failed", e);
        }
        try {
            GmailMirror.reloadLive(this);
            GmailMirror.reloadTracking(this);
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
        current = this;
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
            // A Home Depot order email also gets its own Home Depot notification, in addition to the
            // normal email notification. This only adds a card: it never hides or changes the email,
            // and it works whether the email came through Gmail Go or another email app such as
            // Outlook. The Gmail handling below still runs exactly as before for every notification.
            HomeDepotBridge.onPosted(this, sbn);
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
            if (getPackageName().equals(sbn.getPackageName())) {
                GmailMirror.onCopyRemoved(this, sbn, reason);     // ignores Home Depot (hd:) tags
                HomeDepotBridge.onCopyRemoved(this, sbn, reason); // ignores Gmail mirror (gm:) tags
            }
            if (Keeper.GMAIL_GO.equals(sbn.getPackageName())) GmailMirror.onGoNotificationRemoved(this, sbn, reason);
            if (Keeper.shouldKeep(this, sbn, reason)) Keeper.keep(this, sbn);
        } catch (RuntimeException e) {
            // Never let one odd notification take the keeper down.
            Log.w(Keeper.TAG, "could not handle a removed notification", e);
        }
    }
}
