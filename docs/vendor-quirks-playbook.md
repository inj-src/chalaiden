# Vendor quirks playbook

For an agent helping the maintainer check Chalaiden on volunteers' phones over `adb`, and fix what
each phone maker does differently with bKash's `*247#` USSD dialog. The goal is about 30 phones.

Read all of **Hard rules** before running anything on a volunteer's phone. The rest is reference: a
session runbook, what to look for, where fixes go, and what is already known.

---

## Hard rules

These come before any test result.

1. **Never send a PIN, and never finish a payment, send-money, cash-out or balance check.** Walk
   bKash's menu only as far as its `Enter PIN` prompt, then **Cancel**. Never press **Hold to pay /
   Hold to check**. Only the phone's owner ever types their PIN, and not during these tests.
   - Chalaiden opens `*247#` when the first PIN digit goes in (the "warm-up"). To start a walk, tap
     **one** digit on Chalaiden's PIN pad. That digit stays on the pad and goes nowhere: nothing reaches
     bKash until the hold completes. Then press Cancel.
2. **The volunteer is present and agrees to each step.** Say what you're about to do before you do
   it: install, dial `*247#`, turn on a setting. Ask which SIM holds their bKash account.
3. **Never open or touch the bKash app** or any other personal app: messages, contacts, gallery,
   banking. Chalaiden's camera screen is fine with no card in view.
4. **Keep personal data out of everything you save or show.** No phone numbers, balances, names,
   IMEIs, serial numbers or account details in reports, commits or screenshots.
   - Mask every run of 4+ digits in dumps: `sed -E 's/[0-9]{4,}/####/g'`.
   - Identify a phone by maker, model and Android version only.
   - Only take screenshots of Chalaiden's own screens. Use `adb exec-out screencap -p`; OnePlus blocks
     `screenrecord`. The bKash app uses FLAG_SECURE and comes out black anyway.
5. **Put every setting back.** Before you change anything, save its old value: accessibility
   services, keyboards, overlay permission, Chalaiden's chosen method. Restore each one at the end, and
   uninstall Chalaiden unless the volunteer wants to keep it (see **Restore**).
6. **Never hide Chalaiden from bKash's fraud checks.**
   - Its accessibility service is visible and plainly named. It stays off until the user switches it
     on, and it never claims `isAccessibilityTool`.
   - Don't rename, obfuscate or hide it, and don't help hide root or the app.
   - If the bKash app ever warns about accessibility, write down exactly what it said and on which
     phone and bKash version, and tell the maintainer. The answer for users is guided mode or Shizuku,
     never evasion.
7. **Don't send `input` events, launch apps or change settings on a phone you weren't handed for this
   test.** Check what's in front first:
   `adb shell dumpsys activity activities | grep -m1 topResumedActivity`.

---

## How Chalaiden reaches the USSD dialog

Four methods. Any one is enough, and the user picks one in Setup.

| Method | What it needs | How it reads and answers the dialog | Cover window |
|---|---|---|---|
| **Guided** | Chalaiden keyboard switched on, plus "display over other apps" | The user taps Send; Chalaiden's keyboard (`keyboard/ChalaidenKeyboard.kt`) types each reply | A floating card (`GuideCover`) |
| **Accessibility** | Chalaiden's accessibility service switched on | `ussd/ChalaidenAccessibility.kt` → `AccessibilityScreen` → `helper/PhoneDialog.kt` | Accessibility overlay (no overlay permission needed) |
| **Shizuku** | Shizuku running and allowed | `app_process` helper `helper/UiHelper.kt` (UiAutomation) → `PhoneDialog`; falls back to `uiautomator dump` (`DumpScreen`) | `TYPE_APPLICATION_OVERLAY` |
| **Root** | `su` | Same as Shizuku | Same |

The parts that matter for vendor work:

- `helper/PhoneDialog.kt` holds **all** node logic for the automatic methods:
  - finding the phone's window (`root()`);
  - reading bKash's message, the reply box and the progress spinner (`look()`);
  - typing and sending (`reply()`);
  - pressing `android:id/button1|button2` (`press()`, with a tree-walk fallback).

  A layout fix here reaches Accessibility, Shizuku and Root at once.
