---
name: android-cloud-setup
description: Set up the Android SDK and Gradle in a Claude Code cloud container (no Android Studio, proxied network) so an Android app can be built and unit-tested. Use at the start of a session when `./gradlew` fails with "SDK location not found", when sdkmanager/platforms are missing, when Maven Central answers 429, or when the user asks to build or test the app here. German triggers: "Android SDK einrichten", "kannst du die App bauen".
---

# Android SDK in a cloud container

The container starts without an Android SDK. Run the bundled script; it is idempotent
(skips everything already installed):

```bash
bash .claude/skills/android-cloud-setup/scripts/setup-android-sdk.sh
```

It installs `cmdline-tools`, `platforms;android-<compileSdk>`, `build-tools;<compileSdk>.0.0`,
`platform-tools` into `$HOME/android-sdk`, accepts licenses and writes `local.properties`
(`sdk.dir=...`, gitignored). Read `compileSdk` from `app/build.gradle.kts` first; pass it as
argument if it is not 34: `setup-android-sdk.sh 35`.

## Network problems and fixes

| Symptom | Cause | Fix |
|---|---|---|
| `dl.google.com` / `maven.google.com` blocked (403 from proxy) | Domain not in the environment's allowlist | Ask the user to add `dl.google.com`, `maven.google.com` (and `*.googleapis.com`) to the environment network policy. Do not work around the proxy. |
| `429 Too Many Requests` from `repo.maven.apache.org` | Shared egress IP rate-limited by Maven Central | The script installs `~/.gradle/init.d/central-mirror.gradle`, redirecting Maven Central to Google's mirror `https://maven-central.storage-download.googleapis.com/maven2/` (also for Robolectric's android-all jars). Local only, never commit it. |
| TLS errors in Java/Gradle | Proxy CA not trusted | `JAVA_TOOL_OPTIONS` already points to the system trust store; check `curl -sS "$HTTPS_PROXY/__agentproxy/status"` and `/root/.ccr/README.md`. Never disable TLS checks. |

## Also check

- `git rev-parse --is-shallow-repository` → if `true`, run `git fetch --unshallow`
  (version numbers derived from the commit count would otherwise be too low, see
  `android-apk-update-build`).
- No KVM/GPU in the container: no emulator. Verify with Robolectric unit tests
  (`android-robolectric-tests`) and let the user test on the device.
- The first Gradle run downloads ~1–2 GB into `~/.gradle`; keep an eye on the disk allowance
  and delete the cmdline-tools zip afterwards (the script does).
