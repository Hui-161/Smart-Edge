#!/usr/bin/env bash
# Installs a minimal Android SDK for command-line builds. Idempotent.
# Usage: setup-android-sdk.sh [compileSdk]   (default 34)
set -euo pipefail

API="${1:-34}"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing cmdline-tools into $SDK"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/clt.zip" "$CLT_URL"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q -o "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

need=()
[ -d "$SDK/platforms/android-$API" ] || need+=("platforms;android-$API")
[ -d "$SDK/build-tools/$API.0.0" ]   || need+=("build-tools;$API.0.0")
[ -d "$SDK/platform-tools" ]         || need+=("platform-tools")
if [ ${#need[@]} -gt 0 ]; then
  echo "Installing: ${need[*]}"
  "$SDKMANAGER" --sdk_root="$SDK" "${need[@]}" >/dev/null
fi

# local.properties is gitignored in Android projects
echo "sdk.dir=$SDK" > "$REPO_ROOT/local.properties"

# Maven Central rate-limits shared cloud IPs (HTTP 429): use Google's mirror locally
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/central-mirror.gradle" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def redirect = { repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        if (r.url.toString().startsWith('https://repo.maven.apache.org') || r.url.toString().startsWith('https://repo1.maven.org')) r.url = mirror
    }
}
settingsEvaluated { s -> redirect(s.pluginManagement.repositories); redirect(s.dependencyResolutionManagement.repositories) }
allprojects { redirect(repositories); buildscript { redirect(repositories) } }
// Robolectric downloads android-all jars itself: use the same mirror (local environment only)
allprojects { tasks.withType(Test).configureEach { systemProperty 'robolectric.dependency.repo.url', mirror } }
GRADLE

if [ "$(git -C "$REPO_ROOT" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
  echo "WARNING: shallow clone - run 'git fetch --unshallow' before building (commit-count versions)."
fi
echo "Android SDK ready: $SDK (API $API)"
