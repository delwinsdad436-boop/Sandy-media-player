#!/bin/bash
# Desktop harness for Sandy v0.18.0 — same runner as v170's, new
# workdir. Toolchain repair (2026-10-20): work/v150's rebuilt ORT
# desktop jar was swept from disk again; the official Maven Central
# onnxruntime 1.31.0 desktop jar now lives at work/v180/ort-desktop.jar
# (its linux-x64 libonnxruntime.so is 29,345,384 B — the byte size the
# 0.16.1 note recorded for the repaired natives). The v161 ortnative
# pair (libonnxruntime.so.1 + libonnxruntime4j_jni.so) still serves as
# the native library path, exactly as in the v170 runner.
set -e
export PATH=$HOME/workspace/apk_build/jdk-17.0.11+9/bin:$PATH
ROOT=$HOME/workspace/tala-tv-2026-10-16
APP=$ROOT/app
SDK=$HOME/workspace/apk_build/android-sdk
PLATFORM=$SDK/platforms/android-34/android.jar
V140=$ROOT/work/v140
V161=$ROOT/work/v161
V180=$ROOT/work/v180
TESTCLASS=${1:?usage: run_harness.sh <TestClassName>}
TESTFILE=${2:-$V180/$TESTCLASS.java}

DEPS=$(find "$ROOT/build/aar" -name classes.jar 2>/dev/null | sort | tr '\n' ':')
PLAIN=$(find "$ROOT/build/jars" -name "*.jar" 2>/dev/null | sort | tr '\n' ':')
CP="$PLATFORM:$DEPS$PLAIN"

mkdir -p "$V180/appclasses" "$V180/testclasses"
find "$APP/src" -name "*.java" > "$V180/sources.txt"
if [ -d "$ROOT/build/gen" ]; then
  find "$ROOT/build/gen" -name "*.java" >> "$V180/sources.txt"
fi
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$CP" -d "$V180/appclasses" @"$V180/sources.txt"

javac -nowarn -encoding UTF-8 -source 17 -target 17 \
    -classpath "$V180/appclasses:$V140/json-20240303.jar:$PLATFORM" \
    -d "$V180/testclasses" "$TESTFILE"

LD_LIBRARY_PATH="$V161/ortnative" java -Djava.library.path="$V161/ortnative" -Duser.home=/home/hatch -Dsandy.classes="$V180/appclasses" \
    -cp "$V180/testclasses:$V180/appclasses:$V140/json-20240303.jar:$V180/ort-desktop.jar:$DEPS$PLAIN$PLATFORM" \
    "$TESTCLASS"
