#!/usr/bin/env bash
# Rebuilds the "fresh*" archive fixtures used by FeatureVerificationTest (app/src/test/resources/fmt/).
# Needs: python3, Info-ZIP zip 3.0, p7zip 16.02 (7z), GNU tar (all open source).
# Usage: scripts/test-fixtures/make-fresh-archives.sh <outdir>
# Files does not open RAR, so there are no RAR fixtures: the tests that check RAR is refused build the RAR
# signature bytes themselves. The readme.txt text below still names RAR 6.24 only because the committed
# ZIP/7z/tar.gz fixtures were made with that exact text and the tests check their SHA-256.
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail
OUT="$(realpath "$1")"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/src/Fresh/deep/er" "$W/src/Fresh/emptydir"
cd "$W"
python3 - <<'PY'
import random
random.seed(2026)
open('src/Fresh/readme.txt','w').write("RAR4 fixture made with RAR 6.24 -ma4\n" + "line of compressible text for the LZ coder\n"*1200)
open('src/Fresh/deep/er/random.bin','wb').write(bytes(random.getrandbits(8) for _ in range(16000)))
open('src/Fresh/deep/\u00fcn\u00efcode \u2713.txt','w').write("unicode name ok\n")
PY
find src -exec touch -d '2026-11-01 12:00:00' {} +
cd src
rm -f "$OUT"/fresh.zip "$OUT"/fresh_utf8.zip "$OUT"/fresh.7z "$OUT"/fresh.tar.gz
zip -qrX -9 "$OUT/fresh.zip" Fresh
7z a -bd -mx9 "$OUT/fresh.7z" Fresh > /dev/null
7z a -bd -tzip -mx9 "$OUT/fresh_utf8.zip" Fresh > /dev/null
tar --sort=name --mtime='2026-11-01' --owner=0 --group=0 -czf "$OUT/fresh.tar.gz" Fresh