- `ussd/Prompt.kt` holds text and ids:
  - `isProgress` for the phone's "Running USSD code…" wait text;
  - `MESSAGE_IDS`, where the message lives;
  - `menuLine`, how bKash's menu lines are numbered.
- `vendor/PhoneQuirks.kt` holds **behaviour** that differs by maker, such as `closesDialogOnSend` and
  the timing derived from it. Code asks about behaviour, never brand; a brand appears only in
  `PhoneQuirks.of()`.
- `ussd/UssdScreen.kt` has `DumpScreen`, the slow fallback that parses `uiautomator dump` XML, plus
  its `FOCUS` and `DUMP` shell commands.
- `ussd/UssdDriver.kt` runs the polling loop:
  - it sweeps old dialogs, dials, and executes the engine's Reply, Cancel and Dismiss;
  - it switches the keyboard off for the session;
  - it decides when a dialog is "lost" (`PhoneQuirks.lostAfterMillis`).
- `ussd/UssdEngine.kt` and `UssdSession.kt` hold bKash's menu logic. That's vendor-neutral and rarely
  the problem.

---

## Session runbook (one phone)

Each command below assumes one phone is connected. With several, add `-s <serial>` everywhere, but
never write the serial into a report.

### 0. Before the volunteer arrives

Build the debug APK. Only debug builds write `files/driver.log` and allow `run-as`.

```sh
./gradlew -q :app:testDebugUnitTest :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Start a report from the template at the end of this file, saved as
`docs/vendor-reports/<maker>-<model>-<yyyy-mm-dd>.md`.

### 1. Connect and record the phone

**Xiaomi, Redmi and POCO (MIUI or HyperOS):** before the session, ask the volunteer to turn on
Developer options › **Install via USB** and **USB debugging (Security settings)**. Both need a signed-in
Mi account, which is slow to sort out on the spot. Without them adb can't install, tap, or change any
setting, not even the screen timeout.

The volunteer turns on Developer options and USB debugging, then accepts the RSA prompt.

```sh
adb devices -l
adb shell dumpsys activity activities | grep -m1 topResumedActivity   # what's in front
for p in ro.product.manufacturer ro.product.brand ro.product.model ro.build.version.release \
         ro.build.version.sdk ro.build.display.id ro.config.low_ram persist.sys.locale \
         ro.build.version.oplusrom ro.vivo.os.version ro.miui.ui.version.name ro.build.version.emui \
         ro.build.version.oneui ro.mi.os.version.name ro.os.version.release; do
  printf '%s=%s\n' "$p" "$(adb shell getprop $p)"; done
adb shell dumpsys package com.android.phone | grep -m1 versionName
adb shell settings get secure enabled_accessibility_services   # SAVE THIS
adb shell settings get secure default_input_method             # SAVE THIS
adb shell ime list -s                                          # SAVE THIS
adb shell pm list packages | grep -iE "bkash|shizuku|chalaiden"
```

Things to note:

- **`ro.config.low_ram=true` means Android Go.** Go phones can't grant "display over other apps", so
  Guided and Shizuku/Root have no cover. Accessibility hosts its cover as an accessibility overlay
  instead, so check whether it works there.
- **The locale.** The phone app's own text (progress text, button labels) follows it. Chalaiden
  matches the progress text in English only: "Running USSD" / "USSD code running". A Bangla phone may
  say something else.

### 2. Install Chalaiden

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the volunteer already has the release APK (different
signing key). Uninstalling it loses their settings, so ask first.

Open Chalaiden once and grant phone access in its Setup. Pick the bKash SIM there too.

### 3. Look at the raw dialog

This is the most useful data you'll collect. Dial `*247#` without Chalaiden driving anything:

```sh
adb shell am start -a android.intent.action.CALL -d 'tel:*247%23'
```

If a SIM picker appears, dump it, then tap the bKash SIM. Record the picker's package; vivo's comes
from `com.android.server.telecom`.

