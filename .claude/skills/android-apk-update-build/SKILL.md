---
name: android-apk-update-build
description: Build a debug APK that installs as an update over the previous one (settings and permissions kept) and hand it to the user. Use whenever the user asks for an APK, a download, a new test build, or reports "App wird nicht installiert", "Downgrade", "muss neu installieren", "Einstellungen weg". Covers versionCode from the git commit count, the shallow-clone trap, the fixed dev signing key from a GitHub secret, and verifying the APK before sending it.
---

# Update-safe APK builds

Android installs a new APK **as an update** (data and permissions kept) only if all three hold:

1. same `applicationId`,
2. same signing certificate,
3. `versionCode` higher than the installed one.

Breaking 2 forces uninstall (all settings lost); breaking 3 shows a "downgrade" dialog.

## 1. Version number from the commit count

`app/build.gradle.kts` derives `versionCode = 1000 + git rev-list --count HEAD`
(`versionName = "x.y.z.<count>"`). Every commit therefore yields a higher version.

**Trap:** a shallow clone (cloud sessions, `actions/checkout` default) counts only a few
commits → a *lower* version than the user's installed build. The build refuses shallow clones;
fix it with:

```bash
git fetch --unshallow   # in CI: actions/checkout with fetch-depth: 0
```

## 2. Fixed dev signing key

Debug keys are generated per machine, so cloud and CI builds would each be signed differently.
The project uses one dev key instead:

- Stored as GitHub secret `DEV_KEYSTORE_BASE64` (base64 of a PKCS12/JKS keystore).
- CI decodes it to `app/signing/dev.keystore` (gitignored, **never commit**).
- The `dev` signingConfig is used for `debug` when that file exists.

Create a key once (only when the user wants one; they add the secret themselves under
Repository → Settings → Secrets and variables → Actions):

```bash
keytool -genkeypair -keystore dev.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dev"
base64 -w0 dev.keystore   # value for DEV_KEYSTORE_BASE64
```

Locally, restore it from a CI build or from a file the user provides. Remind the user that it is
a credential: share it only via the secret store and delete temporary copies afterwards.
If the user's device has an APK signed with a different key, one final uninstall is unavoidable;
say so clearly before they install.

## 3. Build, verify, deliver

```bash
bash .claude/skills/android-apk-update-build/scripts/build-and-verify.sh
```

The script builds `assembleDebug`, prints `versionCode`/`versionName` (aapt) and the signing
certificate SHA-256 (apksigner), and copies the APK to the scratchpad as
`<app>-<versionName>.apk`. Before sending, check:

- the certificate SHA-256 matches the previous delivery (note it in the reply),
- the versionCode is higher than what the user has installed (ask for or read it from a
  screenshot of the install dialog if in doubt).

Then send the file with `SendUserFile` and state: version, "installs as update", what changed.
If a CI build exists for the same commit, its artifact is equivalent (same key, same count).
