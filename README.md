# Notification Keeper

Notification Keeper is an open-source Android app that keeps web app notifications in the
GooglebookOS Notification Center. Many websites and Chrome web apps withdraw their own notifications
a few seconds after posting them, so an alert that arrives while you are away from the screen is
simply gone. Notification Keeper watches for that moment and puts a quiet copy back that stays until
you tap it or clear it. I designed it for the Gmail web app on my Lenovo Googlebook 15, and for Gmail
it goes much further, but its goal is to preserve notifications for every Chrome web app on
GooglebookOS. The generic keeping already works for any web app. The richer per-app features are
written only for Gmail, and other developers will need to edit the code to bring them to other web
apps. This README explains the generic keeper in full, explains the Gmail features, and shows exactly
where in the code a developer would start. It also has a separate feature that shows Home Depot order
updates as their own notifications, because the Home Depot app's own notifications never reach my
Googlebook, and that feature has its own section below. The current version is 1.2.

## What it does

Notification Keeper is a notification listener. When a Chrome web app or website withdraws one of
its own notifications a few seconds after posting it, the Keeper posts a quiet copy that stays in the
Notification Center until I tap it or clear it. The copy shows the same title and text, the site's
picture if it had one, and the name of the web app or website it came from. Tapping the copy opens
that web app, or the website in Chrome. Notifications that I swipe away or tap myself are left alone.

The Keeper has no internet permission, holds no wake locks and stores no message content in its
files. It does no polling while idle; with the Gmail features, it checks Gmail Go's unread counts
every few seconds only while a Gmail copy is waiting to be read. A small status window shows whether everything is set up and has a "Run test"
button that proves the keeping works.

