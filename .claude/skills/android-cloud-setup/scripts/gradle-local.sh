#!/usr/bin/env bash
# Runs ./gradlew in a cloud container whose network blocks the JDK download for the Gradle
# daemon. If gradle/gradle-daemon-jvm.properties pins a JDK (e.g. Amazon Corretto) and
# api.foojay.io is unreachable, the file is hidden for this run only and restored afterwards,
# so the daemon uses the installed JDK. Local workaround only: never commit the file's removal.
# Usage: gradle-local.sh <gradle args...>   e.g. gradle-local.sh assembleDebug testDebugUnitTest
# GRADLE_LOCAL_JDK=1 forces it (foojay reachable, but the JDK host behind it is blocked).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
criteria=gradle/gradle-daemon-jvm.properties

if [ -f "$criteria" ] && { [ "${GRADLE_LOCAL_JDK:-}" = 1 ] ||
  ! curl -fsS --max-time 10 -o /dev/null https://api.foojay.io/disco/v3.0/major_versions 2>/dev/null; }; then
  mv "$criteria" "$criteria.local-off"
  trap 'mv "$criteria.local-off" "$criteria"' EXIT
  echo "Daemon JDK criteria skipped for this run (JDK download not possible), using $(java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -1)"
fi

./gradlew "$@"
