#!/usr/bin/env bash
# Rebuilds the "fresh*" archive fixtures used by FeatureVerificationTest (app/src/test/resources/fmt/).
# Needs: python3, RAR 6.x (rarlab.com; 6.x is the last version that can still write RAR4 with -ma4),
# Info-ZIP zip 3.0, p7zip 16.02 (7z), GNU tar. Usage: scripts/test-fixtures/make-fresh-archives.sh <path/to/rar> <outdir>
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail
RAR="$(realpath "$1")"; OUT="$(realpath "$2")"
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
rm -f "$OUT"/fresh_rar4.rar "$OUT"/fresh_rar4_solid.rar "$OUT"/fresh_rar4_password.rar "$OUT"/fresh_rar5.rar "$OUT"/fresh.zip "$OUT"/fresh_utf8.zip "$OUT"/fresh.7z "$OUT"/fresh.tar.gz
"$RAR" a -idq -ma4 -m5 -r "$OUT/fresh_rar4.rar" Fresh
"$RAR" a -idq -ma4 -m5 -s -r "$OUT/fresh_rar4_solid.rar" Fresh
"$RAR" a -idq -ma4 -m3 -ptrip123 -r "$OUT/fresh_rar4_password.rar" Fresh
"$RAR" a -idq -ma5 -r "$OUT/fresh_rar5.rar" Fresh
zip -qrX -9 "$OUT/fresh.zip" Fresh
7z a -bd -mx9 "$OUT/fresh.7z" Fresh > /dev/null
7z a -bd -tzip -mx9 "$OUT/fresh_utf8.zip" Fresh > /dev/null
tar --sort=name --mtime='2026-11-01' --owner=0 --group=0 -czf "$OUT/fresh.tar.gz" Fresh
