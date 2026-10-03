---
name: android-github-ci
description: Set up, monitor and debug GitHub Actions workflows that build an Android APK on every push (fixed key from a repository secret, downloadable artifact, unit tests) and publish signed releases with checksum and certificate fingerprint. Use when the user asks "wie lade ich die APK herunter", wants automatic builds, when a CI run fails or hangs, or when checking CI status through the GitHub MCP tools (no gh CLI in cloud sessions).
---

# Android CI on GitHub Actions

## Workflow pattern (`.github/workflows/build-debug.yml`)

```yaml
on:
  push: { branches-ignore: ['dependabot/**'] }
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }          # full history: versionCode = commit count
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin', cache: gradle }
      - name: Decode dev signing key
        env: { DEV_KEYSTORE_BASE64: '${{ secrets.DEV_KEYSTORE_BASE64 }}' }
        run: |
          if [ -n "$DEV_KEYSTORE_BASE64" ]; then
            mkdir -p app/signing
            echo "$DEV_KEYSTORE_BASE64" | base64 --decode > app/signing/dev.keystore
          else
            echo "::warning::DEV_KEYSTORE_BASE64 not set - APK cannot update earlier builds."
          fi
      - run: chmod +x gradlew && ./gradlew assembleDebug --stacktrace
      - uses: actions/upload-artifact@v4
        with: { name: app-debug-apk, path: app/build/outputs/apk/debug/*.apk, retention-days: 14 }
      - name: Run unit tests              # after the upload: a slow test never blocks the APK
        timeout-minutes: 20
        run: ./gradlew testDebugUnitTest --stacktrace
```

Design rules that paid off:
- **Upload before tests**, so the user gets an APK even when a test fails.
- **`timeout-minutes`** on test steps; the default job timeout is 6 hours.
- Secrets never in logs: decode to a gitignored path; print only whether the secret exists.
- Fork PRs do not receive secrets — the warning branch keeps those builds working.
- `actions/setup-java` `distribution` must match a pinned daemon JDK
  (`gradle/gradle-daemon-jvm.properties`, e.g. `corretto`), otherwise Gradle downloads it again.
- `gradle/actions/setup-gradle` also validates `gradle-wrapper.jar` against official checksums.

## Release workflow (`.github/workflows/release.yml`)

Manual (`workflow_dispatch`, `if: github.ref == 'refs/heads/main'`), job-level
`permissions: contents: write`, everything else `contents: read`:

1. Checkout with `fetch-depth: 0`; `setup-gradle` with `cache-read-only: true` (a release never
   reuses cache entries written by other branches).
2. Decode `RELEASE_KEYSTORE_BASE64` to `$RUNNER_TEMP/release.keystore`, export
   `RELEASE_STORE_FILE`; fail with `::error::` if the secret is missing (otherwise the APK is
   unsigned). Passwords only as step `env` from secrets.
3. `./gradlew assembleRelease`, then verify: `apksigner verify --print-certs` (certificate
   SHA-256) and `aapt2 dump badging` (versionName/Code) from the newest
   `$ANDROID_HOME/build-tools/*`; rename to `<app>-<versionName>.apk`, write `.sha256`.
4. Publish with the preinstalled `gh` CLI instead of third-party release actions:
   `gh release create "v$VERSION" app.apk app.apk.sha256 --target "$GITHUB_SHA" --notes-file notes.md`
   (`GH_TOKEN: ${{ github.token }}`), notes with certificate fingerprint, APK hash and
   `git log` since the previous release tag. Refuse if the tag exists.

Test the shell of the verify step locally against a locally built release APK (throwaway key,
`GITHUB_OUTPUT=<file>`) before the user runs it for the first time.

## Forks

- **Actions are disabled on forks** until the owner enables them (repository → Actions). Via the
  GitHub MCP tools this shows as `list_workflows` → `total_count: 0` even though workflow files
  exist; tell the user, do not try to "fix" the workflows.
- Remove or disable upstream workflows that target the upstream owner's infrastructure
  (notifications with hard-coded chat IDs, `pull_request_target`, bots that push from comments)
  and replace `CODEOWNERS`.
- Secrets are per repository: the user creates them (Settings → Secrets and variables → Actions);
  never ask them to paste key material into the chat.

## Telling the user how to download

Repository → **Actions** → select the run for the commit → section **Artifacts** →
download the zip (contains the APK; GitHub login required, kept 14 days).
On the phone: unzip, open the APK, allow "install unknown apps" once for the file manager.

## Monitoring from a cloud session (GitHub MCP tools)

- Load tools via ToolSearch: `mcp__github__actions_list`, `mcp__github__actions_get`,
  `mcp__github__get_job_logs`, `mcp__github__actions_run_trigger`.
- List runs for the branch → check `status`/`conclusion` of the run whose `head_sha` matches
  `git rev-parse HEAD`.
- On failure, fetch the failed job's logs and search for the first `FAILED`/`error:`; reproduce
  locally (`./gradlew <same task>`) before pushing a fix.
- A run stuck far beyond its usual duration: find the hanging step in the logs (no output for
  minutes), cancel the run, fix the root cause (e.g. a process without timeout). Never "fix"
  by disabling or skipping tests.
- Do not poll with `sleep`; check again on the next turn or schedule a check-in.

## Common failures

| Log | Cause | Fix |
|---|---|---|
| `SDK location not found` | only locally | see `android-cloud-setup` |
| Lint `MissingTranslation` / `abortOnError` | new string without translation | see `android-translation` |
| versionCode lower than expected | shallow checkout | `fetch-depth: 0` |
| Tests run > 20 min, no output | blocking subprocess (e.g. `su` exists on runners) | timeouts on `waitFor`, close stdin |
