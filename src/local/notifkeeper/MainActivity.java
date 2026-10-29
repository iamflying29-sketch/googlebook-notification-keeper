package local.notifkeeper;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Status window. Just a window - closing it (X) never affects the keeper itself, which Android
 * runs in the background as long as notification access is on.
 */
public class MainActivity extends Activity {
    private static final long REFRESH_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private TextView access, notifs, gmail, homedepot, guard, stats;
    private Button fixAccess, fixNotifs;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Keeper.createChannels(this);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text(getString(R.string.app_name), 22, true));
        TextView about = text(getString(R.string.about), 14, false);
        about.setPadding(0, pad / 2, 0, pad);
        root.addView(about);

        access = text("", 16, false);
        fixAccess = button(getString(R.string.button_fix), v -> startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(this, KeeperListener.class).flattenToString())));
        root.addView(row(access, fixAccess));

        notifs = text("", 16, false);
        fixNotifs = button(getString(R.string.button_fix), v -> startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        root.addView(row(notifs, fixNotifs));

        gmail = text("", 16, false);
        gmail.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(gmail);

        homedepot = text("", 16, false);
        homedepot.setPadding(0, 0, 0, pad / 2);
        root.addView(homedepot);

        guard = text("", 16, false);
        guard.setPadding(0, 0, 0, pad / 2);
        root.addView(guard);

        stats = text("", 14, false);
        stats.setPadding(0, pad / 2, 0, pad);
        root.addView(stats);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button(getString(R.string.button_test), v -> {
            Keeper.postTest(this, handler);
            stats.setText(getString(R.string.test_running));
        }));
        buttons.addView(button(getString(R.string.button_clear), v -> {
            Keeper.clearKept(this);
            refresh();
        }));
        buttons.addView(button(getString(R.string.button_show_saved), v -> new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.saved_title)
                .setMessage(SettingsGuard.describe(this))
                .setPositiveButton(android.R.string.ok, null)
                .show()));
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        // Android 15+ draws app content edge to edge (under the desktop caption bar), so keep the
        // content clear of the bars ourselves.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout()
                    | android.view.WindowInsets.Type.captionBar());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        GuardJob.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void refresh() {
        boolean listener = Keeper.listenerAllowed(this);
        access.setText(getString(listener ? R.string.ok_access : R.string.bad_access));
        fixAccess.setVisibility(listener ? View.GONE : View.VISIBLE);

        boolean canPost = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        notifs.setText(getString(canPost ? R.string.ok_notifs : R.string.bad_notifs));
        fixNotifs.setVisibility(canPost ? View.GONE : View.VISIBLE);

        GmailMirror.Engine go = GmailMirror.engine(this);
        if (go == GmailMirror.Engine.OK) {
            gmail.setText(getString(R.string.gmail_go_ok, GmailMirror.goVersion(this),
                    Keeper.prefs(this).getInt("mirrored_count", 0)) + "\n"
                    + getString(WebGmailHelper.isRunning() ? R.string.helper_on : R.string.helper_off));
        } else if (Keeper.gmailState(this) == Keeper.GmailState.FULL) {
            gmail.setText(getString(R.string.gmail_full, Keeper.gmailVersion(this)));
        } else {
            gmail.setText(getString(go == GmailMirror.Engine.MISSING ? R.string.gmail_go_missing : R.string.gmail_go_blocked));
        }
        int hdShown = Keeper.prefs(this).getInt("hd_count", 0);
        homedepot.setText(hdShown > 0 ? getString(R.string.hd_ok, hdShown) : getString(R.string.hd_none));

        if (listener && canPost) Keeper.runGuard(this);

        android.content.SharedPreferences gp = Keeper.prefs(this);
        String saved = SettingsGuard.summary(this);
        String guardText = gp.getBoolean("guard_privileged", false)
                ? (saved != null ? saved : getString(R.string.guard_nothing_yet))
                : getString(R.string.guard_no_link);
        int restoredCount = gp.getInt("guard_restored", 0);
        if (restoredCount > 0) {
            guardText += "\n" + getString(R.string.guard_restored, restoredCount,
                    DateFormat.format("MMM d, h:mm a", gp.getLong("guard_restored_time", 0)));
        }
        guard.setText(guardText);

        android.content.SharedPreferences p = Keeper.prefs(this);
        long last = p.getLong("last_kept_time", 0);
        String lastText = last == 0 ? getString(R.string.never)
                : DateFormat.format("MMM d, h:mm a", last) + " (" + p.getString("last_kept_source", "") + ")";
        stats.setText(getString(R.string.stats, p.getInt("kept_count", 0), Keeper.keptInTray(this), lastText));
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(TextView t, Button b) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(t);
        r.addView(b);
        return r;
    }
}
