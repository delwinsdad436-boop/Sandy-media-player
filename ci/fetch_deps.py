#!/usr/bin/env python3
"""CI dependency fetch for Sandy's manual build.

work/stage_deps.py stages a fixed, hand-resolved dependency closure out of
a local file-based Maven repo at work/maven. Locally that repo was seeded
once by work/fetch_media3.py; in CI we reconstruct exactly the artifacts
stage_deps.py names, downloaded from the same public repositories:

  androidx/**            -> https://dl.google.com/dl/android/maven2/
  everything else        -> https://repo.maven.apache.org/maven2/

Also fetched for the desktop unit harness (work/v180/run_harness.sh):
  org.json json 20240303 jar          -> ci/testdeps/json-20240303.jar
  onnxruntime 1.31.0 desktop jar      -> ci/testdeps/ort-desktop.jar
  its linux-x64 natives, extracted    -> ci/ortnative/ (both the plain and
                                         the versioned .so.1 name the
                                         local v161 natives carried)
"""
import ast
import os
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo.maven.apache.org/maven2/"


def load_lists():
    src = open(os.path.join(ROOT, "work", "stage_deps.py")).read()
    tree = ast.parse(src)
    lists = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and node.targets[0].id in ("AARS", "JARS"):
            lists[node.targets[0].id] = ast.literal_eval(node.value)
    return lists["AARS"], lists["JARS"]


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"  have {dest}")
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"  get  {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "sandy-ci-fetch/1.0"})
    with urllib.request.urlopen(req) as r, open(dest, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)


def main():
    aars, jars = load_lists()
    maven = os.path.join(ROOT, "work", "maven")
    for rel in aars + jars:
        base = GOOGLE if rel.startswith("androidx/") else CENTRAL
        fetch(base + rel, os.path.join(maven, rel))

    td = os.path.join(ROOT, "ci", "testdeps")
    json_jar = os.path.join(td, "json-20240303.jar")
    fetch(CENTRAL + "org/json/json/20240303/json-20240303.jar", json_jar)
    ort_jar = os.path.join(td, "ort-desktop.jar")
    fetch(CENTRAL + "com/microsoft/onnxruntime/onnxruntime/1.31.0/onnxruntime-1.31.0.jar", ort_jar)

    native_dir = os.path.join(ROOT, "ci", "ortnative")
    os.makedirs(native_dir, exist_ok=True)
    with zipfile.ZipFile(ort_jar) as z:
        for name in z.namelist():
            if "linux-x64" in name and (name.endswith(".so") or ".so." in name):
                data = z.read(name)
                base = os.path.basename(name)
                with open(os.path.join(native_dir, base), "wb") as f:
                    f.write(data)
                print(f"  native {base} ({len(data)} B)")
    plain = os.path.join(native_dir, "libonnxruntime.so")
    versioned = os.path.join(native_dir, "libonnxruntime.so.1")
    if os.path.exists(plain) and not os.path.exists(versioned):
        with open(plain, "rb") as f, open(versioned, "wb") as out:
            out.write(f.read())
        print("  native libonnxruntime.so.1 (copy of libonnxruntime.so)")
    print("dependency fetch complete")


if __name__ == "__main__":
    sys.exit(main())
