package local.notifkeeper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Tapping a kept notification: open the web app (or the site in Chrome) it came from. Tapping a
 * Gmail notification (or its Reply / Delete / Mark as read button): open the web Gmail, where the
 * Gmail helper finishes the job on that email.
 */
public class OpenActivity extends Activity {
    static final String EXTRA_PACKAGE = "pkg";
    static final String EXTRA_ORIGIN = "origin";
    static final String EXTRA_URL = "url";
    static final String EXTRA_CANCEL_TAG = "cancel";
    static final String EXTRA_SUBJECT = "subject";
    static final String EXTRA_SENDER = "sender";
    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_ACCOUNT = "account";
    static final String EXTRA_WHEN = "when";
    static final String EXTRA_KIND = "kind";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        String origin = getIntent().getStringExtra(EXTRA_ORIGIN);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String kind = getIntent().getStringExtra(EXTRA_KIND);
        try {
            if (url != null && kind != null) {
                // An email: the web helper does the rest in the web Gmail once the page is up, and
                // removes the notification when it's done (it stays if the helper is off or fails).
                if (WebGmailHelper.isRunning()) {
                    // The helper opens the web Gmail itself, and only when none is open, so a button
                    // never adds a second window when one is already open (Gmail's app opens a brand
                    // new window on every launch, so reusing has to mean not launching at all).
                    WebGmailHelper.start(WebGmailHelper.Kind.valueOf(kind), getIntent().getCharSequenceExtra(EXTRA_SUBJECT),
                            getIntent().getCharSequenceExtra(EXTRA_SENDER), getIntent().getStringExtra(EXTRA_ADDRESS),
                            getIntent().getStringExtra(EXTRA_ACCOUNT),
                            getIntent().getLongExtra(EXTRA_WHEN, 0), url, getIntent().getStringExtra(EXTRA_CANCEL_TAG));
                } else {
                    Log.w(Keeper.TAG, "gmail helper is off: opening the web Gmail so you can act");
                    GmailMirror.openWebGmail(this, url);
                }
                finish();
                return;
            }
            GmailMirror.removeCopy(this, getIntent().getStringExtra(EXTRA_CANCEL_TAG));
            if (url != null) {
                // A summary header or a non-mail Gmail copy: just open the Inbox. Reuse an open web
                // Gmail window if there is one, so this never adds a second window; only open one when
                // the helper is off or no window is found.
                if (WebGmailHelper.isRunning()) {
                    WebGmailHelper.openInbox(url);
                } else {
                    GmailMirror.openWebGmail(this, url);
                }
            } else if (Keeper.TEST_ORIGIN.equals(origin)) {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (isBrowser(pkg) && origin != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(origin + "/"))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Intent launch = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (origin != null) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(origin + "/"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(Keeper.TAG, "could not open " + pkg, e);
        }
        finish();
    }

    private static boolean isBrowser(String pkg) {
        return pkg != null && (pkg.equals("com.android.chrome") || pkg.startsWith("com.chrome."));
    }
}