```sh
adb shell 'uiautomator dump /data/local/tmp/u.xml >/dev/null && cat /data/local/tmp/u.xml; rm -f /data/local/tmp/u.xml' \
  | sed -E 's/[0-9]{4,}/####/g' > /tmp/dump-menu.xml
adb shell dumpsys window | grep mCurrentFocus     # every display; vivo lists an extra null one
```

`uiautomator dump` unbinds every accessibility service while it runs, Chalaiden's included. Never run
it while an Accessibility walk is going: the session falls back to Root and fails with
`No Root shell`. The helper's `tree` op (below) reads the same thing and leaves services alone.

From the dump, record:

- the `package` of the dialog window (expected `com.android.phone`);
- the resource-id holding bKash's text: `com.android.phone:id/message_field`, `android:id/message`,
  `alertTitle`, or something new;
- the reply box: an `EditText`, and whether it's `password="true"` at the PIN prompt;
- the Send and Cancel buttons: are they `android:id/button1` and `button2`, clickable? Or are they
  wrapped like vivo's `VButton` with a `vbutton_title` child?
- how menu lines look: `1 Send Money`, `1. Send Money`, `1.Send Money`, and so on;
- what the progress dialog says while waiting. Dump it right after a reply: type `9` + Send by hand,
  or let Chalaiden do it in step 4.

Then try the helper against the same dialog. It's the exact code Accessibility, Shizuku and Root run,
started here as the adb shell user:

```sh
APK=$(adb shell pm path com.chalaiden.app | cut -d: -f2 | tr -d '\r')
adb shell "(echo '{\"op\":\"look\"}'; sleep 1) | CLASSPATH=$APK app_process /system/bin com.chalaiden.app.helper.UiHelper" \
  | sed -E 's/[0-9]{4,}/####/g'
# {"ready":true}
# {"win":true,"progress":false,"message":"...","input":true,"field":"","password":false}
```

For the whole layout, every window with each node's id, class, text and flags, use `tree`. It works
during any walk, so it's the way to see a dialog that sits behind Chalaiden's cover:

```sh
adb shell "(echo '{\"op\":\"tree\"}'; sleep 1) | CLASSPATH=$APK app_process /system/bin com.chalaiden.app.helper.UiHelper" \
  | sed -E 's/[0-9]{4,}/####/g' | python3 -c 'import json,sys; [print(l) for o in map(json.loads, sys.stdin) for l in o.get("lines", [])]'
```

To catch every screen of a walk, send `tree` in a loop (`for i in $(seq 18); do echo '{"op":"tree"}';
sleep 0.7; done | …`) while it runs.

- `win:false` while the dialog is up: `root()` doesn't find the phone's window.
- An empty or wrong `message`: the text lives under an id `MESSAGE_IDS` doesn't know.
- Then close the dialog with the helper. Cancel is button2; this checks that `press()` works:

  ```sh
  adb shell "(echo '{\"op\":\"press\",\"id\":\"button2\"}'; sleep 1) | CLASSPATH=$APK app_process /system/bin com.chalaiden.app.helper.UiHelper"
  # {"ok":true}  — and the dialog is gone
  ```

  `{"ok":false}` with the dialog still up is a press problem. Close it by hand with Cancel, then see
  **Symptoms**.

Never use the helper's `reply` op on a volunteer's phone. It types and **sends**.

### 4. Test each method: walk to the PIN prompt, then Cancel

Before each walk, clear the old trace. After each walk, read it.

```sh
adb shell run-as com.chalaiden.app cat files/driver.log
```

`driver.log` lines look like `<ms> <Dialog> '<last line>' -> <Action> [<state>]`:

```
Accessibility screen: AccessibilityScreen, keyboards off: hidden
dialled
Progress '' -> None [Working(stepIndex=0, label=Opening bKash menu)]
Prompt '10 Reset PIN' -> Reply [Working(stepIndex=1, label=Choosing Check Balance)]
Progress '' -> None [...]
Prompt 'Enter PIN:' -> None [AwaitingPin(...)]     ← stop here
Prompt 'Enter PIN:' -> Cancel [null]               ← after Cancel
```

Lines that mean trouble:

