#!/usr/bin/env bash
# CI guard: fails if a prebuilt native binary is committed anywhere in the repository,
# so every build (including one with a cold cache) builds FFmpeg and libffmpegJNI.so
# from the pinned FFmpeg commit in scripts/ffmpeg/versions.env.
#
#   scripts/ci/check-no-prebuilt-native.sh            # checks the tracked files of this checkout
#
# It fails on any tracked file that
#   1. is named like a native library or object: *.so, *.so.<n>, *.a, *.o, *.obj, *.dylib, *.dll;
#   2. is an ELF binary or an ar static library by its first bytes, whatever its name;
#   3. is a zip-based package (.aar, .jar, .zip, .apk, .aab) that contains such a file, except the
#      test-fixture APKs under app/src/test/resources/ (inputs of the APK inspector tests; they are
#      never linked, loaded or packaged);
# and on any Gradle build file that points the app at prebuilt native code or a prebuilt decoder
# package (jniLibs, a local .aar, or the old org.jellyfin.media3 dependency).
#
# Needs: bash, git, od, unzip.
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0 (licence decision pending).
set -euo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
cd "$ROOT"

native_name='(\.so(\.[0-9]+)*|\.a|\.o|\.obj|\.dylib|\.dll)$'
fixture_allowed='^app/src/test/resources/.*\.apk$'
problems=()
checked=0

while IFS= read -r -d '' f; do
  [[ -f "$f" ]] || continue
  checked=$((checked + 1))
  if [[ "$f" =~ $native_name ]]; then
    problems+=("$f: native library/object file committed")
    continue
  fi
  magic="$(od -An -tx1 -N8 "$f" 2>/dev/null | tr -d ' \n')"
  case "$magic" in
    7f454c46*) problems+=("$f: ELF binary committed (renamed native library?)"); continue ;;
    213c617263683e0a) problems+=("$f: ar static library committed"); continue ;;
  esac
  if [[ "$f" =~ \.(aar|jar|zip|apk|aab)$ && ! "$f" =~ $fixture_allowed ]]; then
    if inner="$(unzip -Z1 "$f" 2>/dev/null | grep -E "$native_name")"; then
      problems+=("$f: package contains native code: $(echo "$inner" | head -3 | tr '\n' ' ')")
    fi
  fi
done < <(git ls-files -z)

while IFS= read -r -d '' g; do
  # Comment lines are ignored (the decoder module's header names the package it replaced).
  if hits="$(grep -nE 'jniLibs|\.aar["'"'"')]|fileTree\(.*\.aar|org\.jellyfin\.media3' "$g" | grep -vE '^[0-9]+:[[:space:]]*(//|/?\*)')"; then
    problems+=("$g: build file uses prebuilt native code or a prebuilt decoder package: $hits")
  fi
done < <(git ls-files -z -- '*.gradle' '*.gradle.kts' 'gradle/*.toml')

if ((${#problems[@]})); then
  for p in "${problems[@]}"; do echo "::error::$p"; done
  echo "Prebuilt native code is not allowed in this repository: FFmpeg and libffmpegJNI.so are built"
  echo "from source (scripts/ffmpeg/build-ffmpeg.sh, pinned in scripts/ffmpeg/versions.env)."
  exit 1
fi
echo "OK: no prebuilt native libraries in $checked tracked files; FFmpeg is built from source."
