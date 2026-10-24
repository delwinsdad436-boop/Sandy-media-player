#!/bin/bash
# CI helper: the GitHub snapshot channel carries UTF-8 text only, so the
# app's PNG resources travel as base64 text under ci/assets-b64/ and are
# decoded back to their real paths here, byte for byte, before the build.
set -e
cd "$(dirname "$0")/.."
count=0
while IFS= read -r b64; do
  rel="${b64#ci/assets-b64/}"
  out="${rel%.b64}"
  mkdir -p "$(dirname "$out")"
  base64 -d "$b64" > "$out"
  count=$((count+1))
done < <(find ci/assets-b64 -name "*.b64" | sort)
echo "restored $count binary files from ci/assets-b64"