| Line | Meaning |
|---|---|
| `lost` | No dialog was seen for `lostAfterMillis` |
| `reply undelivered` | The prompt changed or the box was gone |
| `failed: …` | The shell, the helper or the service failed |
| `helper closed` | Normal after a session |

The walk itself: Home → **Balance** → tap one digit → wait about 10 s (watch the log) → **Cancel**.

Without touching the screen (debug build; needed on HyperOS, where adb can't tap):

```sh
adb shell am start -n com.chalaiden.app/.MainActivity --es walk balance   # dials, stops at bKash's PIN prompt
adb shell am start -n com.chalaiden.app/.MainActivity --es walk cancel    # closes it; no PIN is ever typed
```
 On
the shell side, check that the dialog is gone (`mCurrentFocus` isn't `com.android.phone`) and that the
keyboard works again in any text field.

Choosing a method without going through Setup (debug build; save the old value first):

```sh
adb shell am force-stop com.chalaiden.app
adb shell run-as com.chalaiden.app cat shared_prefs/chalaiden.xml | grep method      # SAVE
adb shell "run-as com.chalaiden.app sed -i 's|<string name=\"method\">[A-Za-z]*</string>|<string name=\"method\">Accessibility</string>|' shared_prefs/chalaiden.xml"
```

If there's no `method` line yet, choose the method in Setup instead (⋮ → Change method).

**Accessibility**

```sh
OLD=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')   # may be "null"
NEW=com.chalaiden.app/com.chalaiden.app.ussd.ChalaidenAccessibility
adb shell settings put secure enabled_accessibility_services "$( [ "$OLD" = null ] || [ -z "$OLD" ] && echo $NEW || echo "$OLD:$NEW")"
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -A1 "Bound services"     # Chalaiden should be listed
```

- HyperOS refuses `settings put secure` from adb (see **Known quirks**). There, the volunteer turns
  Chalaiden on in Setup → Accessibility → "Turn on in Settings", and off again at the end.
- Turning the service on over adb skips Android 13+'s "restricted settings" block. Also record
  whether the volunteer's phone greys out the switch in Settings for a sideloaded app, and where the
  ⋮ → "Allow restricted settings" item lives. Setup's guide depends on that.
- Watch that the cover covers everything, the soft keyboard stays down during the walk, and the
  keyboard comes back afterwards.

**Shizuku**

Only if the volunteer already uses Shizuku, or agrees to install it. Start it from adb:

```sh
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Allow Chalaiden in Shizuku. The trace's first line should say `Shizuku screen: HelperScreen`;
`DumpScreen` means the helper failed to start.

**Root**

Only on phones that are already rooted. Never root a volunteer's phone.

**Guided**

```sh
adb shell ime enable com.chalaiden.app/.keyboard.KeyboardService
adb shell appops set com.chalaiden.app SYSTEM_ALERT_WINDOW allow
```

The volunteer, or you with their OK, taps Send on each step. Watch these:

- whether the floating card stays up between prompts;
- whether the card disappears too early. That happens on phones that close the dialog on Send:
  `frontGapMillis`, `GONE_MILLIS` in `ChalaidenKeyboard.kt`;
- whether the keyboard picker appears.

Stop at the PIN prompt and press the dialog's Cancel.

### 5. Restore

Put back everything you saved, then check it:

```sh
adb shell settings put secure enabled_accessibility_services "$OLD"   # or: settings delete secure enabled_accessibility_services  (if it was null)
adb shell ime disable com.chalaiden.app/.keyboard.KeyboardService      # if you enabled it
adb shell ime set "<saved default_input_method>"
adb shell appops set com.chalaiden.app SYSTEM_ALERT_WINDOW default    # if you allowed it
adb uninstall com.chalaiden.app                                        # unless the volunteer keeps it
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure default_input_method
```

- On HyperOS the `settings put` line fails: the volunteer switches Chalaiden off in Settings ›
  Accessibility themselves. Check with `dumpsys accessibility | grep -A1 "Bound services"`.
- If a session was killed while the keyboards were off (Root/Shizuku), Chalaiden puts them back on
  its next launch. Check `ime list -s` against what you saved anyway.
- Then leave the phone on its home screen, and give it back.

---

## Symptoms → cause → where to fix

| What you see | Likely cause | Fix in |
|---|---|---|
| Helper `look` says `win:false` with the dialog up | The dialog isn't in `com.android.phone`, or sits in a window the layer sort misses | `PhoneDialog.root()`; `PhoneDialog.PHONE` (make it a list if a maker uses its own phone app) |
| `message` empty or wrong, so the engine never answers | bKash's text sits under an unknown id, or in a title | `Prompt.MESSAGE_IDS` (append; keep the stock ids first) |
| Stuck on the progress dialog, or the progress text taken as a message | Progress text is worded differently or in Bangla | `Prompt.isProgress` (add the exact phrase, with phone/date) |
| `press` returns false and buttons don't respond | Buttons not found by id, not clickable, or the click lands on a wrapper | `PhoneDialog.press()` (the tree-walk fallback already covers vivo; add e.g. "clickable parent of a node with that id") |
| Menu walk fails to choose an item (`Reply` never sent) | Menu line format, e.g. `1.Send Money`, `1)Send`, or Bangla digits | `Prompt.menuLine` + a test in `UssdEngineTest` |
| `lost` mid-walk, then bKash's next menu shows up later | The phone closes the dialog on Send and reopens it with the answer | `PhoneQuirks`: set `closesDialogOnSend` for that maker in `of()` |
| The trace shows `DumpScreen` and every step is slow | The helper didn't start (UiAutomation refused) | `UiHelper.connect()`; run the helper command by hand to see `{"ready":false,"why":…}` |
| `DumpScreen` never sees the dialog | `mCurrentFocus` on another display, or the dump misses the phone package | `DumpScreen.FOCUS`, `phoneFocused()` |
| The keyboard shows above the cover (Root/Shizuku) | `ime disable` didn't take, or a vendor keyboard re-enables itself | `UssdDriver.keyboardsOff/On` |
| The cover doesn't appear | Overlay permission missing (Go phone?) or the window type refused | `RunCover.show()`; Accessibility uses `TYPE_ACCESSIBILITY_OVERLAY` |
| A SIM picker appears despite a chosen SIM | The maker's telecom ignores the call intent's phone account | `Dialer.callIntent`; a `PhoneQuirks` flag if the picker has to be answered |
| The USSD dialog never opens from Chalaiden | The dial needs a foreground activity (OxygenOS), or the maker blocks `CALL` for USSD | `MainActivity.drive` / `Dialer` |
| Guided card disappears between prompts | The dialog closes on Send (same as the `lost` row) | `PhoneQuirks.closesDialogOnSend` → `frontGapMillis` |
| Setup says "No SIM found" with SIMs in and phone access allowed | The maker hides the SIM list from apps | `Dialer.simsBySlot` / `accountBySlot` already cover it; run `--ez sims_probe true` and read `files/sims.log` to see which calls are refused |
| `dialled`, then `lost`, and bKash's menu appears later | Another app also takes calls (a VoIP app), so Android asked "Complete action using" behind the cover | `Dialer.systemCaller` always dials through the system handler; check `cmd package query-activities -a android.intent.action.CALL -d tel:1` |
| Accessibility walk fails at once with `failed: … No Root shell` | The service wasn't connected when the walk started, so Chalaiden fell back to Root. Something unbound it: `uiautomator dump`, or it's off | Check `dumpsys accessibility` → Bound services; don't run `uiautomator` during a walk |
| bKash shows "Do you want to continue? 1-Yes 2-No" first | An unfinished earlier session (not a vendor quirk) | Already handled: `Prompt.isResumeOffer` |

---

## How to apply a fix

1. **Write down the evidence first:** the masked dump excerpt, the helper output, and the `driver.log`
   lines. Put them in the phone's report.
2. **Fix by behaviour, not by brand.** Add the behaviour to `PhoneQuirks` as a property that defaults
   to stock behaviour, and switch it on for the maker in `PhoneQuirks.of(manufacturer, brand)`. Check
   both `Build.MANUFACTURER` and `Build.BRAND`, because sub-brands differ: iQOO is vivo, POCO and
   Redmi are Xiaomi, realme and OnePlus are close to OPPO. Text and id differences that are harmless
   elsewhere go straight into `Prompt` or `PhoneDialog` with no flag.
3. **Every quirk carries its proof in a comment:** model, Android version, skin, date, and what was
   seen. For example: `vivo V2445A, Android 15 (OriginOS 5), 2026-11-12: the message is in alertTitle`.
4. **Add a test for it:**
   - `PhoneQuirksTest` for a flag;
   - `UssdEngineTest` for menu text, with the masked real text as the fixture;
   - `LookTest` for how a look is read;
   - `SteadyTest` is for the scanner, not USSD.
5. **Build and test:** `./gradlew -q :app:testDebugUnitTest :app:assembleDebug`. Re-run the walk on the
   phone if it's still there.
6. **Commit** in small groups, one quirk per commit where you can. Use a message that says what the
   phone does and what Chalaiden now does, ending with the maintainer's attribution line (see
   **Project rules**). Push `main` to `origin`.
7. **Add the phone** to **Known phones** below.

---

## Known quirks (verified on real phones)

| Phone | What it does | Where it's handled |
|---|---|---|
| Stock / OxygenOS (OnePlus CPH2767, Android 16) | Keeps a "Running USSD code…" dialog between prompts. Stacks USSD dialogs, and the previous one can linger for a moment | `PhoneDialog.root()` takes the top layer; `UssdEngine` ignores prompts it has already answered |
| OxygenOS | Shows the USSD dialog only when dialled from a foreground app | `MainActivity.drive` dials from the activity, not the shell |
| OxygenOS | Hides app logcat; `screenrecord` is blocked | Debug `files/driver.log`; use `exec-out screencap` |
| vivo / iQOO (V2445A, Android 15, OriginOS 5) | Send closes the dialog; bKash's answer comes in a **new** dialog about 2.8 s later (SIM 2) | `PhoneQuirks.closesDialogOnSend` → `frontGapMillis` 12 s, `lostAfterMillis` |
| vivo | Progress text is "USSD code running…" (`content_loading_layout_desc`) | `Prompt.isProgress` |
| vivo | bKash's text is in `com.android.phone:id/alertTitle` | `Prompt.MESSAGE_IDS` (after the stock ids) |
| vivo | Buttons carry `android:id/button1/2` inside `VButton` with `vbutton_title` text, but `findAccessibilityNodeInfosByViewId` returns nothing | `PhoneDialog.press()` tree-walk fallback |
| vivo | `dumpsys window` lists an extra display first whose focus is always null | `DumpScreen.FOCUS` greps every display |
| vivo | The SIM picker is `com.android.server.telecom` | Noted; Chalaiden passes the chosen SIM |
| Banglalink SIM (seen on vivo) | bKash menu lines have no space: `9.My bKash` | `Prompt.menuLine` |
| Xiaomi / Redmi (REDMI Turbo 4 Pro, Android 17, HyperOS 4) | bKash's text is in `com.android.phone:id/ussd_message`, under a `title` that says "Carrier info". With no known id, the title outran "Enter PIN:" in the longest-text fallback | `Prompt.MESSAGE_IDS` |
| HyperOS | The dialog activity is `MiuiMMIDialogActivity`, still `com.android.phone`; progress text "USSD code running…" in `com.android.phone:id/message`; buttons are stock `android:id/button1/2`. The PIN box isn't marked password | Nothing needed |
| HyperOS | adb can't `input tap` (`INJECT_EVENTS`) or `settings put secure` (`WRITE_SECURE_SETTINGS`) unless Developer options › "USB debugging (Security settings)" is on, which needs a Mi account. `app_process` UiAutomation works | Walk with `--es walk balance|cancel`; the volunteer toggles Accessibility by hand |
| Xiaomi (Redmi K20 Pro, MIUI 12.5 China ROM, Android 11) | Hands apps an empty SIM list and refuses `getSubscriptionId(PhoneAccountHandle)`, with READ_PHONE_STATE granted and its app-op allowed in every list. Slot calls still work: `getSimState`, `getSubscriptionIds(slot)`, and the SIM accounts' `android.telecom.extra.SORT_ORDER` (= slot) | `Dialer.simsBySlot`, `Dialer.accountBySlot` |
| MIUI 12.5 | Same dialog layout as HyperOS (`ussd_message` under a "Carrier info" `miui:id/alertTitle`), in `MiuiMMIDialogActivity` | `Prompt.MESSAGE_IDS` |
| MIUI | Turning on any accessibility service shows a "Danger" sheet with a forced countdown before OK | Nothing; tell the volunteer to wait and tap OK |
| MIUI China ROM | adb installs are blocked ("Blocked installation via USB") until Install via USB is on and the on-screen prompt is accepted | Runbook step 1 |
| Any phone with a VoIP app (seen: ZoiPer) | ACTION_CALL gets a "Complete action using" chooser | `Dialer.systemCaller` |
| Samsung (Galaxy A13 SM-A135F, Android 14, One UI 6.1) | bKash's text is in `com.android.phone:id/message`, with no title. Buttons are stock `android:id/button1/2` in a `sem_buttonBarLayout`; progress text "USSD code running…" in `android:id/message`. The PIN box isn't marked password. adb can grant permissions and `settings put secure` | `Prompt.MESSAGE_IDS` |
| Any phone | A UiAutomation connection unbinds every accessibility service unless it asks not to (TalkBack too) | `UiHelper.connect()` passes `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` |
| Android 13+ (any maker), sideloaded APK | The accessibility switch is greyed out until App info → ⋮ → Allow restricted settings | Setup's Accessibility guide, first page |
| Motorola (edge 60 fusion, Android 17) | `settings put secure enabled_accessibility_services` from adb was wiped back to `null` twice (once right after install, once ~30 s into setup; no log line says why). The third try, after `cmd appops set … ACCESS_RESTRICTED_SETTINGS allow`, stuck | Runbook: re-read the setting a few seconds after setting it, and again before walking |
| Android 17 (seen on Motorola) | `TelecomManager.getPhoneAccount(h).label` and `.extras` throw without READ_PHONE_NUMBERS, so the `SORT_ORDER` slot match in `Dialer.accountBySlot` can't work there | Fine while the SIM list works (it did on this phone); only matters if an Android 17 phone also hides SIMs like MIUI 12.5 |
| Android Go (`ro.config.low_ram=true`) | No "display over other apps" | Not built yet. Accessibility's overlay may be the way; verify on a Go phone |

The bKash app's accessibility check: on OnePlus with bKash 7.4.0, the app reached its login screen
with a third-party accessibility service on and showed no warning (2026-11). That hasn't been checked
after logging in or on other phones. Record what you see; don't log in to anyone's bKash.

## Known phones

| Maker / model | Android / skin | Date | Guided | Accessibility | Shizuku | Root | Notes |
|---|---|---|---|---|---|---|---|
| OnePlus CPH2767 | 16 / OxygenOS | 2026-11-23 | ✓ | ✓ walked to PIN, Cancel ok | ✓ | ✓ | Maintainer's phone |
| Redmi K20 Pro (raphael) | 11 / MIUI 12.5 China | 2026-11-23 | not tried | ✓ walked to PIN, Cancel ok (after the SIM and chooser fixes) | not installed (helper works from adb) | — | Two Grameenphone SIMs, SIM 1 chosen; ZoiPer installed; build is `test-keys` |
| Samsung Galaxy A13 (SM-A135F) | 14 / One UI 6.1 | 2026-11-23 | not tried | ✓ walked to PIN, Cancel ok | not installed (release helper works from adb) | — | SIM 2 (`sim=2`); no overlay permission, so the cover was the accessibility overlay |
| Motorola edge 60 fusion (scout) | 17 / Hello UI | 2026-11-23 | not tried | ✓ dialled and handled bKash's reply; SIM 2 (Grameenphone) has no bKash account, so no PIN prompt; SIM 1 (Banglalink) not tried | not installed | — | ZoiPer installed: dial went straight to `UserCallActivity`, no chooser. Telecom placed it on the chosen SIM (sub 3, Grameenphone). bKash apps installed (untouched). Status bar stayed visible over the accessibility cover |
| Redmi 10C (fog, 220333QAG) | 13 / MIUI 14 Global | 2026-11-23 | not tried | ✓ walked to PIN, Cancel ok | not installed | — | SIM 1 Robi has no bKash account (bKash's "download bKash App" notice came through as a failure, nothing sent); SIM 2 Banglalink walked. MIUI 14 lists both SIMs normally. No overlay permission; the status bar stayed visible over the accessibility cover |
| REDMI Turbo 4 Pro (25053RT47C) | 17 / HyperOS 4 | 2026-11-23 | not tried | ✓ walked to PIN, Cancel ok (after the `ussd_message` fix) | not installed (helper works from adb) | — | SIM 2 (`sim=1`); no overlay permission, so the cover was the accessibility overlay |
| vivo V2445A | 15 / OriginOS 5 | 2026-11-12 | ✓ | not yet | ✓ walked to PIN, Cancel ok | — | SIM 2 Banglalink |

---

## Project rules (from the maintainer's notes)

Follow these in this repo, not just on volunteers' phones.

- **Test number `01302277488`** for demos, screenshots and USSD tests. Never show a real person's
  number in a screenshot, commit or report. When reading anything from bKash's screen, mask the digits.
- **Commit and push without being asked** once work is finished and the tests pass:
  - group related changes into separate commits;
  - push `main` to `origin`;
  - never commit half-done work;
  - `dist/` stays gitignored, and so does anything with secrets.
- **Commit messages end with** `Co-Authored-By: <the model you are> <noreply@anthropic.com>`. The
  maintainer's sessions use `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **PR descriptions end with** `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **No transaction history, and no list of saved payees.** It's a deliberate product decision; don't
  add one.
- **The PIN is only ever typed by the user**, on Chalaiden's PIN pad. Chalaiden never stores it or
  fills it in.
- **Don't run the Impeccable skill's v4.8.1 update** unless the maintainer asks.
- **Keep personal information private.** Don't put the maintainer's or volunteers' names, numbers or
  emails in requests to outside services, in commits, or in reports.
- **The maintainer's own phones:**
  - OnePlus CPH2767 (Android 16) is their personal phone and in daily use. Check what's in front before
    sending input; don't launch bKash; don't leave settings changed; hand it back on the T3 app
    (`am start -n com.t3tools.t3code/.MainActivity`). `adb install -r` kills a running app.
  - The vivo V2445A test phone uses SIM 2 (Banglalink) for bKash.
- **Design and code style:**
  - Comments say what a phone does and why the code is the way it is, with model and date for vendor
    facts.
  - UI copy is plain and short and talks to the user ("you").
  - Behaviour flags live in `PhoneQuirks`; brand checks live only in `PhoneQuirks.of()`.

---

## Report template

Save as `docs/vendor-reports/<maker>-<model>-<yyyy-mm-dd>.md`. Mask all digits of 4 or more.

```markdown
# <Maker> <Model> — <yyyy-mm-dd>

- Android <release> (SDK <n>), skin <name + version>, build <ro.build.display.id>
- Locale <persist.sys.locale>, low RAM <true/false>
- Phone app <com.android.phone versionName>; bKash SIM: slot <1/2>, operator <name>
- bKash app installed: <yes/no, version>; Shizuku: <yes/no>; rooted: <yes/no>

## Raw dialog
- Window package: <…>; focus line: <…>
- Message id: <…>; reply box: <class, id>; PIN box password: <yes/no>
- Buttons: <ids, clickable?, wrapper?>
- Menu line format: <e.g. "1.Send Money">
- Progress text: <exact text, id>
- SIM picker: <none / package + ids>
- Helper look: <json>; helper press button2: <ok?>

## Methods
| Method | Result | driver.log excerpt |
|---|---|---|
| Guided | | |
| Accessibility | | |
| Shizuku | | |
| Root | | |

- Restricted settings on this phone: <greyed out? where is "Allow restricted settings">
- bKash app warnings seen: <none / exact text>

## Quirks found
- <behaviour> → <proposed fix location>

## Restored
- [ ] accessibility services  - [ ] keyboards  - [ ] overlay appop  - [ ] Chalaiden uninstalled / kept
```
