#!/bin/bash
# Emulator launch verification for Sandy.
#
# Why a file: reactivecircus/android-emulator-runner executes each
# line of its `script:` input as a SEPARATE `/usr/bin/sh -c`, so
# shell variables never persist from one line to the next (proven
# the hard way in CI runs #9 and #10, 2026-10-23: $APK and $OUT
# were empty in the lines that used them). The workflow therefore
# calls this one file, which runs start-to-finish in a single
# shell that keeps its memory.
#
# Paths are derived from this file's own location (repo root),
# not from the caller's working directory or environment.
set -x
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/emu-out"
mkdir -p "$OUT"
echo "dl contents:"
ls -la "$ROOT/dl" || true
APK=$(find "$ROOT/dl" -name '*.apk' | head -1)
echo "installing $APK"
if [ -z "$APK" ]; then echo "LAUNCH VERDICT: FAIL - no APK found under dl/"; exit 1; fi
adb install -r "$APK"
adb shell am start -W -n com.talacore.talatv/.SplashActivity
sleep 15
PID=$(adb shell pidof com.talacore.talatv || true)
echo "app pid: $PID"
adb shell dumpsys activity activities > "$OUT/activities.txt" || true
adb shell screencap -p /sdcard/shot.png || true
adb pull /sdcard/shot.png "$OUT/screenshot.png" || true
adb logcat -d > "$OUT/logcat.txt" || true
if [ -z "$PID" ]; then echo "LAUNCH VERDICT: FAIL - process not alive after launch"; exit 1; fi
if grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "talacore"; then echo "LAUNCH VERDICT: FAIL - fatal exception for the app"; exit 1; fi
if ! grep -q "com.talacore.talatv" "$OUT/activities.txt"; then echo "LAUNCH VERDICT: FAIL - app not in activity stack"; exit 1; fi
echo "LAUNCH VERDICT: PASS - process $PID alive, app in activity stack"
