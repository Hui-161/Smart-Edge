#!/usr/bin/env bash
# Installs a minimal Android SDK (and, if needed, the Gradle distribution) for command-line
# builds in a cloud container. Idempotent.
# Usage: setup-android-sdk.sh [compileSdk]   e.g. 34, 36.1 (default: read from app/build.gradle[.kts])
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
MIRROR="https://maven-central.storage-download.googleapis.com/maven2/"

# --- compileSdk (and compileSdkMinor, e.g. 37 + 1 -> platform android-37.1) -----------------
API="${1:-}"
if [ -z "$API" ]; then
  BUILD_FILE="$(ls "$REPO_ROOT"/app/build.gradle.kts "$REPO_ROOT"/app/build.gradle 2>/dev/null | head -1 || true)"
  if [ -n "$BUILD_FILE" ]; then
    major="$(sed -nE 's/^\s*compileSdk\s*=?\s*([0-9]+).*/\1/p' "$BUILD_FILE" | head -1)"
    minor="$(sed -nE 's/^\s*compileSdkMinor\s*=?\s*([0-9]+).*/\1/p' "$BUILD_FILE" | head -1)"
    API="${major:-34}${minor:+.$minor}"
  fi
fi
API="${API:-34}"
MAJOR="${API%%.*}"

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

# Platform names differ by API level: android-34, android-36.1, android-37.0 ...
available="$("$SDKMANAGER" --sdk_root="$SDK" --list 2>/dev/null | awk '{print $1}')"
platform=""
for candidate in "$API" "$MAJOR" "$MAJOR.0"; do
  if grep -qx "platforms;android-$candidate" <<<"$available"; then platform="android-$candidate"; break; fi
done
[ -n "$platform" ] || { echo "No SDK platform found for compileSdk $API"; exit 1; }
build_tools="$MAJOR.0.0"
grep -qx "build-tools;$build_tools" <<<"$available" ||
  build_tools="$(grep -E '^build-tools;[0-9.]+$' <<<"$available" | sed 's/build-tools;//' | sort -V | tail -1)"

need=()
[ -d "$SDK/platforms/$platform" ]       || need+=("platforms;$platform")
[ -d "$SDK/build-tools/$build_tools" ]  || need+=("build-tools;$build_tools")
[ -d "$SDK/platform-tools" ]            || need+=("platform-tools")
if [ ${#need[@]} -gt 0 ]; then
  echo "Installing: ${need[*]}"
  "$SDKMANAGER" --sdk_root="$SDK" "${need[@]}" >/dev/null
fi

# local.properties is gitignored in Android projects
echo "sdk.dir=$SDK" > "$REPO_ROOT/local.properties"

# --- Maven Central mirror (shared cloud IPs get HTTP 429) - local environment only -----------
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/central-mirror.gradle" <<GRADLE
def mirror = '$MIRROR'
def redirect = { repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        if (r.url.toString().startsWith('https://repo.maven.apache.org') || r.url.toString().startsWith('https://repo1.maven.org')) r.url = mirror
    }
}
// Plugins in the settings plugins {} block (e.g. foojay-resolver) resolve before settingsEvaluated
beforeSettings { s -> s.pluginManagement.repositories { maven { url = mirror } } }
settingsEvaluated { s -> redirect(s.pluginManagement.repositories); redirect(s.dependencyResolutionManagement.repositories) }
allprojects { redirect(repositories); buildscript { redirect(repositories) } }
// Robolectric downloads android-all jars itself: use the same mirror
allprojects { tasks.withType(Test).configureEach { systemProperty 'robolectric.dependency.repo.url', mirror } }
GRADLE

# --- Gradle distribution: services.gradle.org is not always reachable from the container ----
PROPS="$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$PROPS" ]; then
  url="$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed 's/\\:/:/g')"
  zip_name="$(basename "$url")"                     # gradle-9.5.0-bin.zip
  dist_name="${zip_name%.zip}"                      # gradle-9.5.0-bin
  version="$(sed -E 's/^gradle-([^-]+)-(bin|all)$/\1/' <<<"$dist_name")"
  hash="$(python3 -c 'import hashlib,sys
n=int.from_bytes(hashlib.md5(sys.argv[1].encode()).digest(),"big"); d="0123456789abcdefghijklmnopqrstuvwxyz"; s=""
while n: n,r=divmod(n,36); s=d[r]+s
print(s)' "$url")"
  dist_dir="$HOME/.gradle/wrapper/dists/$dist_name/$hash"
  if [ ! -f "$dist_dir/$zip_name.ok" ] && ! curl -fsSIL --max-time 15 -o /dev/null "$url"; then
    echo "services.gradle.org unreachable - fetching $zip_name from github.com/gradle/gradle-distributions"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/$zip_name" "https://github.com/gradle/gradle-distributions/releases/download/v$version/$zip_name"
    expected="$(sed -n 's/^distributionSha256Sum=//p' "$PROPS")"
    if [ -n "$expected" ] && [ "$(sha256sum "$tmp/$zip_name" | cut -d' ' -f1)" != "$expected" ]; then
      echo "ERROR: $zip_name does not match distributionSha256Sum"; rm -rf "$tmp"; exit 1
    fi
    mkdir -p "$dist_dir"
    unzip -q -o "$tmp/$zip_name" -d "$dist_dir"
    touch "$dist_dir/$zip_name.ok"
    rm -rf "$tmp"
  fi
  # Let the wrapper download/unpack the distribution if it can reach it itself
  if [ ! -f "$dist_dir/$zip_name.ok" ] && [ -x "$REPO_ROOT/gradlew" ]; then
    (cd "$REPO_ROOT" && timeout 600 ./gradlew --version >/dev/null 2>&1) || echo "WARNING: ./gradlew could not fetch $zip_name"
  fi
  # The committed wrapper jar must be the one this Gradle version generates
  jar="$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar"
  gradle_bin="$(ls -d "$dist_dir"/gradle-*/bin/gradle 2>/dev/null | head -1 || true)"
  if [ -f "$jar" ] && [ -n "$gradle_bin" ]; then
    tmp="$(mktemp -d)"
    echo 'rootProject.name = "wrapper-check"' > "$tmp/settings.gradle.kts"
    if (cd "$tmp" && timeout 300 "$gradle_bin" wrapper -q --no-daemon >/dev/null 2>&1) &&
      [ "$(sha256sum < "$tmp/gradle/wrapper/gradle-wrapper.jar")" = "$(sha256sum < "$jar")" ]; then
      echo "gradle-wrapper.jar matches the official Gradle $version wrapper"
    else
      echo "WARNING: gradle-wrapper.jar differs from the Gradle $version wrapper - inspect it before building"
    fi
    rm -rf "$tmp"
  fi
fi

if [ "$(git -C "$REPO_ROOT" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
  echo "WARNING: shallow clone - run 'git fetch --unshallow' before building (commit-count versions)."
fi
if [ -f "$REPO_ROOT/gradle/gradle-daemon-jvm.properties" ]; then
  echo "Note: gradle/gradle-daemon-jvm.properties pins the daemon JDK; if it cannot be downloaded, build with scripts/gradle-local.sh."
fi
echo "Android SDK ready: $SDK ($platform, build-tools $build_tools)"
