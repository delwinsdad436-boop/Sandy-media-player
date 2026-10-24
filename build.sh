#!/bin/bash
# Manual APK build for Sandy 0.10.0 (package com.talacore.talatv) — same proven chain as Eater/Ripper:
# stage deps -> aapt2 compile/link (versions live in the manifest itself)
# -> javac -> d8 -> zipalign -> apksigner.
set -e
export PATH=$HOME/workspace/apk_build/jdk-17.0.11+9/bin:$PATH

APP=$HOME/workspace/tala-tv-2026-10-16/app
ROOT=$HOME/workspace/tala-tv-2026-10-16
SDK=$HOME/workspace/apk_build/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
BUILD=$ROOT/build
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/apk" "$BUILD/res" "$BUILD/gen"

echo "== stage media3 deps (closure pre-fetched from Google Maven)"
python3 "$ROOT/work/stage_deps.py"

echo "== collect jars"
AAR_JARS=$(find "$BUILD/aar" -name classes.jar | sort | tr '\n' ' ')
PLAIN_JARS=$(find "$BUILD/jars" -name "*.jar" | sort | tr '\n' ' ')
ALL_JARS="$AAR_JARS $PLAIN_JARS"
echo "$ALL_JARS" | tr ' ' '\n' | grep -v '^$' | wc -l

echo "== aapt2 compile res (app + every AAR res/)"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res/app.zip"
RES_ARGS="-R $BUILD/res/app.zip"
for d in "$BUILD"/aar/*/; do
  if [ -d "$d/res" ] && [ -n "$(ls -A "$d/res" 2>/dev/null)" ]; then
    name=$(basename "$d")
    # v0.7.0: mediarouter's res serves only its AppCompat chooser
    # dialogs (styles parented on AppCompat themes). Sandy lists
    # routes itself and never loads those classes, so its res is
    # excluded — classes.jar only (see TvShare/ShareCast notes).
    if [ "$name" = "mediarouter-1.7.0" ]; then
      echo "  res: $name SKIPPED (dialog resources; not used)"
      continue
    fi
    "$BT/aapt2" compile --dir "$d/res" -o "$BUILD/res/$name.zip"
    RES_ARGS="$RES_ARGS -R $BUILD/res/$name.zip"
    echo "  res: $name"
  fi
done

echo "== aapt2 link (NO version flags - AndroidManifest.xml is authoritative)"
"$BT/aapt2" link -o "$BUILD/apk/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  $RES_ARGS \
  --auto-add-overlay \
  --extra-packages "androidx.media3.ui:androidx.media3.exoplayer:androidx.core:androidx.core.ktx:androidx.media:androidx.recyclerview:androidx.lifecycle:androidx.annotation.experimental:androidx.customview.poolingcontainer:androidx.mediarouter" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34

echo "== javac (app sources + generated R classes)"
find "$APP/src" -name "*.java" > "$BUILD/sources.txt"
find "$BUILD/gen" -name "*.java" >> "$BUILD/sources.txt"
CP=$(echo $ALL_JARS | tr ' ' ':')
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -classpath "$PLATFORM:$CP" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== d8"
"$BT/d8" --min-api 24 \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name "*.class" | tr '\n' ' ') \
  $ALL_JARS \
  --lib "$PLATFORM"

echo "== package dex"
cd "$BUILD/apk"
cp base.apk unsigned.apk
for dex in "$BUILD"/dex/classes*.dex; do
  b=$(basename "$dex")
  cp "$dex" "./$b"
  zip -q unsigned.apk "$b"
  rm "./$b"
done
ls "$BUILD"/dex/

echo "== package onnxruntime native libs (arm64-v8a only)"
mkdir -p "$BUILD/apk/lib/arm64-v8a"
cp "$BUILD/aar/onnxruntime-android-1.31.0/jni/arm64-v8a/libonnxruntime.so" \
   "$BUILD/apk/lib/arm64-v8a/"
cp "$BUILD/aar/onnxruntime-android-1.31.0/jni/arm64-v8a/libonnxruntime4j_jni.so" \
   "$BUILD/apk/lib/arm64-v8a/"
zip -q unsigned.apk lib/arm64-v8a/libonnxruntime.so \
  lib/arm64-v8a/libonnxruntime4j_jni.so

echo "== zipalign + sign"
if [ ! -f "$ROOT/app-signing.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/app-signing.keystore" -storepass android \
    -keypass android -alias talatvkey -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=TALA TV,O=TalaCore,C=US" 2>/dev/null
fi
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$ROOT/app-signing.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$BUILD/sandy-0.10.0.apk" aligned.apk

echo "== verify"
"$BT/aapt2" dump badging "$BUILD/sandy-0.10.0.apk" | head -5
"$BT/apksigner" verify --print-certs "$BUILD/sandy-0.10.0.apk" | head -3
ls -la "$BUILD/sandy-0.10.0.apk"
