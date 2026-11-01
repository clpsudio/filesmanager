#!/usr/bin/env bash
# Builds libffmpegJNI.so for one ABI without Gradle: CMake + the NDK toolchain,
# using the same CMake files as the Gradle build. This is the documented way to
# relink the decoder with a modified FFmpeg (see RELINKING.md).
#
#   scripts/ffmpeg/build-jni-standalone.sh <arm64-v8a|armeabi-v7a> [ndk-dir] [out-dir]
#
# Before running it, build FFmpeg's static libraries (scripts/ffmpeg/build-ffmpeg.sh,
# or your own modified build placed in decoder-ffmpeg/src/main/jni/ffmpeg/android-libs/<abi>/).
# Needs CMake >= 3.21 and Ninja on PATH (the Android SDK's cmake/3.22.1/bin has both).
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/scripts/ffmpeg/versions.env"
ABI="${1:?usage: $0 <arm64-v8a|armeabi-v7a> [ndk-dir] [out-dir]}"
NDK="${2:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$NDK_VERSION}}"
OUT="${3:-$ROOT/build/ffmpeg-jni-standalone/$ABI}"
case "$ABI" in arm64-v8a|armeabi-v7a) ;; *) echo "error: ABI must be arm64-v8a or armeabi-v7a" >&2; exit 1 ;; esac
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo "error: no NDK at '$NDK'" >&2; exit 1; }

CMAKE="${CMAKE:-cmake}"
if ! command -v "$CMAKE" >/dev/null && [[ -x "${ANDROID_HOME:-}/cmake/$CMAKE_VERSION/bin/cmake" ]]; then
  CMAKE="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/cmake"; export PATH="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin:$PATH"
fi

rm -rf "$OUT"
# Same settings the Android Gradle plugin passes for the release build:
# RelWithDebInfo, API 21 (the module's minSdk), the NDK's default STL.
"$CMAKE" -S "$ROOT/decoder-ffmpeg/src/main/cpp" -B "$OUT" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-21 -DANDROID_NDK="$NDK" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo
"$CMAKE" --build "$OUT"
so="$OUT/jni/libffmpegJNI.so"
"$NDK/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-strip" --strip-unneeded -o "$OUT/libffmpegJNI.stripped.so" "$so"
echo "Built $so"
echo "Stripped (as packaged in the APK): $OUT/libffmpegJNI.stripped.so"
