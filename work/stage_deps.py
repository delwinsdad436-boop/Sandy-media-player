#!/usr/bin/env python3
"""Stage the selected media3 dependency set for the manual build.

Selection = transitive closure from Google Maven with Gradle's conflict
rule applied by hand: highest version per (group, artifact). The two
listenablefuture jars are skipped on purpose: guava-android carries
ListenableFuture itself, and the 9999.0 stub exists to say exactly that.
"""
import os, shutil, sys, zipfile
import xml.etree.ElementTree as ET

M = os.path.expanduser("~/workspace/tala-tv-2026-10-16/work/maven")
B = os.path.expanduser("~/workspace/tala-tv-2026-10-16/build")

AARS = [
    "androidx/media3/media3-common/1.4.1/media3-common-1.4.1.aar",
    "androidx/media3/media3-container/1.4.1/media3-container-1.4.1.aar",
    "androidx/media3/media3-database/1.4.1/media3-database-1.4.1.aar",
    "androidx/media3/media3-datasource/1.4.1/media3-datasource-1.4.1.aar",
    "androidx/media3/media3-decoder/1.4.1/media3-decoder-1.4.1.aar",
    "androidx/media3/media3-exoplayer/1.4.1/media3-exoplayer-1.4.1.aar",
    "androidx/media3/media3-exoplayer-hls/1.4.1/media3-exoplayer-hls-1.4.1.aar",
    "androidx/media3/media3-extractor/1.4.1/media3-extractor-1.4.1.aar",
    "androidx/media3/media3-ui/1.4.1/media3-ui-1.4.1.aar",
    "androidx/annotation/annotation-experimental/1.3.1/annotation-experimental-1.3.1.aar",
    "androidx/core/core/1.8.0/core-1.8.0.aar",
    "androidx/core/core-ktx/1.5.0/core-ktx-1.5.0.aar",
    "androidx/media/media/1.7.0/media-1.7.0.aar",
    "androidx/lifecycle/lifecycle-runtime/2.3.1/lifecycle-runtime-2.3.1.aar",
    "androidx/arch/core/core-runtime/2.1.0/core-runtime-2.1.0.aar",
    "androidx/customview/customview/1.0.0/customview-1.0.0.aar",
    "androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0.aar",
    "androidx/exifinterface/exifinterface/1.3.6/exifinterface-1.3.6.aar",
    "androidx/recyclerview/recyclerview/1.3.0/recyclerview-1.3.0.aar",
    "androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar",
    # v0.6.1: ONNX Runtime (MIT) — the bound translation engine;
    # its arm64-v8a .so files are packaged by build.sh
    "com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar",
    # v0.7.0: MediaRouter — the Android remote-playback fling
    # (route scan + RemotePlaybackClient). Only the core classes
    # are used; the library's AppCompat chooser dialog is NOT
    # referenced (Sandy builds its own route list), so appcompat
    # stays out of the closure on purpose.
    "androidx/mediarouter/mediarouter/1.7.0/mediarouter-1.7.0.aar",
]
JARS = [
    "androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar",
    "androidx/arch/core/core-common/2.2.0-rc01/core-common-2.2.0-rc01.jar",
    "androidx/collection/collection/1.2.0/collection-1.2.0.jar",
    "androidx/concurrent/concurrent-futures/1.0.0/concurrent-futures-1.0.0.jar",
    "androidx/lifecycle/lifecycle-common/2.3.1/lifecycle-common-2.3.1.jar",
    "com/google/guava/guava/33.0.0-android/guava-33.0.0-android.jar",
    "com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar",
    "org/jetbrains/kotlin/kotlin-stdlib/1.7.10/kotlin-stdlib-1.7.10.jar",
    "org/jetbrains/annotations/13.0/annotations-13.0.jar",
]

def main():
    aar_dir = os.path.join(B, "aar")
    jar_dir = os.path.join(B, "jars")
    shutil.rmtree(aar_dir, ignore_errors=True)
    shutil.rmtree(jar_dir, ignore_errors=True)
    os.makedirs(aar_dir); os.makedirs(jar_dir)

    print("== AARs")
    for rel in AARS:
        src = os.path.join(M, rel)
        name = os.path.basename(rel).replace(".aar", "")
        dst = os.path.join(aar_dir, name)
        with zipfile.ZipFile(src) as z:
            z.extractall(dst)
        has_classes = os.path.exists(os.path.join(dst, "classes.jar"))
        has_res = os.path.isdir(os.path.join(dst, "res")) and any(os.scandir(os.path.join(dst, "res")))
        has_assets = os.path.isdir(os.path.join(dst, "assets")) and any(os.scandir(os.path.join(dst, "assets")))
        has_jni = os.path.isdir(os.path.join(dst, "jni")) and any(os.scandir(os.path.join(dst, "jni")))
        # manifest component scan
        comps = []
        mf = os.path.join(dst, "AndroidManifest.xml")
        if os.path.exists(mf):
            try:
                data = open(mf, "rb").read()
                # AAR manifests are plain XML text
                root = ET.fromstring(data)
                for el in root.iter():
                    tag = el.tag.split("}")[-1]
                    if tag in ("activity", "service", "provider", "receiver"):
                        comps.append(tag)
            except Exception as e:
                comps.append(f"<unparsed: {e}>")
        print(f"  {name}: classes.jar={'YES' if has_classes else 'MISSING!'} "
              f"res={'yes' if has_res else 'no'} assets={'YES!' if has_assets else 'no'} "
              f"jni={'YES!' if has_jni else 'no'} components={comps or 'none'}")
        if not has_classes:
            sys.exit(f"FATAL: {name} has no classes.jar")

    print("== JARs")
    for rel in JARS:
        src = os.path.join(M, rel)
        dst = os.path.join(jar_dir, os.path.basename(rel))
        shutil.copy2(src, dst)
        with zipfile.ZipFile(dst) as z:
            n = len([x for x in z.namelist() if x.endswith(".class")])
        print(f"  {os.path.basename(rel)}: {n} classes")

    # duplicate-class check across every dex input
    print("== duplicate class check (AAR classes.jar + plain jars)")
    seen = {}
    dups = 0
    inputs = []
    for d in sorted(os.listdir(aar_dir)):
        inputs.append(os.path.join(aar_dir, d, "classes.jar"))
    for j in sorted(os.listdir(jar_dir)):
        inputs.append(os.path.join(jar_dir, j))
    for path in inputs:
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.endswith(".class"):
                    if n in seen:
                        print(f"  DUP: {n} in {os.path.basename(path)} and {seen[n]}")
                        dups += 1
                    else:
                        seen[n] = os.path.basename(path)
    print(f"  total classes={len(seen)} duplicates={dups}")
    if dups:
        sys.exit("FATAL: duplicate classes in dex inputs")

if __name__ == "__main__":
    main()
