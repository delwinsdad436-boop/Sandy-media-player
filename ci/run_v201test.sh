#!/bin/bash
# CI adaptation of work/v201/run_harness.sh — same desktop JVM harness,
# repo-relative paths, test-only jars staged by ci/fetch_deps.py.
# Pass bar: the harness's own summary line must report 0 failures.
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
APP="$ROOT/app"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
PLATFORM="$SDK/platforms/android-34/android.jar"
V201="$ROOT/work/v201"
OUT="$ROOT/ci-out"
mkdir -p "$OUT" "$V201/appclasses" "$V201/testclasses"

DEPS=$(find "$ROOT/build/aar" -name classes.jar 2>/dev/null | sort | tr '\n' ':')
PLAIN=$(find "$ROOT/build/jars" -name "*.jar" 2>/dev/null | sort | tr '\n' ':')
CP="$PLATFORM:$DEPS$PLAIN"

find "$APP/src" -name "*.java" > "$V201/sources.txt"
if [ -d "$ROOT/build/gen" ]; then
  find "$ROOT/build/gen" -name "*.java" >> "$V201/sources.txt"
fi
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$CP" -d "$V201/appclasses" @"$V201/sources.txt"

javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$V201/appclasses:$ROOT/ci/testdeps/json-20240303.jar:$PLATFORM" \
    -d "$V201/testclasses" "$V201/V201Test.java"

set +e
LD_LIBRARY_PATH="$ROOT/ci/ortnative" java \
    -Djava.library.path="$ROOT/ci/ortnative" \
    -Dsandy.ci=true \
    -Dsandy.classes="$V201/appclasses" \
    -cp "$V201/testclasses:$V201/appclasses:$ROOT/ci/testdeps/json-20240303.jar:$ROOT/ci/testdeps/ort-desktop.jar:$DEPS$PLAIN$PLATFORM" \
    V201Test 2>&1 | tee "$OUT/v201test.log"
rc=${PIPESTATUS[0]}
set -e
echo "harness exit code: $rc"
grep -E "V201Test: [0-9]+ checks, [0-9]+ failures" "$OUT/v201test.log" || true
echo "--- CI skips (groups whose binary packs / bulky fixtures do not travel; see ci/HARNESS-CI-AUDIT.md) ---"
grep -E "^(SKIP |CI SKIPS:)" "$OUT/v201test.log" || true
if [ "$rc" -ne 0 ]; then echo "HARNESS FAILED (exit $rc)"; exit 1; fi
if ! grep -qE "V201Test: [0-9]+ checks, 0 failures" "$OUT/v201test.log"; then
  echo "HARNESS FAILED (failures present or summary missing)"
  exit 1
fi
echo "HARNESS PASSED"
