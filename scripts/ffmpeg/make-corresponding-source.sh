#!/usr/bin/env bash
# Writes the "corresponding source" bundle for libffmpegJNI.so (LGPL-2.1 section 6):
#
#   scripts/ffmpeg/make-corresponding-source.sh [label] [out-dir]
#
# -> <out-dir>/ffmpeg-corresponding-source-<label>.tar.gz  (default out-dir: build/ffmpeg-corresponding-source)
#
# Contents: the FFmpeg source tarball at the pinned commit (sha256 checked), the
# decoder module source (JNI glue, Java classes, CMake files), the FFmpeg build
# scripts with the exact configure options and pins, the licence texts,
# RELINKING.md, and - when a Gradle native build has run - the JNI glue object
# files with their exact link commands (relink-kit/).
# Run it after ./gradlew :app:assembleRelease. Needs GNU tar (Linux).
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/scripts/ffmpeg/versions.env"
LABEL="${1:-$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo local)}"
OUTDIR="${2:-$ROOT/build/ffmpeg-corresponding-source}"
DL="${FFMPEG_DOWNLOAD_DIR:-$ROOT/build/ffmpeg-download}"
NAME="ffmpeg-corresponding-source-$LABEL"
STAGE="$OUTDIR/$NAME"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

# 1. The exact FFmpeg source tarball.
TARBALL="$DL/$FFMPEG_TARBALL_NAME"
if [[ ! -f "$TARBALL" ]]; then
  mkdir -p "$DL"
  curl -fL --retry 3 -o "$TARBALL.part" "$FFMPEG_TARBALL_URL" && mv "$TARBALL.part" "$TARBALL"
fi
[[ "$(sha256 "$TARBALL" | cut -d' ' -f1)" == "$FFMPEG_TARBALL_SHA256" ]] || { echo "error: $TARBALL sha256 mismatch" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$STAGE/source" "$STAGE/licenses"
cp "$TARBALL" "$STAGE/"

# 2. Decoder module + scripts + docs (FFmpeg tree and build outputs excluded).
(cd "$ROOT" && tar -cf - --exclude='decoder-ffmpeg/src/main/jni/ffmpeg' --exclude='decoder-ffmpeg/build' \
    --exclude='decoder-ffmpeg/.cxx' decoder-ffmpeg scripts/ffmpeg RELINKING.md) | tar -xf - -C "$STAGE/source"
cp "$ROOT"/licenses/{LGPL-2.1.txt,MIT-musl-avsscanf.txt,FFMPEG-NOTICE.txt,FFMPEG-SOURCE.txt,FFMPEG-RELINKING.txt,Apache-2.0.txt} "$STAGE/licenses/"

# 3. Relink kit: JNI glue object files and the exact link commands from the last Gradle release build.
NINJA="${NINJA:-}"
[[ -z "$NINJA" && -x "${ANDROID_HOME:-}/cmake/$CMAKE_VERSION/bin/ninja" ]] && NINJA="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/ninja"
for abi in arm64-v8a armeabi-v7a; do
  bdir="$(ls -d "$ROOT"/decoder-ffmpeg/.cxx/RelWithDebInfo/*/"$abi" 2>/dev/null | head -1 || true)"
  obj="$(find "${bdir:-/nonexistent}" -name 'ffmpeg_jni.cc.o' 2>/dev/null | head -1 || true)"
  if [[ -n "$obj" ]]; then
    mkdir -p "$STAGE/relink-kit/$abi"
    cp "$obj" "$STAGE/relink-kit/$abi/ffmpeg_jni.cc.o"
    if [[ -n "$NINJA" ]]; then
      (cd "$bdir" && "$NINJA" -t commands ffmpegJNI | tail -1) > "$STAGE/relink-kit/$abi/link-command.txt"
    fi
  else
    echo "note: no Gradle native build for $abi found; relink-kit/$abi not included" >&2
  fi
done

cat > "$STAGE/README.txt" <<EOF
Corresponding source for libffmpegJNI.so in Files (com.clpstudio.filesmanager), build $LABEL

libffmpegJNI.so = AndroidX Media 1.8.0 ffmpeg_jni.cc (Apache-2.0)
                + FFmpeg $FFMPEG_COMMIT (LGPL-2.1-or-later; libavutil/avsscanf.c is MIT)
                  libavcodec, libavutil, libswresample, linked statically.

$FFMPEG_TARBALL_NAME
    FFmpeg source exactly as compiled (sha256 $FFMPEG_TARBALL_SHA256).
source/decoder-ffmpeg/
    JNI glue, Java classes, CMake files (UPSTREAM.md lists origins and hashes).
source/scripts/ffmpeg/
    versions.env (pins), fetch-ffmpeg.sh, build-ffmpeg.sh, build-jni-standalone.sh, this script.
    FFmpeg configure options: source/decoder-ffmpeg/src/main/jni/build_ffmpeg.sh
    (called with: <module> <ndk> <host> $FFMPEG_ANDROID_API $FFMPEG_DECODERS).
source/RELINKING.md
    How to rebuild the library with a modified FFmpeg and put it into the APK.
relink-kit/<abi>/
    ffmpeg_jni.cc.o and the exact link command used by the build (paths are those of the build machine).
licenses/
    LGPL-2.1, MIT (musl av_sscanf), Apache-2.0, FFmpeg notice.

Toolchain: Android NDK $NDK_VERSION, CMake $CMAKE_VERSION.
To rebuild: put $FFMPEG_TARBALL_NAME in build/ffmpeg-download/ of a checkout (or set FFMPEG_TARBALL),
then follow source/RELINKING.md.
EOF

(cd "$STAGE" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS)

mkdir -p "$OUTDIR"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@$SOURCE_DATE_EPOCH" \
    -C "$OUTDIR" -cf - "$NAME" | gzip -n -9 > "$OUTDIR/$NAME.tar.gz"
echo "$OUTDIR/$NAME.tar.gz"
