---
name: android-platform-pitfalls
description: Known Android 10–17 restrictions and working fallbacks for system-near features — overlays and edge panels, accessibility services, clipboard in the background, Extra Dim / secure settings, split screen, quick settings tiles, keyboard focus in overlays, backup of private data. Use before implementing or debugging such a feature, or when a feature "does nothing" or crashes only on newer Android versions.
---

# Android platform pitfalls (and what worked)

Always check the user's Android version first. Code paths must degrade gracefully: try → catch
`SecurityException`/`Exception` → fall back (open the relevant settings page, show a toast).
Never let a privileged call crash a service.

| Feature | Restriction | Working approach |
|---|---|---|
| **Overlay windows** | `TYPE_APPLICATION_OVERLAY` needs "Display over other apps"; cannot draw over the notch/status bar area | For notch/status-bar gestures use `TYPE_ACCESSIBILITY_OVERLAY` from an `AccessibilityService`; hide it in landscape and on the lock screen |
| **Clipboard history** | Since Android 10 only the focused app or the IME can read the clipboard | Read in listener while the overlay has focus; with Shizuku/root: `appops set <pkg> READ_CLIPBOARD allow`. Skip clips flagged `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) and mark own sensitive copies (IBAN, numbers) with it |
| **Extra Dim** (`reduce_bright_colors_activated`) | `Settings.Secure` key is not readable for apps on Android 12+ → `SecurityException` crash | Read state via hidden `ColorDisplayManager.isReduceBrightColorsActivated` (HiddenApiBypass), write with `WRITE_SECURE_SETTINGS` (granted via adb/Shizuku); otherwise open the accessibility settings |
| **Split screen** | Android 12+ removed windowing modes 3/4; `FLAG_ACTIVITY_LAUNCH_ADJACENT` alone works only if a split already exists | With Shizuku/root: `dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage <taskId> <position>` (task id from `dumpsys activity recents`); else `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` if offered; else explain via toast. Must be verified on the device — OEMs and versions differ |
| **Quick settings tile** | API 34: `startActivityAndCollapse(Intent)` throws | Use the `PendingIntent` overload on 34+ |
| **Do-not-disturb / modes** | Bedtime/Driving/Focus modes have no public API | Detect only via `NotificationManager.currentInterruptionFilter != INTERRUPTION_FILTER_ALL`; tell the user modes without interruption filtering are not detectable |
| **Contacts** | `READ_CONTACTS` is a dangerous permission | Use the system picker (`ACTION_PICK` on `Phone.CONTENT_URI`) — it grants one row without the permission; request `READ_CONTACTS` only for bulk import. `ACTION_DIAL` instead of `ACTION_CALL` unless the user enables direct calls |
| **Keyboard in overlays** | Closing a view with a focused `EditText` leaves the IME open | `imm.hideSoftInputFromWindow(editText.windowToken, 0)`, then move focus to the root view, before removing the view |
| **Root checks** | `su` may prompt or block forever (and exists on CI runners) | Run with timeout (`waitFor(2, SECONDS)`), close stdin, destroy on timeout |
| **Backups** | Auto Backup copies SharedPreferences to the cloud | Exclude private data (clipboard, contacts, saved texts) in `backup_rules.xml` **and** `data_extraction_rules.xml` (Android 12+) |
| **Runtime broadcast receivers** | Android 14+ (targetSdk 34+) requires an export flag; `RECEIVER_EXPORTED` for the app's own actions lets any app trigger them | `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` (also below API 33); system broadcasts and the app's own PendingIntents still arrive. See `android-security-review` |
| **Hardware features** | `CALL_PHONE`/camera permissions make Play/F-Droid treat telephony/camera as required | Declare `<uses-feature android:required="false">` for telephony and camera |

## Touch handling in edge handles

- Do not wait for a double-tap timeout if no double-tap action is configured — fire the single
  tap immediately.
- Reset tap counters on every new `ACTION_DOWN` that starts a slide/drag, and drop interrupted
  sequences; otherwise stray taps trigger later.
- Callbacks created once (e.g. per handle) must read preferences live, not capture old values.

## Data protection note

Features like clipboard history and favorite contacts process personal data on the device.
Keep it local (no network permission use, backup exclusions) and do not put real personal data
into tests or chats — use placeholders like `DE00 0000 ...`.
