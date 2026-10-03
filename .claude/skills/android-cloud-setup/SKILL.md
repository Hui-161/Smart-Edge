---
name: android-cloud-setup
description: Set up the Android SDK and Gradle in a Claude Code cloud container (no Android Studio, proxied network) so an Android app can be built and unit-tested. Use at the start of a session when `./gradlew` fails with "SDK location not found", when sdkmanager/platforms are missing, when Maven Central answers 429, when the Gradle distribution, a JitPack dependency or the daemon JDK ("Unable to download toolchain", foojay) cannot be downloaded, or when the user asks to build or test the app here. German triggers are "Android SDK einrichten", "kannst du die App bauen".
---

# Android SDK in a cloud container

The container starts without an Android SDK. Run the bundled script; it is idempotent
(skips everything already installed):

```bash
bash <skill-dir>/scripts/setup-android-sdk.sh   # <skill-dir> = folder of this SKILL.md
```

It reads `compileSdk` (and `compileSdkMinor`, e.g. 37 + 1 → `android-37.1`) from
`app/build.gradle[.kts]`, or takes it as argument (`setup-android-sdk.sh 36.1`). It installs
`cmdline-tools`, the matching platform, `build-tools`, `platform-tools` into `$HOME/android-sdk`,
accepts licenses, writes `local.properties` (`sdk.dir=...`, gitignored), makes sure the Gradle
distribution of the wrapper is available and checks that the committed `gradle-wrapper.jar` is
byte-identical to the one that Gradle version generates (a tampered wrapper jar runs arbitrary
code on every build).

Then build with `./gradlew`, or with `bash <skill-dir>/scripts/gradle-local.sh <tasks>` when the
project pins a daemon JDK (see below).

## Network problems and fixes

| Symptom | Cause | Fix |
|---|---|---|
| `dl.google.com` / `maven.google.com` blocked (403 from proxy) | Domain not in the environment's allowlist | Ask the user to add `dl.google.com`, `maven.google.com` (and `*.googleapis.com`) to the environment network policy. Do not work around the proxy. |
| `429 Too Many Requests` from `repo.maven.apache.org` | Shared egress IP rate-limited by Maven Central | The script installs `~/.gradle/init.d/central-mirror.gradle`, redirecting Maven Central to Google's mirror `https://maven-central.storage-download.googleapis.com/maven2/` (also for settings plugins such as foojay-resolver via `beforeSettings`, and for Robolectric's android-all jars). Local only, never commit it. |
| `./gradlew` cannot download `gradle-X-bin.zip` (`services.gradle.org` / `downloads.gradle.org` 403) | Gradle download host blocked | The script fetches the same zip from `github.com/gradle/gradle-distributions` (Gradle's official mirror; `services.gradle.org` itself redirects there), checks `distributionSha256Sum` if the project sets one, and unpacks it into `~/.gradle/wrapper/dists/<name>/<hash>/` with the `.ok` marker, so `./gradlew` works unchanged. |
| `Unable to download toolchain matching ... vendor=...` (foojay 403) | `gradle/gradle-daemon-jvm.properties` pins a daemon JDK (e.g. Amazon Corretto) and the JDK hosts are blocked | `gradle-local.sh` hides that file for the run only and restores it afterwards, so the daemon uses the installed JDK (`GRADLE_LOCAL_JDK=1` forces this). Never commit the file's removal; check `git status` if a run was killed. In CI use the same vendor in `actions/setup-java` (`distribution: corretto`). |
| `Could not resolve com.github.*` from `jitpack.io` (403) | JitPack blocked | Ask the user to allow `jitpack.io`, or (better) replace the dependency: JitPack builds artifacts on demand from mutable git tags, a supply-chain risk. |
| TLS errors in Java/Gradle | Proxy CA not trusted | `JAVA_TOOL_OPTIONS` already points to the system trust store; check `curl -sS "$HTTPS_PROXY/__agentproxy/status"` and `/root/.ccr/README.md`. Never disable TLS checks. |

## Also check

- `git rev-parse --is-shallow-repository` → if `true`, run `git fetch --unshallow`
  (version numbers derived from the commit count would otherwise be too low, see
  `android-apk-update-build`). The script warns about it.
- Optional: make this automatic for a repo with a SessionStart hook (skill `session-start-hook`).
- No KVM/GPU in the container: no emulator. Verify with Robolectric unit tests
  (`android-robolectric-tests`) and let the user test on the device.
- The first Gradle run downloads ~1–2 GB into `~/.gradle`; keep an eye on the disk allowance
  and delete the cmdline-tools zip afterwards (the script does). Large apps (100k+ lines of
  Kotlin) need 5–10 minutes for a debug build and longer for R8: run builds in the background.
