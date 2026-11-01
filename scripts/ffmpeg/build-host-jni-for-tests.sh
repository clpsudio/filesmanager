#!/usr/bin/env bash
# Builds a HOST (Linux x86_64) libffmpegJNI.so from the same sources as the app's
# library - FFmpeg at the pinned commit with the same decoder list, and AndroidX
# Media's unmodified ffmpeg_jni.cc - so JVM unit tests can load it and exercise the
# real Java -> JNI -> FFmpeg decoding path (decoder-ffmpeg/src/test). This library is
# for tests only; it is never packaged. The Android (ARM) libraries can't run on the
# build machine, so they are checked separately (symbols, byte comparison).
#
#   scripts/ffmpeg/build-host-jni-for-tests.sh      -> build/ffmpeg-host/lib/libffmpegJNI.so
#
# Needs: Linux, a C/C++ compiler (cc, c++), make, JAVA_HOME (for jni.h).
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/scripts/ffmpeg/versions.env"
[[ "$(uname -s)" == Linux ]] || { echo "error: host test library is built on Linux only" >&2; exit 1; }
: "${JAVA_HOME:?set JAVA_HOME (jni.h)}"
OUT="$ROOT/build/ffmpeg-host"
DL="${FFMPEG_DOWNLOAD_DIR:-$ROOT/build/ffmpeg-download}"
STAMP="$OUT/lib/.stamp"
key="$FFMPEG_COMMIT $FFMPEG_DECODERS $(sha256sum "$ROOT/decoder-ffmpeg/src/main/jni/ffmpeg_jni.cc" "$0" | sha256sum | cut -c1-16)"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$key" && -f "$OUT/lib/libffmpegJNI.so" ]]; then
  echo "$OUT/lib/libffmpegJNI.so is up to date"; exit 0
fi

# Fresh, verified source tree (the Android build configures the tree in place).
"$ROOT/scripts/ffmpeg/fetch-ffmpeg.sh" >/dev/null
TARBALL="$DL/$FFMPEG_TARBALL_NAME"
rm -rf "$OUT"; mkdir -p "$OUT/src" "$OUT/obj" "$OUT/lib" "$OUT/include/android"
tar -xzf "$TARBALL" -C "$OUT/src" --strip-components=1

# Same options as build_ffmpeg.sh's COMMON_OPTIONS, minus the Android-only ones,
# plus position-independent code for a shared library and no external assembler.
opts=(--enable-static --disable-shared --disable-doc --disable-programs --disable-everything
      --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter
      --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan
      --enable-pic --disable-x86asm --prefix="$OUT/obj/install")
for d in $FFMPEG_DECODERS; do opts+=(--enable-decoder="$d"); done
(cd "$OUT/obj" && "$OUT/src/configure" "${opts[@]}" > configure.log && make -j"$(nproc)" > make.log && make install-libs install-headers > install.log)

# android/log.h stand-in: log lines go to stderr.
cat > "$OUT/include/android/log.h" <<'EOF'
#pragma once
#include <stdio.h>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "%s: ", tag), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
EOF

I="$OUT/obj/install"
c++ -std=c++11 -O2 -fPIC -shared -o "$OUT/lib/libffmpegJNI.so" \
  -I"$OUT/include" -I"$I/include" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$ROOT/decoder-ffmpeg/src/main/jni/ffmpeg_jni.cc" \
  -Wl,--no-undefined "$I/lib/libswresample.a" "$I/lib/libavcodec.a" "$I/lib/libavutil.a" -lm -lpthread
echo "$key" > "$STAMP"
rm -rf "$OUT/src" "$OUT/obj"
echo "$OUT/lib/libffmpegJNI.so"
