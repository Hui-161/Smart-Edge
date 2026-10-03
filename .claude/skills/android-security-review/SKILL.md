---
name: android-security-review
description: Review an Android app that holds powerful permissions (accessibility service, notification listener, IME, Shizuku/root shell, WRITE_SECURE_SETTINGS, device admin, install packages) for security and privacy problems, fix them and document the result. Use when the user asks to check an app or a fork "auf Sicherheit", before self-releasing someone else's app, when reviewing upstream merges, or when adding exported components, shell commands, network calls or telemetry. German triggers are "Sicherheitsprüfung", "prüfe aus Sicherheitsbedenken", "ist die App sicher".
---

# Security review of a privileged Android app

The core risk of such apps is **privilege escalation through the app** ("confused deputy"):
another installed app, an imported file or a web page makes it use its permissions. Second:
data leaving the device (telemetry, updaters, remote content), third: sensitive data in logs,
backups and storage.

## 1. Inventory (read-only)

```bash
python3 <skill-dir>/scripts/attack-surface.py . [--apk app/build/outputs/apk/release/app-release.apk]
```

Lists exported components (`OPEN` = reachable without permission), custom permissions with
protection level, manifest entries without a class, runtime receivers registered as exported,
shell strings with interpolated values and hard-coded endpoints. Build first
(`android-cloud-setup`) so the merged manifest and generated classes are included.

For a large code base split the reading into parallel areas (subagents): exported components/IPC,
privileged execution (shell, hidden APIs), network/data egress, sensitive data handling
(IME, accessibility, notifications, storage, backups). Ask for file:line, attacker
precondition, impact and fix for each finding, plus a list of what was checked and found safe.
Verify every reported finding yourself in the code before acting on it; if a delegated review
stops early, cover its area yourself.

## 2. What to look for (findings that were real)

| Area | Pattern | Fix |
|---|---|---|
| Runtime receivers | `registerReceiver(..., RECEIVER_EXPORTED)` (or no flag below API 33) for the app's own actions: any app can trigger them (freeze apps, "authenticated" for App Lock, remap actions) | `ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)` after checking that all senders are in-app or PendingIntents; system broadcasts still arrive |
| Shell strings | Shizuku runs `sh -c "<string>"`, root writes to `su` stdin or `su -c "a b c"`: values from prefs, imports, intents, Wear messages are shell syntax | Validate (package `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`, settings key `^[A-Za-z0-9_.:-]{1,256}$`) and quote every value: `"'" + v.replace("'", "'\\''") + "'"`; prefer Settings/Binder APIs or argv arrays |
| Root helper | `waitFor()` without timeout on `su` blocks forever on a root-manager prompt | `waitFor(n, SECONDS)`, `destroy()` on timeout, close stdin |
| Custom permissions | `protectionLevel="dangerous"` = one runtime dialog for any app; a provider/receiver behind it exposing *all* preferences leaks tokens and lets apps write JSON that later reaches the shell | `signature`/`knownSigner`, or an allowlist (e.g. only Boolean feature switches, no protection switches), never secrets; no coordinates or other personal data |
| Exported activities | Internal dialogs/shortcut targets with `exported="true"` perform actions on launch (disable ADB, unfreeze apps, full-screen alarm) | `exported="false"` when started only in-app, by PendingIntents or pinned ShortcutManager shortcuts; keep launcher/system entry points (CREATE_SHORTCUT, QS_TILE_PREFERENCES, LAUNCHER aliases) and validate their extras |
| Phantom components | Exported manifest entry whose class does not exist (e.g. from an old library): an explicit broadcast crashes the app | Remove the entry |
| WebView | `intent:` URIs: `component = null` but not `selector = null`; opened without user gesture; mixed content | `selector = null`, `request.hasGesture()`, `MIXED_CONTENT_NEVER_ALLOW`, no `addJavascriptInterface` |
| Broadcast receivers for system events | Exported for the system but action not checked: fake `PHONE_STATE` etc. via explicit broadcast | `if (intent.action != EXPECTED) return` |
| Keyboard (IME) | Logs clipboard; learns words in password fields; ignores `IME_FLAG_NO_PERSONALIZED_LEARNING`; clipboard panel on the lock screen; undo across apps | Check `inputType` password variations and the flag in `onStartInputView`; hide clipboard when `isKeyguardLocked`; clear undo per field |
| Logs | Clipboard, numbers, contact names, notification text, coordinates, SSIDs, URLs, API bodies in `Log.d` | Remove the values; R8 in release: `-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); public static int i(...); }` |
| Backups | `allowBackup="true"` with template rules | Exclude learned words, crash logs, secrets in `backup_rules.xml` and `data_extraction_rules.xml` (cloud-backup **and** device-transfer) |
| Telemetry | Crash reporting enabled by default, initialised before consent, sending to the original author's project (fork) | Remove, or own DSN and opt-in; check the APK's dex for the SDK afterwards (`unzip -p app.apk classes*.dex \| grep -ac 'Lio/sentry/'`) |
| Updater | APK URL from a server response, no hash/signature check, library from JitPack | Own releases only, https prefix check, install only if package name and signing certificates equal the installed app (see `android-apk-update-build`) |
| Remote content | Help media, wallpapers, "about" JSON, link previews fetched from a third party's server; link pre-fetch even with preview disabled | Decide with the user: keep, disable by default or remove; document what stays |
| CI / supply chain | `pull_request_target`, workflows that push from comments, hard-coded chat IDs, unpinned JitPack deps, wrapper jar not verified, no `distributionSha256Sum` | Remove upstream-specific workflows in a fork, verify the wrapper (`android-cloud-setup`), prefer Maven Central/Google deps |

Good signs worth stating too: accessibility service reads no window content
(`canRetrieveWindowContent=false`, no node text), notification listener stores no text,
`BIOMETRIC_STRONG`, immutable/explicit PendingIntents, broadcasts with `setPackage`.

## 3. Fix, verify, document

- Ask the user before decisions that change behaviour or identity: telemetry, updater source,
  `applicationId`, remote content. Fix clear vulnerabilities without asking.
- One topic per commit (receivers, shell, external interface, privacy, ...), messages explain
  the attack and the fix. Build debug **and** release (R8 can break reflection-based code),
  run unit tests, re-run `attack-surface.py` with `--apk` and inspect the merged manifest.
- Write `docs/SECURITY_REVIEW.md`: date, base commit, method (static review, no device test),
  table `# | severity | finding | status` including what stays open and why.
- Remind the user: results are proposals; test on a device before release; never paste keys or
  tokens into chats or reports; no real personal data in tests or the report.
