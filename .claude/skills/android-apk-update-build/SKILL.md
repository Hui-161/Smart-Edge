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

Check how `versionCode` is set in `app/build.gradle.kts`. A hand-maintained number is easy to
forget; the robust pattern (offer it if the project has none) derives it from git:

```kotlin
val gitCommitCount: Int = try {
    val shallow = providers.exec { commandLine("git", "rev-parse", "--is-shallow-repository") }
        .standardOutput.asText.get().trim()
    if (shallow == "true") throw GradleException("Shallow git clone: run 'git fetch --unshallow'")
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (e: GradleException) { throw e } catch (e: Exception) { 0 }
// defaultConfig: versionCode = 1000 + gitCommitCount; versionName = "1.0.$gitCommitCount"
```

Keep the offset above the app's last released versionCode.

**Trap:** a shallow clone (cloud sessions, `actions/checkout` default) counts only a few
commits → a *lower* version than the user's installed build ("downgrade" dialog). Fix:

```bash
git fetch --unshallow   # in CI: actions/checkout with fetch-depth: 0
```

## 2. Fixed dev signing key

Debug keys are generated per machine, so cloud and CI builds would each be signed differently.
Use one dev key instead:

- Stored as GitHub secret `DEV_KEYSTORE_BASE64` (base64 of a PKCS12/JKS keystore).
- CI decodes it to `app/signing/dev.keystore` (gitignored, **never commit**).
- The `dev` signingConfig is used for `debug` when that file exists:

```kotlin
val devKeystore = file("signing/dev.keystore")
if (devKeystore.exists()) signingConfigs { create("dev") {
    storeFile = devKeystore; storePassword = "android"
    keyAlias = "androiddebugkey"; keyPassword = "android"
} }
buildTypes { debug { signingConfigs.findByName("dev")?.let { signingConfig = it } } }
```

Add `app/signing/` and `*.keystore` to `.gitignore`.

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
bash <skill-dir>/scripts/build-and-verify.sh   # <skill-dir> = folder of this SKILL.md; run from the repo
# project pins a daemon JDK that cannot be downloaded here (see android-cloud-setup):
GRADLE=<android-cloud-setup-dir>/scripts/gradle-local.sh bash <skill-dir>/scripts/build-and-verify.sh
```

The script builds `assembleDebug`, prints `versionCode`/`versionName` (aapt) and the signing
certificate SHA-256 (apksigner), and copies the APK to the scratchpad as
`<app>-<versionName>.apk`. Before sending, check:

- the certificate SHA-256 matches the previous delivery (note it in the reply),
- the versionCode is higher than what the user has installed (ask for or read it from a
  screenshot of the install dialog if in doubt).

Then send the file with `SendUserFile` and state: version, "installs as update", what changed.
If a CI build exists for the same commit, its artifact is equivalent (same key, same count).

## 4. Signed releases next to debug builds

When the user publishes releases (own key, e.g. a fork of someone else's app):

- **Release key** only from CI secrets or a local, gitignored `keystore.properties`; never in the
  repo. In Kotlin DSL `java.util.Properties()` does not resolve (`java` is the Java extension):
  `import java.util.Properties` at the top.

  ```kotlin
  fun signingValue(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: keystoreProperties?.getProperty(name)
  val releaseStoreFile = signingValue("RELEASE_STORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
  // signingConfigs { if (releaseStoreFile != null) create("release") { ... RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD } }
  ```

- **Debug builds get `applicationIdSuffix = ".debug"`** (plus `versionNameSuffix = "-debug"`) and
  the dev key: they install next to the release app and can never replace or block it. Custom
  `<permission>` definitions with fixed names would make the second install fail
  (`INSTALL_FAILED_DUPLICATE_PERMISSION`): remove them for debug in
  `app/src/debug/AndroidManifest.xml` with `tools:node="remove"`. Check the merged manifest
  (`app/build/intermediates/merged_manifests/<variant>/.../AndroidManifest.xml`).
- **Version:** `versionCode` = commit count, `versionName` carries it too (e.g.
  `<upstream>-fork.<count>`), so release tags (`v<versionName>`) are unambiguous and an updater
  can compare the build number instead of parsing version names.
- **Fork of an app with the same `applicationId`:** the user must uninstall the original once
  (different certificate) — export settings first. Say so before the first install.
- **In-app updater:** download only from the project's own release URLs (https), then install only
  if `getPackageArchiveInfo(apk, GET_SIGNING_CERTIFICATES)` has the app's package name and exactly
  the `signingInfo.apkContentsSigners` of the installed app. Never install from a URL that a
  server response names without such a check.
- Build the signed release in CI (skill `android-github-ci`, release workflow), not in a cloud
  session: the release key must not be copied into the container. For a local test of the
  signing config use a throwaway key in the scratchpad and delete it afterwards.
