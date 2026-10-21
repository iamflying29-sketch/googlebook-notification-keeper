package local.notifkeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Keeper's read sense: a small single-purpose learner that notices when a Gmail Go email was
 * read on any device, with Gmail Go's own notification never shown.
 *
 * Gmail Go's own notification is hidden by a long snooze (GmailMirror.hideGo), and Gmail Go still
 * cancels it when the mail is read anywhere; Android then only flags the hidden record, so nothing
 * tells us directly. Two things together do: Gmail Go's unread counts (its public content provider,
 * see GoCounts) fall the moment it syncs a read, and a one-second re-snooze of the hidden records
 * (GmailMirror.probe) brings back the unread ones while a read one never returns. A count drop
 * triggers the probe. Gmail Go's provider announces changes, so most reads are caught at once; these
 * ticks are the safety net. To be quick without being busy, they come every few
 * seconds inside a learned "hot" window after a new mail (how long after arrival your mails usually
 * get read) and rarely after it, and stop when nothing is waiting. Each confirmed read teaches it.
 *
 * The lookups are one small query of Gmail Go's provider per account; nothing is dumped and no system
 * service is ever left waiting on us (see the never-block rule).
 */
final class ReadSense {
    private static final long FAST_MS = 2500;             // inside the hot window
    private static final long SLOW_MS = 15_000;           // after it
    private static final long DEFAULT_HOT_MS = 10 * 60_000;
    private static final long MIN_HOT_MS = 60_000;
    private static final long MAX_HOT_MS = 3 * 60 * 60_000;
    private static final int HISTORY = 40;                // read latencies remembered
    private static final String PREF_HISTORY = "readsense_latencies";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean scheduled;
    private static long lastNewMail;

    private ReadSense() {}

    /** A new mail was mirrored: the hot window starts again. */
    static void noteNewMail(Context c) {
        lastNewMail = System.currentTimeMillis();
    }

    /** Make sure the watcher runs while something is hidden. */
    static void watch(NotificationListenerService s) {
        if (scheduled) return;
        schedule(s, nextDelay(s));
    }

    /**
     * A new listener instance has connected: cancel any ticks scheduled against a previous instance
     * and clear the flag, so the next watch() re-arms against the new service. Without this, the old
     * loop keeps ticking against a dead service and the new watch() is a no-op.
     */
    static void reset() {
        main.removeCallbacksAndMessages(null);
        scheduled = false;
    }

    private static void schedule(NotificationListenerService s, long delay) {
        scheduled = true;
        main.postDelayed(() -> tick(s), delay);
    }

    /** Gmail Go's counts changed (content observer): look now rather than at the next tick. */
    static void poke(NotificationListenerService s) {
        main.removeCallbacksAndMessages(null);
        scheduled = false;
        schedule(s, 0);
    }

    private static void tick(NotificationListenerService s) {
        scheduled = false;
        if (!GmailMirror.watching()) return; // nothing waiting: sleep until the next mail
        List<Long> cleared = new ArrayList<>();
        try {
            for (String account : GmailMirror.watchedAccounts()) {
                Map<String, Integer> drops = GoCounts.dropped(s, account);
                if (drops != null && !drops.isEmpty()) {
                    Log.i(Keeper.TAG, "read sense: counts dropped " + drops + " for " + account + ": probing which mail it was");
                    GmailMirror.probe(s, account);
                }
            }
        } catch (RuntimeException e) {
            Log.w(Keeper.TAG, "read sense: check failed", e);
        }
        if (!cleared.isEmpty()) learn(s, cleared);
        if (GmailMirror.watching()) schedule(s, nextDelay(s));
    }

    /** Fast while a read is likely (the learned hot window after the last new mail), slow after. */
    private static long nextDelay(Context c) {
        long since = System.currentTimeMillis() - lastNewMail;
        return since < hotWindow(c) ? FAST_MS : SLOW_MS;
    }

    /** How long after a mail arrives most of them get read: 1.5 times the 80th percentile so far. */
    static long hotWindow(Context c) {
        List<Long> h = history(c);
        if (h.size() < 3) return DEFAULT_HOT_MS;
        Collections.sort(h);
        long p80 = h.get(Math.min(h.size() - 1, (int) Math.floor(h.size() * 0.8)));
        return Math.max(MIN_HOT_MS, Math.min(MAX_HOT_MS, p80 * 3 / 2));
    }

    /** The probe found reads: learn from their latencies. */
    static void learned(Context c, List<Long> latencies) {
        if (!latencies.isEmpty()) learn(c, latencies);
    }

    private static void learn(Context c, List<Long> latencies) {
        List<Long> h = history(c);
        for (Long l : latencies) {
            if (l != null && l > 0 && l < 7L * 24 * 3600_000) h.add(l);
        }
        while (h.size() > HISTORY) h.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Long l : h) sb.append(l).append(',');
        SharedPreferences p = Keeper.prefs(c);
        p.edit().putString(PREF_HISTORY, sb.toString())
                .putInt("readsense_reads", p.getInt("readsense_reads", 0) + latencies.size())
                .putLong("readsense_last_read", System.currentTimeMillis()).apply();
        Log.i(Keeper.TAG, "read sense: learned from " + latencies.size() + " read(s); hot window now "
                + (hotWindow(c) / 1000) + " s from " + h.size() + " samples");
    }

    private static List<Long> history(Context c) {
        List<Long> out = new ArrayList<>();
        String s = Keeper.prefs(c).getString(PREF_HISTORY, "");
        for (String part : s.split(",")) {
            if (part.isEmpty()) continue;
            try {
                out.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return out;
    }
}