It was built first for the Gmail web app, and for Gmail it does more. It turns each email alert into
a rich notification with Reply, Delete and Mark as read buttons, it guards Gmail's notification
settings, and it contains a Gmail helper that carries out those buttons in the Gmail web app. Those
Gmail features are part of a larger setup that is documented in its own repository,
[googlebook-gmail-web-app-notifications](https://github.com/iamflying29-sketch/googlebook-gmail-web-app-notifications).
You can use Notification Keeper without any of that.

It also shows Home Depot order updates as their own notifications. The Home Depot app's own
notifications never reach my Googlebook, so its order updates only arrive by email. When an order
update email from Home Depot arrives in any email app on the Googlebook, the Keeper shows it as a
Home Depot order notification in the brand's colors that opens the Home Depot app when I tap it, in
addition to the email's own notification, which still arrives as usual. This feature has its own
section below, and it is separate from the web app keeping and from the Gmail features.

## The problem

On GooglebookOS, web apps and websites deliver their notifications through Chrome. A web page or its
service worker can close one of its own notifications at any time, and Chrome passes that on to
Android, which removes the notification from the Notification Center. Android records this with
removal reason 8, which it uses when the app that posted a notification cancels it itself.

Some web apps do this with every alert. The Gmail web app is the clearest case: in my logs, every
Gmail alert was removed by Gmail's own web code about 7 seconds after it appeared, always with
reason 8. The alert pops up, disappears, and leaves nothing behind. If I was not looking at the
screen during those seconds, I never knew it had arrived.

No GooglebookOS setting causes this. I checked and ruled out Do Not Disturb, snoozing, notification
timeouts, notification assistants and other listeners, and the notification rate limit. The site's
own code withdraws the alert, so no setting can keep it. The only way to keep it is to notice the
withdrawal and post a copy.

## How I solved it

### The generic keeper (works for any web app)

1. **A notification listener.** The app's `KeeperListener` service is a standard Android
   notification listener. Android binds it and keeps it running for as long as the app has
   notification access, so the Keeper needs no polling, no wake locks and no foreground service.
2. **Recognizing web notifications.** Chrome marks every web notification it delivers. The tag
   starts with `p#` followed by the site's origin for notifications from a service worker, or `n#`
   followed by the origin for notifications from an open page, and some carry a group named
   `Web:` followed by the site's host. The Keeper reads the origin from either marker. Chrome keeps
   these markers when it posts the notification under an installed web app's own package, such as
   the Gmail web app, so the same check covers websites in Chrome and installed web apps.
3. **Watching for the withdrawal.** When a notification is removed, Android tells the listener why.
   The Keeper acts only on reason 8 (the app cancelled it) and reason 9 (the app cancelled all of its
   notifications). It ignores everything you do yourself, such as swiping a notification away,
   tapping it or pressing Clear all, and everything the system removes. It also ignores ongoing
   notifications, foreground service notifications and its own notifications (except the Run test
   notification). Each original is copied at most once.
4. **Posting the copy.** The copy goes on the Keeper's own "Kept web notifications" category, which
   is silent and does not vibrate, because the original already alerted you. It carries the
   original's title, text and expanded text, its large picture (when that picture is a bitmap), its
   accent color and its category, plus the original time. The small icon is the Keeper's own bell,
   and the notification's header line names the source, for example "Chrome · example.com" for a
   website or the web app's own name for an installed web app. The copy does not carry the original's
   buttons, because those belonged to the web page, which may no longer exist.
5. **Tapping the copy.** Tapping removes the copy and opens the source. For a website in Chrome, it
   opens the site's root address in Chrome. For an installed web app, it opens the web app through
   its launcher entry. If neither is possible, it opens the site's root address in your default
   browser.
6. **Staying within Android's limits.** Android allows each app at most 50 notifications at a time.
   The Keeper keeps at most 15 kept copies and removes the oldest copy when a sixteenth arrives. The
   Gmail features use up to 25 more, plus their summaries and the Keeper's own alerts.
7. **Checking itself.**
   * The status window has a **Run test** button. It posts a fake website notification, tagged
     exactly the way Chrome tags a real one, and withdraws it after 7 seconds, the way the Gmail web
     app does. If the Keeper works, a kept copy appears right after.
   * A guard job runs every 6 hours. It checks that the Keeper still has notification access, because
     a listener that loses its access simply stops running and cannot notice that by itself. If
     access is gone, the Keeper posts a "Notification Keeper is switched off" alert.
   * A boot receiver runs the same check after a restart and after the app is updated, and makes sure
     the guard job stays scheduled.
   * If Android ever disconnects the listener, the Keeper asks Android to bind it again.

### The Gmail features (set up separately)

On my Googlebook, Gmail is a web app. Keeping its withdrawn alerts was the first step, but the Gmail
web app cannot notify at all once its window is closed, and keeping copies cannot fix that. The full
solution uses Google's Gmail Go app as the engine that receives Google's push messages, and
Notification Keeper does the rest. The whole setup, including how to install Gmail Go and the small
Linux Terminal service that handles Mark as read, is documented in
[googlebook-gmail-web-app-notifications](https://github.com/iamflying29-sketch/googlebook-gmail-web-app-notifications).
Inside this app, the Gmail features are:

* **Gmail mirror** (`GmailMirror.java`). Every Gmail Go notification is shown as a Keeper copy that
  pops up and stays, grouped by account under its own categories, with the Keeper's own "N new
  messages" summary. Email notifications get three buttons: Reply, Delete and Mark as read. Tapping
  opens that account's inbox in the Gmail web app, and with the Gmail helper on, it opens the email
  itself. Gmail Go's original is hidden only after the copy is posted, and it is hidden by a 12-hour
  snooze rather than cancelled: a cancel would tell Gmail Go that I dismissed the notification, and
  Gmail Go would stop tracking that email. Snoozed, it stays out of sight while Gmail Go keeps
  managing it, which is what makes the read detection below possible. If a hidden notification ever
  comes back, the Keeper hides it again at once. When the Gmail web app is open and posts its own
  new-mail alert as well, the Keeper removes that duplicate, but only once a Gmail Go copy for that
  account is actually showing, and it leaves the web alert alone if no copy appears within 2.5
  seconds. The Keeper shows at most 25 Gmail copies.
* **Read detection** (`GoCounts.java`, `ReadSense.java` and `GmailMirror.probe()`). When I read an
  email on another device, its copy clears by itself. Gmail Go does not announce reads, so the Keeper
  combines two signals. Gmail Go's public content provider, the one launchers use for badge numbers,
  gives the unread count of every label, and the Keeper watches it; a drop means some mail was read.
  To learn which one, the Keeper re-snoozes the account's hidden Gmail Go notifications for about one
  second. Android brings back the ones Gmail Go still considers unread, and the Keeper hides them
  again before they can be seen, while a notification that Gmail Go cancelled because its email was
  read is dropped and never comes back. Whatever did not come back was read, and that copy is
  cleared. The read check only ever hides Gmail Go's test notifications again, so it can never turn
  one into a notification you see. The check runs every 2.5 seconds inside a window after a new mail,
  every 15 seconds after it, and not at all when no Gmail copy is waiting; the length of that window
  is learned from how
  long after arrival my mails usually get read. In my tests, reading one of two unread emails on a
  tablet cleared exactly that one copy, between 20 seconds and a few minutes after the read,
  depending on when Gmail Go synced.
* **Gmail helper** (`WebGmailHelper.java`). An accessibility service, off by default, that carries
  out a tap, Reply or Delete in the Gmail web app window. It finds the email by its subject and
  arrival time, and it only touches the screen when the Gmail web app is the active window and
  nothing covers the spot. When it needs the Gmail web app it reuses a window that is already open
  instead of opening a second one. Android switches an accessibility service off whenever its app is
  updated or reinstalled, so if you grant the optional permission in
  [step 10](#10-optional-gmail-setup-only-let-the-gmail-helper-stay-on-after-updates) the Keeper
  turns this helper back on by itself after every update.
* **Mark as read** (`GhostReceiver.java`). The Mark as read button writes a one-line command to the
  system log, where the Linux Terminal service from the Gmail project picks it up and marks the email
  read in a hidden browser. It also saves the same command as a small file in
  `Documents/Notification Keeper/cmd/`, so the service still finds it after an outage. The Linux
  Terminal stops its Linux system whenever its last window closes, and the service stops with it. So
  when you tap Mark as read and the Terminal's Linux system is not running, the Keeper opens the
  Terminal the same way its app icon does. The Linux system starts, the service picks up the saved
  command, and the email is marked read a few seconds later. The Keeper can tell whether the Linux
  system is running because the Terminal shows its ongoing notification only while it is. The
  Terminal window stays open afterwards, because closing it would stop the Linux system again.
* **Gmail Go's other buttons** (`ActionReceiver.java`). Buttons on Gmail Go's other notifications that
  work in the background run Gmail Go's own action.
* **Settings guard** (`SettingsGuard.java`). With a companion link (explained in
  [Installation](#9-optional-gmail-setup-only-create-the-companion-link)), the Keeper saves the
  notification categories of Gmail and Gmail Go and puts them back if they are ever reset, for
  example after an app's data is cleared. New Gmail categories that are on are set to pop on screen.
  While the Keeper mirrors Gmail Go, it keeps Gmail Go's own categories at the quietest level, so
  Gmail Go's own notification never shows, not even for a split second, and only the Keeper's copy
  alerts.
* **Gmail checks.** The status window shows whether Gmail Go is working, and the Keeper posts a
  "Gmail notifications stopped working" alert if Gmail Go is installed but cannot post
  notifications and no full Gmail app receives push messages either. If Gmail Go is not installed,
  the Gmail setup is not in use, and the Keeper does not post this alert. A package receiver notices right away when Gmail or Gmail Go is installed, updated,
  changed or removed.
* **Test trigger** (`DebugReceiver.java`). A receiver that only adb can call, for testing the Gmail
  helper. Its usage is described at the top of the file. It also has adb-only probes for the read
  detection: logging Gmail Go's unread counts, listing the hidden Gmail Go notifications, and running
  the one-second probe by hand.

The mirror and its buttons only work when Gmail Go is installed and allowed to post notifications,
the settings guard only works with the companion link, and the Gmail helper only works if you turn it
on yourself. The generic keeper works without any of them. The Gmail checks are the exception: they
run for everyone, as described in [Limitations and known issues](#limitations-and-known-issues).

## Home Depot order notifications

This is a separate feature from the web app keeping above, and it does not involve Chrome at all. It
shows Home Depot order updates on the Googlebook as their own notifications, in Home Depot's colors,
that open the Home Depot app when I tap them.

### Why it exists

The Home Depot Android app registers for push notifications in the normal way, but on my Googlebook
it never receives a single one. I watched Google's push service on the laptop across two sessions and
several clean reinstalls of the app. Every other app's push messages arrived, including ones that
were queued while the screen was off, but not one ever arrived for the Home Depot app. Nothing on the
laptop blocks it, so there is simply nothing from the Home Depot app itself for a notification
listener to catch.

What does arrive reliably is Home Depot's order update emails, such as order confirmed, shipped, out
for delivery, delivered, and ready for pickup. Home Depot's own help pages name email and text
messages as the ways they send order updates, and my laptop has no SIM card for text messages, so
email is the signal that is always there. This feature turns those order emails into Home Depot
notifications.

### How it works

* **It is purely additive.** The email's own notification still arrives exactly as before. The Home
  Depot card is shown in addition to it, and the feature never hides, cancels, snoozes or changes the
  email's notification. For every notification that is not a Home Depot order email, it does nothing
  at all.
* **It works with any email app**, not only the Gmail setup described above. It treats a notification
  as mail when it comes from Gmail Go on one of its per-account mail categories, from one of a list of
  common email apps (the full Gmail app, Microsoft Outlook, Outlook Lite, Yahoo Mail, Samsung Email,
  the stock Email app, Proton Mail, Aqua Mail, FairEmail, K-9 Mail, BlueMail and Zoho Mail), or from
  any other app that marks its notification as an email. The list does not have to be complete,
  because the last rule catches the rest. I tested it with Gmail Go and with Outlook.
* **It recognizes Home Depot as the sender** in one of two ways. When the email app exposes the
  sender's address, the card is shown only if that address is at `homedepot.com` or one of its
  subdomains, which only Home Depot can send from. When the app gives only a display name, as Outlook
  does, the card is shown if the name says Home Depot. The test is deliberately conservative, so that
  the worst a wrong guess can do is show an ordinary email as a Home Depot card, while a real order
  update is never missed. Look-alike addresses such as `homedepot.com.example.com` and
  `fakehomedepot.com` are correctly rejected.
* **The card is built from my own layout**, filled with Home Depot's orange, with white text and the
  Home Depot logo on a white tile. It shows the order status as its headline and a short line that
  says to tap to open the Home Depot app. It does not show the email address or the account, and it is
  not marked as an email, because it is an order update rather than a piece of mail. Android colors the
  whole background of an ordinary notification only for a few special kinds, such as media and
  foreground service notifications, so a custom layout is the only way to show this card in Home
  Depot's colors.
* **Tapping the card opens the Home Depot app** through the app's own launcher entry, the same way the
  launcher opens it, and clears the card. My
  [Home Depot phone mode fix](https://github.com/iamflying29-sketch/googlebook-projects/tree/main/smaller-fixes/homedepot-phone-mode)
  keeps the app in a phone-sized window once it opens. If the Home Depot app is not installed, the
  card opens homedepot.com instead.
* **A card you swipe away stays away.** If I swipe a Home Depot card off the screen, the Keeper
  remembers it and does not put that card back if the email's notification reappears later, for
  example when an email app reconnects. The choice is kept if the Keeper restarts, and the email's own
  notification is never affected.
* **Old emails are handled quietly.** When an order email is already on the screen and the listener
  reconnects, the Keeper adds its card without popping it up again, and it treats an email that is
  more than ten minutes old the same way, so a reconnecting email app never makes a stale order pop up.

### The logo

The logo on the card is the Home Depot app's own icon. The Keeper reads it from the installed Home
Depot app at the moment it builds the card, which is the same icon the launcher shows, so the genuine
logo appears and this project includes no image of its own. If the Home Depot app is not installed,
the card falls back to a plain orange tile with a simple drawing of a shipping box, which is my own
generic drawing.

### What I verified, and what I did not

I verified each part of this on my Googlebook. The listener receives the email notifications, the
sender is read correctly, the recognition test gives the right answer for Home Depot senders and the
right answer for ordinary senders and for look-alike addresses, and the card renders in Home Depot's
colors, pops up, stays until I deal with it, and opens the app when tapped. Two adb test triggers made
this possible without a real email: one runs the exact recognition test the live code uses, with plain
values, so any email app can be checked, and the other posts a finished card directly. Their usage is
written at the top of `DebugReceiver.java`.

What I have not yet seen is the whole chain with a real Home Depot order email, from the email
arriving to the card appearing, because a genuine email-app notification cannot be forced and no new
order email arrived while I was building this. Every link in the chain is proven on its own, so the
feature is ready for the next real order email.

## Requirements

* **Device:** a Lenovo Googlebook 15 running GooglebookOS, the desktop build of Android 17. I tested
  it in October 2026 on the GooglebookOS release that was current then. The app needs Android 13 or
  newer (minimum SDK 33, target SDK 35). It may work on other Android devices that use Chrome, but I
  have only tested it on my Googlebook.
* **Chrome** must be installed, because it delivers the web notifications.
* **Permissions that you grant:**
  * Notification access is required, so that Android binds the listener.
  * Permission to post notifications is required.
  * A companion link is optional and only needed for the Gmail settings guard.
  * Accessibility access for the Gmail helper is optional and only needed for the Gmail setup.
* **Permissions in the manifest:** `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` and Gmail's own
  `com.google.android.gm.permission.READ_CONTENT_PROVIDER` for the unread counts (see
  [Safety and privacy notes](#safety-and-privacy-notes)). There is no `INTERNET` permission.
* **Tools:** the Linux Terminal with adb connected to the Googlebook over Wireless debugging, and,
  to build the APK, a JDK, Python 3, and the Android SDK build tools 35.0.0 with the android-35
  platform. This repository contains source code only, so you build the APK yourself.

## Installation

### 1. Prepare the Googlebook and the Linux Terminal

Follow the shared
[Getting Started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md).
It covers turning on Developer options, setting up the Linux Terminal, installing adb, pairing and
connecting to Wireless debugging, finding your Android user ID, and installing a JDK and the Android
SDK build tools. In the commands below, replace `<USER_ID>` with the user ID you found there
(`adb shell am get-current-user`).

### 2. Get the source

```bash
git clone https://github.com/iamflying29-sketch/googlebook-notification-keeper.git ~/googlebook-notification-keeper
cd ~/googlebook-notification-keeper
```

### 3. Build the APK

```bash
KS_PASS='choose-a-long-password' ./build.sh
```

The script compiles the resources with aapt2, the Java code with javac and d8, aligns the APK with
zipalign and signs it with apksigner. It expects the Android SDK in `~/tools/android-sdk`. If yours
is somewhere else, set `ANDROID_SDK`, for example `ANDROID_SDK=/path/to/sdk KS_PASS='...' ./build.sh`.

The first build creates `keystore.jks`, the key that signs the app, protected by the password in
`KS_PASS`. Keep that file and that password. Every later build must use the same `keystore.jks` and
the same `KS_PASS`, or Android will refuse to install the new version over the old one. If you leave
`KS_PASS` out, the script uses the placeholder password `change-me`, which is fine for a quick test
but not for a key you keep.

When it finishes, the script prints the path of `NotificationKeeper.apk` with its version name (1.2)
and version code. The version code is the build time in minutes, so every new build counts as an
upgrade. Each build gets its own folder under `build/`, and the script never deletes anything, so you
can remove old build folders yourself whenever you like.

### 4. Install it

```bash
adb install -r --user <USER_ID> NotificationKeeper.apk
```

### 5. Allow it to post notifications

```bash
adb shell pm grant --user <USER_ID> local.notifkeeper android.permission.POST_NOTIFICATIONS
```

The app also asks for this permission the first time you open it.

### 6. Turn on notification access

```bash
adb shell cmd notification allow_listener local.notifkeeper/local.notifkeeper.KeeperListener <USER_ID>
```

You can check that it worked. The output of the following command should include
`local.notifkeeper/local.notifkeeper.KeeperListener`:

```bash
adb shell settings get --user <USER_ID> secure enabled_notification_listeners
```

Instead of the adb command, you can also open Notification Keeper and press the **Fix** button next to
"Notification access is off", which opens the right Settings page.

### 7. Optional: stop Android from removing its permissions

You will rarely open the Keeper's window, and Android can remove permissions from apps that look
unused. This command tells Android not to do that for the Keeper:

```bash
adb shell cmd appops set --user <USER_ID> local.notifkeeper AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
```

### 8. Check that it works

Open **Notification Keeper** from the launcher. Below the description, the first two status lines
should read "✓ Notification access is on" and "✓ Allowed to show notifications". Press **Run test**. A notification titled "Notification
Keeper test" pops up, disappears after 7 seconds, and a kept copy from "Test website" appears right
after and stays. Tapping that copy opens the status window again.

If you do not use the Gmail setup, two things are expected and harmless:

* The Gmail line says that Gmail Go isn't installed.
* The settings guard line says that Notification Keeper isn't linked as a companion app.

You can close the window at any time. The Keeper keeps working in the background.

### 9. Optional, Gmail setup only: create the companion link

Skip this step unless you are setting up the Gmail features from
[googlebook-gmail-web-app-notifications](https://github.com/iamflying29-sketch/googlebook-gmail-web-app-notifications).

Android lets a notification listener read and change another app's notification categories only if
the listener's app has a companion device association. Normally an app gets one by pairing with a
watch or another device through a system dialog. The adb shell can create one directly. The Keeper
never talks to that device and never reads its address. The address is only a label for the
association, and it does not have to belong to a real device. I used a made-up address. Use any
made-up address written as six pairs of hexadecimal digits separated by colons, for example
`AA:BB:CC:DD:EE:FF`, and write it down, because you need the same address to remove the link later.

```bash
adb shell cmd companiondevice associate <USER_ID> local.notifkeeper AA:BB:CC:DD:EE:FF
```

The address is a plain third argument after the package name. The command's built-in help may
describe the arguments differently, but on my Googlebook this positional form is what worked.

Check the link. The output should contain `mPackageName='local.notifkeeper'`:

```bash
adb shell cmd companiondevice list <USER_ID>
```

Then reconnect the listener, so the Keeper notices its new rights right away:

```bash
adb shell cmd notification disallow_listener local.notifkeeper/local.notifkeeper.KeeperListener <USER_ID>
adb shell cmd notification allow_listener local.notifkeeper/local.notifkeeper.KeeperListener <USER_ID>
```

**What the link changes.** From now on, the settings guard manages the notification categories of
Gmail (`com.google.android.gm`) and Gmail Go (`com.google.android.gm.lite`). Categories you set
yourself are saved as they are. Categories that Gmail creates or resets get your saved settings back.
A new Gmail category that is on, and that does not already pop up, is set to pop on screen. An
account group you turned off is honored by turning its categories off, because Android lets a
companion app change categories but not groups. While the Keeper mirrors Gmail Go, Gmail Go's own
categories are set to the quietest level, so Gmail Go's own notification never shows, not even for a
split second, except any you turned off, which stay off. The settings are saved in the Keeper's
private storage, one file per app.

The link is also what lets the Gmail mirror read which accounts Gmail Go notifies about, so that it can
recognize the Gmail web app's duplicate alerts, and it lets the Keeper notice when you turn a Gmail Go
account's mail category on or off, which it passes on to the Mark as read service.

### 10. Optional, Gmail setup only: let the Gmail helper stay on after updates

Skip this step unless you set up the Gmail features and turned the Gmail helper on in Settings, then
Accessibility. Android switches an accessibility service off whenever its app is updated or
reinstalled, which would quietly stop tapping a notification to open the exact email, and the Reply
and Delete buttons, until you turned the helper on again by hand. Grant the Keeper this one
permission and it turns its own helper back on by itself after every update, on boot, and the moment
anything switches it off:

```bash
adb shell pm grant --user <USER_ID> local.notifkeeper android.permission.WRITE_SECURE_SETTINGS
```

The Keeper only ever adds its own helper to the accessibility list and never touches another app's
service. The grant survives updates, so this is a one-time step.

## Using it

### Day to day

There is nothing to do. When a web app or website withdraws a notification, the copy appears at once
in the Notification Center on the "Kept web notifications" category. Copies are silent and do not
pop up again, because the original already alerted you. Tap a copy to open its source, or swipe it
away or clear it like any other notification. Swiping or tapping an original notification yourself
never creates a copy.

With the Gmail features, swiping a Gmail copy away (or tapping it, or using Clear all) is remembered.
That email will not notify you again on this device, even if Gmail Go tries to bring its notification
back, and the choice is kept if the Keeper restarts. A later, genuinely new email from the same sender
still notifies you, because it is a different notification.

With the Gmail features, a Gmail copy also clears by itself when you read that email on another
device. In my tests this took between 20 seconds and a few minutes after the read, depending on when
Gmail Go synced.

### The status window

Open **Notification Keeper** from the launcher. The window refreshes itself every 2 seconds while it
is open. From top to bottom it shows:

* A short description of what the app does.
* **Notification access**, with a **Fix** button when access is off. The button opens the Settings
  page where you can turn the Keeper's access on.
* **Allowed to show notifications**, with a **Fix** button when the permission is off. The button
  opens the Keeper's notification settings.
* **The Gmail line.** It says whether Gmail notifications come from Gmail Go, whether the Gmail helper
  is on, or that Gmail Go is missing or blocked. This line only matters for the Gmail setup.
* **The settings guard line.** It shows how many Gmail categories and account groups are saved, and
  how many settings it has put back, or that the companion link is missing. This line only matters
  for the Gmail setup.
* **Statistics:** how many copies the Keeper has kept so far, how many of its copies are in the
  Notification Center now, and when the last one was kept and from where.
* Three buttons:
  * **Run test** posts the fake website notification described in
    [Check that it works](#8-check-that-it-works).
  * **Clear kept notifications** removes every kept web copy. It does not remove Gmail copies.
  * **Show saved Gmail settings** lists the Gmail and Gmail Go categories the settings guard has
    saved and whether each one is off, silent, default or pop on screen.

Closing the window never stops the Keeper. Android runs the listener in the background for as long as
notification access is on.

### The Keeper's own notification categories

You can change these in the Keeper's notification settings like those of any app:

* **Kept web notifications** holds the copies. It has default importance, no sound and no vibration.
* **Problems** holds the Keeper's alerts, such as "Notification Keeper is switched off". It has high
  importance.
* **Test** holds the Run test notification. It has high importance and no sound.
* **Gmail categories** exist only with the Gmail features. There is one category for each Gmail Go
  category the Keeper mirrors, grouped by account, plus "Other Gmail notifications".

### Reading its log

The Keeper writes short messages to the system log under the tag `NotifKeeper`, for example "kept a
copy of a withdrawn notification from Chrome · example.com". To print what is in the log right now
and return:

```bash
adb logcat -d -s NotifKeeper
```

## Updating

### Updating Notification Keeper

Get the new source, build it with the same `keystore.jks` and `KS_PASS` as before, and install it over
the old version:

```bash
cd ~/googlebook-notification-keeper
git pull
KS_PASS='your-password' ./build.sh
adb install -r --user <USER_ID> NotificationKeeper.apk
```

Notification access, the companion link, the saved Gmail settings and the counters all survive an
update. Android removes all of an app's notifications when the app is updated, so any kept copies
in the Notification Center disappear at that moment. After the update, the boot receiver checks
access again and keeps the guard job scheduled.

### After GooglebookOS, Chrome or web app updates

* **GooglebookOS updates** should keep notification access and the companion link, because Android
  stores both with the app. If an update ever removes access, the Keeper posts "Notification Keeper
  is switched off" within 6 hours, or at the next restart. Running the test after a big update is a
  good habit.
* **Chrome updates** could change the way Chrome tags web notifications. The Keeper would then stop
  recognizing them. Run test cannot catch this, because it posts its own notification in the current
  format. If copies stop appearing for real sites, check the log as described above.
* **Gmail and Gmail Go updates** matter only for the Gmail setup. The Keeper notices them right away
  and alerts you if Gmail Go can no longer notify. See the Gmail project for what to do.

## Uninstalling

Turn off notification access, remove the companion link if you created one (use the same address you
used to create it), and uninstall the app:

```bash
adb shell cmd notification disallow_listener local.notifkeeper/local.notifkeeper.KeeperListener <USER_ID>
adb shell cmd companiondevice disassociate <USER_ID> local.notifkeeper AA:BB:CC:DD:EE:FF
adb shell pm uninstall --user <USER_ID> local.notifkeeper
```

Uninstalling removes the app, its saved settings files and its counters. It does not undo changes the
settings guard made to other apps: Gmail categories it set to pop on screen and Gmail Go categories it
set to silent stay that way until you change them in Settings. Gmail Go notifications that the Keeper
had hidden come back on their own when their snooze ends, within 12 hours.

On the Linux side, you can delete the `~/googlebook-notification-keeper` folder when you no longer
need it. Keep `keystore.jks` if you may want to install an update of your build later.

## Troubleshooting

**Run test shows the test notification, but no copy appears.**
Check the notification access line in the status window. If notification access is off, run the `allow_listener`
command from [step 6](#6-turn-on-notification-access) again. If it is on, check that the "Kept web
notifications" category is not turned off in the Keeper's notification settings, and look at the
log with `adb logcat -d -s NotifKeeper`.

**No notifications from the Keeper appear at all.**
The permission to post notifications is off. Press the **Fix** button in the status window or run
the `pm grant` command from [step 5](#5-allow-it-to-post-notifications).

**A "Notification Keeper is switched off" alert appeared.**
The Keeper lost its notification access, which also stops it from keeping anything. Tap the alert to
open the status window and press **Fix**, or run the `allow_listener` command again.

**A "Gmail notifications stopped working" alert appeared.**
This alert only appears when Gmail Go is installed but cannot post notifications, for example after
the Play Store updated it to a newer build. Follow the Gmail setup guide to put the 2021 build back. If
you do not use the Gmail setup, uninstall Gmail Go and the alert will not come back.

**A new email never produced a notification.**
Gmail Go posts no notification for mail that arrives while its own inbox window is open on the
screen. Close the Gmail Go window. The Keeper only needs Gmail Go installed, not open.

**A Gmail copy did not clear after I read the email on another device.**
Gmail Go has to sync the read first, which can take a few minutes. Then look at the log: a line
"read sense: counts dropped" shows that the Keeper noticed, "probing" shows the check, and "READ:"
shows the copy being cleared. If the log says the Keeper is "not allowed to read Gmail Go's counts",
your Gmail app registered the permission with the system itself (mine did not, see
[Safety and privacy notes](#safety-and-privacy-notes)), so grant it once:

```bash
adb shell pm grant --user <USER_ID> local.notifkeeper com.google.android.gm.permission.READ_CONTENT_PROVIDER
```

**Tapping a notification, or Reply or Delete, stopped working after an update.**
Android switches an accessibility service off whenever its app is updated, which turns the Gmail
helper off. If you granted the permission in
[step 10](#10-optional-gmail-setup-only-let-the-gmail-helper-stay-on-after-updates), the Keeper turns
the helper back on by itself within a second. If you did not, grant it once with that command, or
turn the helper back on by hand in Settings, then Accessibility.

**Copies don't appear for a particular website.**
A copy is made only when the site itself withdraws its notification. If the original stays in the
Notification Center, there is nothing to keep. If you swiped or tapped the original, the Keeper leaves
it alone by design. If the site's notifications do disappear on their own and no copy appears, the
notification may not carry Chrome's web markers. The log shows which sources were kept.

**Older copies disappeared.**
The Keeper keeps at most 15 copies and removes the oldest one when a new one arrives. Copies also
disappear when the Keeper is updated and when the Googlebook restarts, because Android removes those
notifications.

**`adb install` fails with a signature or "update incompatible" error.**
The new APK was signed with a different key than the installed one. Build it with your original
`keystore.jks` and `KS_PASS`. If you no longer have them, uninstall the app first and repeat the
installation steps. That loses the counters and the saved Gmail settings, and if you use the Gmail
features, you may need to create the companion link again.

**The build fails while signing.**
`KS_PASS` does not match the password of the existing `keystore.jks`. Run the build again with the
password you chose the first time.

**The build cannot find aapt2, d8 or android.jar.**
The SDK is not in `~/tools/android-sdk`, or build tools 35.0.0 or the android-35 platform is missing.
Install them as described in the Getting Started guide, or point `ANDROID_SDK` at your SDK.

**The status window says the companion link is missing, after I created it.**
Reconnect the listener with the `disallow_listener` and `allow_listener` commands from
[step 9](#9-optional-gmail-setup-only-create-the-companion-link), then reopen the window. Check the
link with `cmd companiondevice list`. If the `associate` command printed an error, make sure the
address is the third plain argument and is written as six pairs of hexadecimal digits separated by
colons.

## Safety and privacy notes

* **No network access.** The manifest has no `INTERNET` permission, so the app cannot send anything
  anywhere.
* **Little background load.** It holds no wake locks and runs no foreground service. Android binds
  the listener, and the guard job runs every 6 hours with only quick package and settings lookups.
  The only repeated work is the Gmail read check: while a Gmail copy is waiting to be read, the Keeper
  reads Gmail Go's unread counts every 2.5 seconds inside the learned window after a new mail and
  every 15 seconds after it, and it stops completely when no copy is waiting.
* **Gmail Go's unread counts.** With the Gmail features, the Keeper reads Gmail Go's public content
  provider, the same one launchers use for badge numbers. It returns one row per label with the
  number of unread conversations, never subjects, senders or message text. Reading it needs Gmail's
  own permission `com.google.android.gm.permission.READ_CONTENT_PROVIDER`. On my Googlebook neither
  Gmail app had registered that permission with the system, so no app could have been granted it;
  the Keeper therefore declares the permission itself at the normal level and receives it on
  install. On a device where Gmail does register it, Android keeps Gmail's definition, and the
  permission then needs the one-time adb grant shown in Troubleshooting. I have not tested that case.
* **What notification access means.** Like every notification listener, the Keeper is shown every
  notification on the device. It acts only on web notifications that their own site withdraws and,
  with the Gmail features, on notifications from Gmail Go and the Gmail web app, and, with the Home
  Depot feature, on order update emails from Home Depot in any email app. Everything else is ignored.
* **What it stores.** In its private app storage it keeps counters, the time and source (the app name
  and website host) of the last kept copy, and, with the companion link, the saved notification
  categories of Gmail and Gmail Go. Those saved categories include their names and identifiers, and
  Gmail Go's identifiers contain your account addresses. The read detection stores a short list of
  numbers: how long after arrival the last few emails were read. It stores no notification titles or
  texts in its files. The app sets `allowBackup` to false, so Android backup does not copy its data.
* **The Home Depot feature** reads the sender address, display name, subject and account of mail
  notifications so that it can recognize Home Depot's order emails, and it acts only on the ones that
  match. In its private storage it keeps only a count of the cards it has shown, the time of the last
  one, and a short list of the identifiers of the order cards you have swiped away, so that it does not
  put those back. Those identifiers are Android's own notification keys, which for some email apps
  contain the account address. It stores no email subjects, senders or message text, and it has no
  part in the Gmail setup.
* **What it writes to the system log.** It logs the source of each kept copy. With the Gmail features, the
  Mark as read button writes the account, the sender's address, the subject and the arrival time
  under the tag `GmailGhostCmd`, because that is how the Linux Terminal service receives the command,
  and each new email pop-up writes the account address under the same tag. The read detection logs
  the account address, the names of the labels whose counts changed and the identifier of the copy it
  cleared, never a subject or sender. On Android, ordinary apps
  cannot read other apps' log messages. The system, adb, and apps that you give log access through
  adb can.
* **The companion link** gives the Keeper the right to read and change any app's notification
  categories. The code uses it only for Gmail and Gmail Go (`SettingsGuard.PKGS`). Create it only if
  you use the Gmail setup.
* **The Gmail helper** is an accessibility service and is off unless you turn it on. Its configuration
  limits it to events from Chrome and Gmail, it only touches the screen when the Gmail web app is the
  active window and nothing covers the spot, and it stores nothing. Leave it off if you do not use the
  Gmail setup.
* **Exported components.** The test trigger (`DebugReceiver`) requires the `DUMP` permission, which
  only the adb shell has. The listener and the Gmail helper can only be bound by the system. The boot
  receiver and the package receiver only start a quick status check.

## Limitations and known issues

* **It cannot make a closed web app notify.** If a web app's page is closed and it has no way to
  receive push messages, nothing is posted, so there is nothing to keep. For Gmail, the Gmail project
  solves that part with Gmail Go.
* **It cannot tell why a site withdrew a notification.** Some sites withdraw a notification because
  you have already seen the message in the open page. The Keeper keeps a copy of those too.
* **Copies are simpler than the originals.** They have no buttons, and tapping opens the site's root
  address or the web app's start screen, not the specific message or page.
* **Copies are silent** and never pop up a second time.
* **It keeps at most 15 copies.** The limit is counted in memory, so copies left over from before the
  Keeper's process restarted are not counted toward it.
* **It depends on Chrome's notification markers.** A Chrome change to the tag or group format would
  stop the keeping, and Run test would not notice.
* **No warning when Gmail Go is removed.** `Keeper.runGuard()` treats a missing Gmail Go as "the Gmail
  setup is not in use", so if you do use the Gmail setup and Gmail Go gets uninstalled, the Keeper
  stays quiet. The status window still shows that Gmail Go is missing.
* **Parts of the status window are about Gmail** even when you only use the generic keeper.
* **Rich features exist only for Gmail.** Action buttons, the settings guard and mirroring are written
  for Gmail and Gmail Go. See the next section.
* **Read detection waits for Gmail Go.** A copy clears only after Gmail Go has synced the read, which
  in my tests took between 20 seconds and a few minutes.
* **Gmail Go does not notify while its own inbox window is open**, so no copy appears for mail that
  arrives then. Keep Gmail Go installed but closed.
* **Hidden Gmail Go notifications are snoozed 12 hours at a time.** When a snooze ends, Android
  re-posts the notification and the Keeper hides it again at once. I have not seen it flicker, but
  the re-post does happen. Uninstalling the Keeper lets any hidden ones reappear when their snooze ends.
* **The unread-count permission** is declared by the Keeper itself, which only works because Gmail
  had not registered it on my device. See Safety and privacy notes.
* **Home Depot order notifications need an email app.** A Home Depot card appears only when an order
  update email from Home Depot arrives in an email app on the Googlebook that posts a notification for
  it. On my Googlebook, Gmail Go covers my Gmail accounts and Outlook covers my other account. There
  is no path for Home Depot's text messages, because the laptop has no SIM card.
* **I have not seen the Home Depot feature work end to end with a real order email.** I verified each
  part on its own, including with adb tests that run the real recognition test and post the real card,
  but I have not yet watched a genuine order email become a card, because an email-app notification
  cannot be forced. See [Home Depot order notifications](#home-depot-order-notifications).
* **The app is in English only**, and I have tested it only on my Googlebook.
* **There is no prebuilt APK.** You build it yourself with `build.sh`.

## Contributing: extending it to other web apps

My goal for Notification Keeper is to preserve notifications in the GooglebookOS Notification Center
for every Chrome web app. The generic keeping already does that for any web app whose alerts are
withdrawn by the web app itself. Everything beyond that is written for Gmail, and bringing it to other
web apps means editing the package names written into the code and writing matching logic for each
app. This section tells you where that code is and what you would need to write. Line numbers refer to
version 1.2.

### What already works for every web app

The whole keeping path is generic and needs no changes for a new web app:

* `Keeper.webOrigin()` (`Keeper.java`, line 75) reads the origin from Chrome's tag pattern `WEB_TAG`
  (line 46) or from a `Web:` group.
* `Keeper.shouldKeep()` (line 89) accepts only removals with reason 8 or 9.
* `Keeper.keep()` (line 113) builds the copy, and `OpenActivity` opens the source when it is tapped.
* `KeeperListener.onNotificationRemoved()` (`KeeperListener.java`, line 130) calls both for every
  removed notification, from any package.

### Where the Gmail-specific code lives

| File | Gmail-specific code |
| --- | --- |
| `Keeper.java` | The package names `GMAIL` and `GMAIL_GO` (lines 38 and 39). Two Gmail exclusions in `shouldKeep()` (lines 106 and 108). `gmailState()` (line 234) and the Gmail half of `runGuard()` (line 266). `MAX_KEPT` (line 43) shares the 50-notification budget with the Gmail copies. |
| `KeeperListener.java` | `onNotificationPosted()` (line 115) sends Gmail Go's notifications to `GmailMirror.onPosted()` and the Gmail web app's to `GmailMirror.onWebGmailPosted()`. The package receiver (line 39) only reacts to Gmail and Gmail Go. `onNotificationChannelModified()` (line 87) passes Gmail Go's category changes to `GhostReceiver`. |
| `GmailMirror.java` | All of the mirroring. Gmail Go's channel prefix `MAIL_CHANNEL` and account group prefix `ACCOUNT_GROUP` (lines 64 and 65), the Gmail web app's new-mail tag `WEB_MAIL_TAG` (line 68), the engine check `engine()` (line 242), the hiding `hideGo()` (line 95) and the read probe `probe()` (line 112), the copy builder `mirror()` (line 364), the duplicate removal `onWebGmailPosted()` (line 674), `openWebGmail()` (line 788), `inboxUrl()` (line 810), `accountOf()` (line 835) and `MAX_MIRRORED` (line 55). |
| `GoCounts.java`, `ReadSense.java` | The read detection: Gmail Go's provider authority and label names, the count snapshots, and the timing of the checks. |
| `SettingsGuard.java` | The guarded packages `PKGS` (line 43), their file names in `file()` (line 54) and display names in `label()` (line 58), my "pop on screen" rule in `newCategoryRule()` (line 262), the Gmail Go silencing in `silenceOriginals()` (line 205), and `summary()` (line 360), which reads only Gmail's file. |
| `WebGmailHelper.java` and `res/xml/web_gmail_helper.xml` | The accessibility helper. It reads Gmail's web page structure, and the XML limits it to the packages `com.android.chrome` and `com.google.android.gm`. |
| `GhostReceiver.java`, `ActionReceiver.java`, `DebugReceiver.java` | Mark as read, Gmail Go's background buttons, and the adb test trigger. |
| `OpenActivity.java` | `isBrowser()` (line 70) decides whether a source is Chrome. The Gmail branches use the `url` and `kind` extras. |
| `MainActivity.java` | `refresh()` shows the Gmail line (line 136) and the settings guard line (line 150). |
| `AndroidManifest.xml` | The `<queries>` block (line 19) names Gmail and Gmail Go, so the app may look them up, and the `<permission>` element (line 13) declares Gmail's unread-count permission. |
| `res/values/strings.xml` | All Gmail texts and alerts. |

### What you would write to add another web app

1. **Find the package that posts its notifications.** A website posts as Chrome
   (`com.android.chrome`). An installed web app may post under its own package, the way the Gmail web
   app posts as `com.google.android.gm`. `adb shell pm list packages` lists the packages on the
   device.
2. **Declare it for package visibility.** If your code calls `PackageManager` for that package, add it
   to `<queries>` in `AndroidManifest.xml`. Launching an app through its launcher entry already works
   for every app, because the manifest queries all launcher activities.
3. **Avoid duplicates.** If the web app also has a native engine that posts its own notifications,
   add a check to `Keeper.shouldKeep()` like the two Gmail lines, so the web app's withdrawn alerts are
   not kept as well.
4. **Write a mirror for rich notifications.** Model a new class on `GmailMirror` and call it from
   `KeeperListener.onNotificationPosted()` with a package check. It needs to decide which notifications
   to mirror (Gmail uses Gmail Go's channel prefix, which also tells it the account), post a copy on
   its own channel and group, decide what a tap opens (Gmail builds a web address with `inboxUrl()`
   and sends it to the web app's own screen in `openWebGmail()`, which picks the web app's
   `TwaActivity` so that Android does not ask which app to use), decide what each button does, and
   hide the original only after the copy is posted (the Keeper snoozes Gmail Go's). Keep the total of `MAX_KEPT`, your own limit,
   the Gmail limit, summaries and alerts under Android's 50 notifications per app.
5. **Guard its settings, if you want that.** Add the package to `SettingsGuard.PKGS`, give it a file
   name in `file()` and a display name in `label()`, and review `newCategoryRule()`, which applies my
   personal rule that every new Gmail category that is on should pop up. That rule may not suit other
   apps. The settings guard needs the companion link.
6. **Adjust the status window and the checks.** `MainActivity.refresh()` and `Keeper.runGuard()` only
   know about Gmail. A more general version could skip the Gmail line and the Gmail alert when Gmail Go
   is not installed, and add lines for other apps.
7. **Test it.** `Keeper.postTest()` shows how to fake a Chrome web notification for testing. Build with
   `build.sh`, install with `adb install -r`, and watch `adb logcat -d -s NotifKeeper`.

A cleaner long-term design, which the current code does not have yet, would replace the hardcoded
Gmail constants with a small per-app profile: the packages to watch, how to recognize the app's
notifications, how to build a web address for a tap, and how to open it. `KeeperListener` would then
loop over a list of profiles instead of checking for Gmail by name. Forks and pull requests are
welcome.

## Files in this repository

| File | Purpose |
| --- | --- |
| `AndroidManifest.xml` | Package `local.notifkeeper`, permissions, components and package visibility |
| `build.sh` | Builds and signs `NotificationKeeper.apk` (aapt2, javac, d8, zipalign, apksigner) |
| `src/local/notifkeeper/KeeperListener.java` | The notification listener that receives posted and removed notifications |
| `src/local/notifkeeper/Keeper.java` | Generic keeping, categories, checks and the Run test |
| `src/local/notifkeeper/OpenActivity.java` | Opens the web app or website when a copy is tapped |
| `src/local/notifkeeper/MainActivity.java` | The status window |
| `src/local/notifkeeper/GuardJob.java` | The 6-hour check |
| `src/local/notifkeeper/BootReceiver.java` | Checks after a restart or an app update |
| `src/local/notifkeeper/GmailMirror.java` | Gmail: shows Gmail Go's notifications as copies linked to the Gmail web app, hides the originals and runs the read probe |
| `src/local/notifkeeper/GoCounts.java` | Gmail: reads Gmail Go's unread counts and watches them for changes |
| `src/local/notifkeeper/ReadSense.java` | Gmail: schedules the read checks and learns when to look |
| `src/local/notifkeeper/WebGmailHelper.java` | Gmail: the optional accessibility helper |
| `src/local/notifkeeper/GhostReceiver.java` | Gmail: the Mark as read command for the Linux Terminal service |
| `src/local/notifkeeper/ActionReceiver.java` | Gmail: runs Gmail Go's own background buttons |
| `src/local/notifkeeper/SettingsGuard.java` | Gmail: saves and restores Gmail's and Gmail Go's notification categories |
| `src/local/notifkeeper/DebugReceiver.java` | Gmail and Home Depot: test triggers that only adb can call |
| `src/local/notifkeeper/HomeDepotBridge.java` | Home Depot: shows Home Depot order update emails from any email app as Home Depot notifications |
| `res/values/strings.xml` | All app text |
| `res/xml/web_gmail_helper.xml` | Accessibility settings for the Gmail helper |
| `res/layout/` | The Home Depot order card, collapsed and expanded |
| `res/drawable/` | The app icon, the notification icons and the Home Depot logo tile (vector drawables) |
| `LICENSE` | MIT License |
| `.gitignore` | Keeps build outputs, keystores and logs out of the repository |

## Related projects

* [googlebook-gmail-web-app-notifications](https://github.com/iamflying29-sketch/googlebook-gmail-web-app-notifications):
  the complete Gmail setup that uses Notification Keeper's Gmail features, Gmail Go and a Linux
  Terminal service to give the Gmail web app real, lasting notifications even when it is closed.
* [googlebook-projects](https://github.com/iamflying29-sketch/googlebook-projects): the overview of
  all my Googlebook projects and the shared
  [Getting Started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md).

## Disclaimer and license

This is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by
Google, Lenovo or any other company named here. All product names belong to their owners. Released
under the MIT License. Copyright (c) 2026 Thomas Liviakis.
