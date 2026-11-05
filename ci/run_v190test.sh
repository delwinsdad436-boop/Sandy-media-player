#!/bin/bash
# CI adaptation of work/v190/run_harness.sh — same desktop JVM harness,
# repo-relative paths, test-only jars staged by ci/fetch_deps.py.
# Pass bar: the harness's own summary line must report 0 failures.
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
APP="$ROOT/app"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
PLATFORM="$SDK/platforms/android-34/android.jar"
V190="$ROOT/work/v190"
OUT="$ROOT/ci-out"
mkdir -p "$OUT" "$V190/appclasses" "$V190/testclasses"

DEPS=$(find "$ROOT/build/aar" -name classes.jar 2>/dev/null | sort | tr '\n' ':')
PLAIN=$(find "$ROOT/build/jars" -name "*.jar" 2>/dev/null | sort | tr '\n' ':')
CP="$PLATFORM:$DEPS$PLAIN"

find "$APP/src" -name "*.java" > "$V190/sources.txt"
if [ -d "$ROOT/build/gen" ]; then
  find "$ROOT/build/gen" -name "*.java" >> "$V190/sources.txt"
fi
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$CP" -d "$V190/appclasses" @"$V190/sources.txt"

javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$V190/appclasses:$ROOT/ci/testdeps/json-20240303.jar:$PLATFORM" \
    -d "$V190/testclasses" "$V190/V190Test.java"

set +e
LD_LIBRARY_PATH="$ROOT/ci/ortnative" java \
    -Djava.library.path="$ROOT/ci/ortnative" \
    -Dsandy.ci=true \
    -Dsandy.classes="$V190/appclasses" \
    -cp "$V190/testclasses:$V190/appclasses:$ROOT/ci/testdeps/json-20240303.jar:$ROOT/ci/testdeps/ort-desktop.jar:$DEPS$PLAIN$PLATFORM" \
    V190Test 2>&1 | tee "$OUT/v190test.log"
rc=${PIPESTATUS[0]}
set -e
echo "harness exit code: $rc"
grep -E "V190Test: [0-9]+ checks, [0-9]+ failures" "$OUT/v190test.log" || true
echo "--- CI skips (groups whose binary packs / bulky fixtures do not travel; see ci/HARNESS-CI-AUDIT.md) ---"
grep -E "^(SKIP |CI SKIPS:)" "$OUT/v190test.log" || true
if [ "$rc" -ne 0 ]; then echo "HARNESS FAILED (exit $rc)"; exit 1; fi
if ! grep -qE "V190Test: [0-9]+ checks, 0 failures" "$OUT/v190test.log"; then
  echo "HARNESS FAILED (failures present or summary missing)"
  exit 1
fi
echo "HARNESS PASSED"
