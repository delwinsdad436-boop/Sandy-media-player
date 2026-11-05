#!/bin/bash
# Desktop harness for Sandy v0.20.0 — the v190 runner carried
# forward with the workdir advanced: the ORT desktop jar still
# lives at work/v180/ort-desktop.jar and the native pair at
# work/v161/ortnative (see the v180 runner's note for the
# toolchain-repair history). Test deps for THIS version's local
# runs: ci/testdeps/json-20240303.jar (repaired 2026-11-05 from
# the staged work/v140 copy, SHA-1 0ebb88e8… — see the fix-pass
# run log) and the natives staged into ci/ortnative.
set -e
export PATH=$HOME/workspace/apk_build/jdk-17.0.11+9/bin:$PATH
ROOT=$HOME/workspace/tala-tv-2026-10-16
APP=$ROOT/app
SDK=$HOME/workspace/apk_build/android-sdk
PLATFORM=$SDK/platforms/android-34/android.jar
V161=$ROOT/work/v161
V180=$ROOT/work/v180
V200=$ROOT/work/v200
TESTCLASS=${1:?usage: run_harness.sh <TestClassName>}
TESTFILE=${2:-$V200/$TESTCLASS.java}

DEPS=$(find "$ROOT/build/aar" -name classes.jar 2>/dev/null | sort | tr '\n' ':')
PLAIN=$(find "$ROOT/build/jars" -name "*.jar" 2>/dev/null | sort | tr '\n' ':' )
CP="$PLATFORM:$DEPS$PLAIN"

mkdir -p "$V200/appclasses" "$V200/testclasses"
find "$APP/src" -name "*.java" > "$V200/sources.txt"
if [ -d "$ROOT/build/gen" ]; then
  find "$ROOT/build/gen" -name "*.java" >> "$V200/sources.txt"
fi
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$CP" -d "$V200/appclasses" @"$V200/sources.txt"

javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$V200/appclasses:$ROOT/ci/testdeps/json-20240303.jar:$PLATFORM" \
    -d "$V200/testclasses" "$TESTFILE"

LD_LIBRARY_PATH="$ROOT/ci/ortnative" java -Djava.library.path="$ROOT/ci/ortnative" -Duser.home=/home/hatch -Dsandy.classes="$V200/appclasses" \
    -cp "$V200/testclasses:$V200/appclasses:$ROOT/ci/testdeps/json-20240303.jar:$V180/ort-desktop.jar:$DEPS$PLAIN$PLATFORM" \
    "$TESTCLASS"
