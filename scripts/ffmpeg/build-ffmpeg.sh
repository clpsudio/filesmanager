#!/usr/bin/env bash
# Builds FFmpeg's static libraries for the decoder module, exactly as AndroidX
# Media's own build_ffmpeg.sh does (that script is kept unmodified in
# decoder-ffmpeg/src/main/jni/). Gradle runs this automatically before the
# native (CMake) build of :decoder-ffmpeg; you can also run it by hand:
#
#   scripts/ffmpeg/build-ffmpeg.sh [path/to/android-ndk-r26b]
#
# Without an argument the NDK is taken from $ANDROID_NDK_HOME, or from
# $ANDROID_HOME/ndk/$NDK_VERSION (sdkmanager "ndk;26.1.10909125").
#
# Output: decoder-ffmpeg/src/main/jni/ffmpeg/android-libs/<abi>/lib{avcodec,avutil,swresample}.a
# The build is skipped when a stamp shows the same inputs were already built.
# Host: Linux or macOS (x86_64 or arm64 with Rosetta), with bash, make, curl and tar.
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/scripts/ffmpeg/versions.env"
export SOURCE_DATE_EPOCH LC_ALL=C TZ=UTC

NDK="${1:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$NDK_VERSION}}"
if [[ ! -f "$NDK/source.properties" ]]; then
  echo "error: Android NDK $NDK_VERSION not found at '$NDK'." >&2
  echo "       Install it with: sdkmanager \"ndk;$NDK_VERSION\"  (or pass its path)" >&2
  exit 1
fi
ndk_rev="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties" | tr -d '\r')"
if [[ "$ndk_rev" != "$NDK_VERSION" ]]; then
  echo "error: NDK at $NDK is $ndk_rev; this build is pinned to $NDK_VERSION." >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST_PLATFORM=linux-x86_64 ;;
  Darwin) HOST_PLATFORM=darwin-x86_64 ;;
  *) echo "error: unsupported host $(uname -s); build on Linux or macOS" >&2; exit 1 ;;
esac

MODULE_MAIN="$ROOT/decoder-ffmpeg/src/main"
FFMPEG_DIR="$MODULE_MAIN/jni/ffmpeg"
LIBS="$FFMPEG_DIR/android-libs"
STAMP="$LIBS/.clp-build-stamp"

"$ROOT/scripts/ffmpeg/fetch-ffmpeg.sh"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
key="$( {
  echo "commit=$FFMPEG_COMMIT tree=$FFMPEG_TREE_SHA256"
  echo "decoders=$FFMPEG_DECODERS api=$FFMPEG_ANDROID_API ndk=$ndk_rev host=$HOST_PLATFORM"
  sha256 "$MODULE_MAIN/jni/build_ffmpeg.sh" | cut -d' ' -f1
} | sha256 | cut -d' ' -f1 )"

have_all() {
  for abi in armeabi-v7a arm64-v8a; do
    for lib in avcodec avutil swresample; do
      [[ -f "$LIBS/$abi/lib$lib.a" ]] || return 1
    done
  done
}
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$key" ]] && have_all; then
  echo "FFmpeg static libraries are up to date ($key)"
  exit 0
fi

rm -rf "$LIBS"
echo "Building FFmpeg $FFMPEG_COMMIT with NDK $ndk_rev, decoders: $FFMPEG_DECODERS"
# Unmodified AndroidX Media script: same configure options as Jellyfin's 1.8.0+1 build.
# It also builds x86/x86_64 libraries; those are not linked or shipped
# (decoder-ffmpeg and app build only armeabi-v7a and arm64-v8a).
# shellcheck disable=SC2086
(cd "$MODULE_MAIN/jni" && ./build_ffmpeg.sh "$MODULE_MAIN" "$NDK" "$HOST_PLATFORM" "$FFMPEG_ANDROID_API" $FFMPEG_DECODERS)
have_all || { echo "error: FFmpeg build did not produce the expected libraries" >&2; exit 1; }
echo "$key" > "$STAMP"
echo "FFmpeg static libraries built ($key)"
