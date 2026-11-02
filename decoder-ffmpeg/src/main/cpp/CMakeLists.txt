# CLP Studio wrapper around AndroidX Media's jni/CMakeLists.txt, which is kept
# unmodified next to this file. It only adds flags that make libffmpegJNI.so
# reproducible; they don't change the generated code.
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0.

# Same minimum as the upstream file (see decoder-ffmpeg/build.gradle.kts for the pinned 3.22.1).
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
project(clp_decoder_ffmpeg C CXX)

set(upstream_jni "${CMAKE_CURRENT_SOURCE_DIR}/../jni")
add_subdirectory("${upstream_jni}" "${CMAKE_CURRENT_BINARY_DIR}/jni")

# Debug info and __FILE__ refer to "decoder-ffmpeg/src/main/..." instead of the
# absolute checkout path, so object files don't depend on where the repo is.
get_filename_component(module_main "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
target_compile_options(ffmpegJNI PRIVATE "-ffile-prefix-map=${module_main}=decoder-ffmpeg/src/main")

# FFmpeg's static libraries carry debug info with the absolute path of the
# FFmpeg source tree. Dropping debug sections at link time makes the GNU
# build-id (a hash of the linked file) independent of that path. The packaged
# library is stripped by the Android Gradle plugin in any case.
target_link_options(ffmpegJNI PRIVATE "-Wl,--strip-debug")
