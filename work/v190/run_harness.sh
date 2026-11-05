#!/bin/bash
# Desktop harness for Sandy v0.19.0 — the v180 runner carried
# forward unchanged except the workdir: the ORT desktop jar
# still lives at work/v180/ort-desktop.jar and the native pair
# at work/v161/ortnative (see the v180 runner's note for the
# toolchain-repair history).
set -e
export PATH=$HOME/workspace/apk_build/jdk-17.0.11+9/bin:$PATH
ROOT=$HOME/workspace/tala-tv-2026-10-16
APP=$ROOT/app
SDK=$HOME/workspace/apk_build/android-sdk
PLATFORM=$SDK/platforms/android-34/android.jar
V140=$ROOT/work/v140
V161=$ROOT/work/v161
V180=$ROOT/work/v180
V190=$ROOT/work/v190
TESTCLASS=${1:?usage: run_harness.sh <TestClassName>}
TESTFILE=${2:-$V190/$TESTCLASS.java}

DEPS=$(find "$ROOT/build/aar" -name classes.jar 2>/dev/null | sort | tr '\n' ':')
PLAIN=$(find "$ROOT/build/jars" -name "*.jar" 2>/dev/null | sort | tr '\n' ':' )
CP="$PLATFORM:$DEPS$PLAIN"

mkdir -p "$V190/appclasses" "$V190/testclasses"
find "$APP/src" -name "*.java" > "$V190/sources.txt"
if [ -d "$ROOT/build/gen" ]; then
  find "$ROOT/build/gen" -name "*.java" >> "$V190/sources.txt"
fi
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$CP" -d "$V190/appclasses" @"$V190/sources.txt"

javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$V190/appclasses:$V140/json-20240303.jar:$PLATFORM" \
    -d "$V190/testclasses" "$TESTFILE"

LD_LIBRARY_PATH="$V161/ortnative" java -Djava.library.path="$V161/ortnative" -Duser.home=/home/hatch -Dsandy.classes="$V190/appclasses" \
    -cp "$V190/testclasses:$V190/appclasses:$V140/json-20240303.jar:$V180/ort-desktop.jar:$DEPS$PLAIN$PLATFORM" \
    "$TESTCLASS"
