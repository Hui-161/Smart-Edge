#!/usr/bin/env bash
# Builds the debug APK and prints version + signing certificate for verification.
# Usage: build-and-verify.sh [output-dir]   (default: $CLAUDE_SCRATCHPAD or ./build-out)
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
OUT="${1:-${CLAUDE_SCRATCHPAD:-$ROOT/build-out}}"
SDK="$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)"
SDK="${SDK:-${ANDROID_HOME:-$HOME/android-sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  echo "Shallow clone -> fetching full history for a correct versionCode"
  git fetch --unshallow
fi
[ -f app/signing/dev.keystore ] || echo "WARNING: app/signing/dev.keystore missing - APK will NOT update builds signed with the dev key."

./gradlew assembleDebug -q
APK="$(ls -t app/build/outputs/apk/debug/*.apk | head -1)"

badging="$("$BT/aapt" dump badging "$APK" | head -1)"
pkg="$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
vcode="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
vname="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
cert="$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"

mkdir -p "$OUT"
name="$(basename "$ROOT" | tr '[:upper:]' '[:lower:]')-$vname.apk"
cp "$APK" "$OUT/$name"
echo "package:     $pkg"
echo "versionCode: $vcode"
echo "versionName: $vname"
echo "cert SHA256: $cert"
echo "apk:         $OUT/$name"
