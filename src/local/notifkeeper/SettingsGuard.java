package local.notifkeeper;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Saves the notification settings of Gmail and Gmail Go (every category and account group) and
 * puts them back whenever the app recreates them - e.g. after its app data is cleared - so the
 * user never has to redo them. Each app has its own saved file.
 *
 * Rules:
 * - A category the user has set (Android marks it "user set") is the truth: we save it.
 * - A category Gmail has just created or reset (not user locked) gets the saved settings back.
 *   A brand-new category we've never seen (e.g. a newly added account) gets "Pop on screen",
 *   following this app's rule that every Gmail category that is on should pop up.
 * - Account groups the user switched off are honoured by switching off their categories (Android
 *   lets companion apps change categories, not groups).
 *
 * Needs Notification Keeper to be a companion-device app (otherwise Android refuses the calls and
 * we just log it).
 */
final class SettingsGuard {
    /** The apps whose notification settings we guard. */
    static final String[] PKGS = {Keeper.GMAIL, Keeper.GMAIL_GO};
    private static final Object LOCK = new Object();

    private SettingsGuard() {}

    static boolean guards(String pkg) {
        for (String p : PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    /** Gmail keeps its original file name so settings saved by earlier versions stay in use. */
    private static String file(String pkg) {
        return Keeper.GMAIL.equals(pkg) ? "gmail-notification-settings.json" : pkg + "-notification-settings.json";
    }

    private static String label(String pkg) {
        return Keeper.GMAIL_GO.equals(pkg) ? "Gmail Go" : "Gmail";
    }

    /** Full pass over every guarded app. */
    static void sync(NotificationListenerService s) {
        for (String pkg : PKGS) sync(s, pkg);
    }

    /** Full pass for one app: save user-set categories, restore reset/new ones. */
    static void sync(NotificationListenerService s, String pkg) {
        synchronized (LOCK) {
            List<NotificationChannel> channels;
            List<NotificationChannelGroup> groups;
            UserHandle user = Process.myUserHandle();
            try {
                channels = s.getNotificationChannels(pkg, user);
                groups = s.getNotificationChannelGroups(pkg, user);
            } catch (SecurityException e) {
                Log.w(Keeper.TAG, "settings guard: not a companion app yet, can't read " + label(pkg) + "'s settings");
                Keeper.prefs(s).edit().putBoolean("guard_privileged", false).apply();
                return;
            } catch (RuntimeException e) {
                Log.w(Keeper.TAG, "settings guard: " + label(pkg) + " settings unavailable", e);
                return;
            }
            Keeper.prefs(s).edit().putBoolean("guard_privileged", true).apply();
            if (channels.isEmpty() && groups.isEmpty()) return; // app not installed (or never ran)
            JSONObject snap = load(s, pkg);
            JSONObject savedChannels = snap.optJSONObject("channels");
            JSONObject savedGroups = snap.optJSONObject("groups");
            if (savedChannels == null) savedChannels = new JSONObject();
            if (savedGroups == null) savedGroups = new JSONObject();
            boolean changed = false;

            if (Keeper.GMAIL_GO.equals(pkg) && GmailMirror.engineOk(s)) {
                // Gmail Go's notifications are shown by our copies (GmailMirror), which pop up;
                // Gmail Go's own must stay silent so nothing alerts twice.
                if (silenceOriginals(s, user, channels, savedChannels)) {
                    try {
                        snap.put("channels", savedChannels).put("savedAt", System.currentTimeMillis());
                        save(s, pkg, snap);
                    } catch (JSONException e) {
                        Log.w(Keeper.TAG, "settings guard: save", e);
                    }
                }
                return;
            }

            // Groups: Android lets us read them but not switch them. Remember the ones the user
            // switched off; after a reset (group back on) we switch their categories off instead.
            Map<String, NotificationChannelGroup> current = new HashMap<>();
            for (NotificationChannelGroup g : groups) current.put(g.getId(), g);
            for (NotificationChannelGroup g : groups) {
                try {
                    JSONObject prev = savedGroups.optJSONObject(g.getId());
                    if (g.isBlocked()) {
                        if (prev == null || !prev.optBoolean("blocked")) {
                            savedGroups.put(g.getId(), new JSONObject().put("name", str(g.getName())).put("blocked", true));
                            changed = true;
                        }
                    } else if (prev == null) {
                        savedGroups.put(g.getId(), new JSONObject().put("name", str(g.getName())).put("blocked", false));
                        changed = true;
                    } else if (prev.optBoolean("blocked") && userTurnedOn(g.getId(), channels)) {
                        // Group on again AND the user switched one of its categories on: they
                        // really want this account back (a reset alone never does both).
                        prev.put("blocked", false);
                        changed = true;
                    }
                } catch (JSONException e) {
                    Log.w(Keeper.TAG, "settings guard: group", e);
                }
            }
            Set<String> lostBlocks = new HashSet<>(); // saved as off, but currently on (reset)
            for (java.util.Iterator<String> it = savedGroups.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject g = savedGroups.optJSONObject(id);
                NotificationChannelGroup cur = current.get(id);
                if (g != null && g.optBoolean("blocked") && cur != null && !cur.isBlocked()) lostBlocks.add(id);
            }

            int restored = 0;
            for (NotificationChannel ch : channels) {
                try {
                    JSONObject saved = savedChannels.optJSONObject(ch.getId());
                    if (userSet(ch)) {
                        // Set by the user (or by us earlier, on the user's behalf): this is the truth.
                        JSONObject now = toJson(ch).put("userSet", true);
                        if (saved == null || !saved.toString().equals(now.toString())) {
                            savedChannels.put(ch.getId(), now);
                            changed = true;
                        }
                        continue;
                    }
                    // Created or reset by Gmail: put the user's settings back.
                    NotificationChannel want;
                    if (ch.getGroup() != null && lostBlocks.contains(ch.getGroup())) {
                        if (ch.getImportance() == NotificationManager.IMPORTANCE_NONE) continue;
                        want = copy(ch);
                        want.setImportance(NotificationManager.IMPORTANCE_NONE);
                    } else if (saved != null) {
                        want = fromJson(ch, saved);
                    } else if (ch.getGroup() != null && isBlocked(current.get(ch.getGroup()))) {
                        // Account switched off by the user: leave it alone, just record it.
                        savedChannels.put(ch.getId(), toJson(ch).put("userSet", false));
                        changed = true;
                        continue;
                    } else {
                        want = newCategoryRule(ch);
                        if (want == null) {
                            // Gmail's default already fits the rule: record it as it is.
                            savedChannels.put(ch.getId(), toJson(ch).put("userSet", false));
                            changed = true;
                            continue;
                        }
                    }
                    if (want == null || sameSettings(ch, want)) continue;
                    s.updateNotificationChannel(pkg, user, want);
                    restored++;
                    Log.i(Keeper.TAG, "settings guard: restored " + label(pkg) + " category " + ch.getId());
                } catch (JSONException | RuntimeException e) {
                    Log.w(Keeper.TAG, "settings guard: category " + ch.getId(), e);
                }
            }
            if (changed) {
                try {
                    snap.put("channels", savedChannels).put("groups", savedGroups)
                            .put("savedAt", System.currentTimeMillis());
                    save(s, pkg, snap);
                } catch (JSONException e) {
                    Log.w(Keeper.TAG, "settings guard: save", e);
                }
            }
            if (restored > 0) {
                Keeper.prefs(s).edit()
                        .putInt("guard_restored", Keeper.prefs(s).getInt("guard_restored", 0) + restored)
                        .putLong("guard_restored_time", System.currentTimeMillis()).apply();
            }
        }
    }

    /**
     * Gmail Go while its notifications are mirrored: every category that's on becomes silent
     * ("Low"); a category the user switched off stays off, also after Gmail Go resets it.
     * Returns true if the saved settings changed.
     */
    private static boolean silenceOriginals(NotificationListenerService s, UserHandle user,
            List<NotificationChannel> channels, JSONObject savedChannels) {
        boolean changed = false;
        for (NotificationChannel ch : channels) {
            try {
                JSONObject saved = savedChannels.optJSONObject(ch.getId());
                int imp = ch.getImportance();
                if (imp == NotificationManager.IMPORTANCE_NONE) {
                    if (saved == null || saved.optInt("importance", -1) != NotificationManager.IMPORTANCE_NONE) {
                        savedChannels.put(ch.getId(), toJson(ch).put("userSet", userSet(ch)));
                        changed = true;
                    }
                    continue;
                }
                NotificationChannel want = copy(ch);
                if (saved != null && saved.optInt("importance", -1) == NotificationManager.IMPORTANCE_NONE) {
                    want.setImportance(NotificationManager.IMPORTANCE_NONE); // switched off by the user
                } else if (imp != NotificationManager.IMPORTANCE_LOW) {
                    want.setImportance(NotificationManager.IMPORTANCE_LOW);
                } else {
                    if (saved == null || saved.optInt("importance", -1) != imp) {
                        savedChannels.put(ch.getId(), toJson(ch).put("userSet", userSet(ch)));
                        changed = true;
                    }
                    continue;
                }
                s.updateNotificationChannel(Keeper.GMAIL_GO, user, want);
                Log.i(Keeper.TAG, "settings guard: Gmail Go category " + ch.getId() + " -> "
                        + (want.getImportance() == NotificationManager.IMPORTANCE_NONE ? "off" : "silent (Keeper shows it)"));
            } catch (JSONException | RuntimeException e) {
                Log.w(Keeper.TAG, "settings guard: Gmail Go category " + ch.getId(), e);
            }
        }
        return changed;
    }

    /** Android marks a category the user has changed (importance or sound) as "user set". */
    private static boolean userSet(NotificationChannel ch) {
        return ch.hasUserSetImportance() || ch.hasUserSetSound();
    }

    private static boolean isBlocked(NotificationChannelGroup g) {
        return g != null && g.isBlocked();
    }

    /** True if one of the group's categories was switched on by the user. */
    private static boolean userTurnedOn(String groupId, List<NotificationChannel> channels) {
        for (NotificationChannel ch : channels) {
            if (groupId.equals(ch.getGroup()) && userSet(ch)
                    && ch.getImportance() != NotificationManager.IMPORTANCE_NONE) {
                return true;
            }
        }
        return false;
    }

    /** New category never seen before: pop on screen if it's on (this app's rule for Gmail). */
    private static NotificationChannel newCategoryRule(NotificationChannel ch) {
        if (ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return null; // switched off
        if (ch.getImportance() >= NotificationManager.IMPORTANCE_HIGH) return null; // already pops up
        NotificationChannel want = copy(ch);
        want.setImportance(NotificationManager.IMPORTANCE_HIGH);
        return want;
    }

    static JSONObject toJson(NotificationChannel ch) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", str(ch.getName()));
        o.put("group", ch.getGroup() == null ? JSONObject.NULL : ch.getGroup());
        o.put("importance", ch.getImportance());
        o.put("sound", ch.getSound() == null ? JSONObject.NULL : ch.getSound().toString());
        o.put("vibration", ch.shouldVibrate());
        o.put("lights", ch.shouldShowLights());
        o.put("lightColor", ch.getLightColor());
        o.put("lockscreen", ch.getLockscreenVisibility());
        o.put("badge", ch.canShowBadge());
        o.put("bypassDnd", ch.canBypassDnd());
        o.put("bubbles", ch.canBubble());
        return o;
    }

    private static NotificationChannel fromJson(NotificationChannel current, JSONObject o) throws JSONException {
        NotificationChannel want = copy(current);
        want.setImportance(o.getInt("importance"));
        Object sound = o.opt("sound");
        want.setSound(sound == null || sound == JSONObject.NULL ? null : Uri.parse((String) sound),
                current.getAudioAttributes());
        want.enableVibration(o.optBoolean("vibration", current.shouldVibrate()));
        want.enableLights(o.optBoolean("lights", current.shouldShowLights()));
        want.setLightColor(o.optInt("lightColor", current.getLightColor()));
        want.setLockscreenVisibility(o.optInt("lockscreen", current.getLockscreenVisibility()));
        want.setShowBadge(o.optBoolean("badge", current.canShowBadge()));
        want.setBypassDnd(o.optBoolean("bypassDnd", current.canBypassDnd()));
        want.setAllowBubbles(o.optBoolean("bubbles", current.canBubble()));
        return want;
    }

    private static NotificationChannel copy(NotificationChannel ch) {
        NotificationChannel c = new NotificationChannel(ch.getId(), ch.getName(), ch.getImportance());
        c.setDescription(ch.getDescription());
        c.setGroup(ch.getGroup());
        c.setSound(ch.getSound(), ch.getAudioAttributes());
        c.enableVibration(ch.shouldVibrate());
        c.setVibrationPattern(ch.getVibrationPattern());
        c.enableLights(ch.shouldShowLights());
        c.setLightColor(ch.getLightColor());
        c.setLockscreenVisibility(ch.getLockscreenVisibility());
        c.setShowBadge(ch.canShowBadge());
        c.setBypassDnd(ch.canBypassDnd());
        c.setAllowBubbles(ch.canBubble());
        return c;
    }

    private static boolean sameSettings(NotificationChannel a, NotificationChannel b) {
        try {
            return toJson(a).toString().equals(toJson(b).toString());
        } catch (JSONException e) {
            return false;
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString();
    }

    // ---- storage ----

    static JSONObject load(Context c, String pkg) {
        File f = new File(c.getFilesDir(), file(pkg));
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = in.readAllBytes();
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(Keeper.TAG, "settings guard: unreadable saved settings", e);
            return new JSONObject();
        }
    }

    private static void save(Context c, String pkg, JSONObject o) {
        // Write to a new file, then move it over the old one, so a crash can't leave half a file.
        File tmp = new File(c.getFilesDir(), file(pkg) + ".new");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(Keeper.TAG, "settings guard: could not save", e);
            return;
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), file(pkg)))) {
            Log.w(Keeper.TAG, "settings guard: could not replace saved settings");
        }
    }

    /** "12 categories, 2 account groups (saved Jan 5, 9:30 AM)" for the status window. */
    static String summary(Context c) {
        JSONObject o = load(c, Keeper.GMAIL);
        JSONObject ch = o.optJSONObject("channels");
        JSONObject gr = o.optJSONObject("groups");
        if (ch == null || ch.length() == 0) return null;
        long at = o.optLong("savedAt", 0);
        return c.getString(R.string.guard_saved, ch.length(), gr == null ? 0 : gr.length(),
                android.text.format.DateFormat.format("MMM d, h:mm a", at));
    }

    /** The saved settings as text (for the status window's "Show saved settings"). */
    static String describe(Context c) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : PKGS) {
            JSONObject o = load(c, pkg);
            if (o.optJSONObject("channels") == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("== ").append(label(pkg)).append(" ==\n");
            describe(o, sb);
        }
        return sb.toString();
    }

    private static void describe(JSONObject o, StringBuilder sb) {
        JSONObject ch = o.optJSONObject("channels");
        JSONObject gr = o.optJSONObject("groups");
        if (gr != null) {
            for (java.util.Iterator<String> it = gr.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject g = gr.optJSONObject(id);
                if (g != null) sb.append(g.optString("name")).append(g.optBoolean("blocked") ? ": OFF\n" : ": on\n");
            }
        }
        if (ch != null) {
            JSONArray names = ch.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONObject x = ch.optJSONObject(names.optString(i));
                if (x == null) continue;
                int imp = x.optInt("importance");
                String level = imp == 0 ? "off" : imp >= 4 ? "pop on screen" : imp == 3 ? "default" : "silent";
                sb.append("• ").append(x.optString("name")).append(" (").append(names.optString(i)).append("): ")
                        .append(level).append('\n');
            }
        }
    }
}
