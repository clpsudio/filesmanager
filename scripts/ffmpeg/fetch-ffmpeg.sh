#!/usr/bin/env bash
# Downloads FFmpeg at the pinned commit, checks it, and unpacks it to
# decoder-ffmpeg/src/main/jni/ffmpeg (where AndroidX Media's CMakeLists.txt and
# build_ffmpeg.sh expect it). Nothing is built here.
#
#   scripts/ffmpeg/fetch-ffmpeg.sh          # tarball from FFMPEG_TARBALL_URL, sha256 checked
#   scripts/ffmpeg/fetch-ffmpeg.sh --git    # git fetch of FFMPEG_COMMIT from git.ffmpeg.org instead
#
# Both ways end with the same tree check (FFMPEG_TREE_SHA256).
# Env: FFMPEG_DOWNLOAD_DIR (default build/ffmpeg-download), FFMPEG_TARBALL (use this local file).
#
# Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/scripts/ffmpeg/versions.env"

DEST="$ROOT/decoder-ffmpeg/src/main/jni/ffmpeg"
DL="${FFMPEG_DOWNLOAD_DIR:-$ROOT/build/ffmpeg-download}"
MARK="$DEST/.clp-ffmpeg-source"
WANT="commit=$FFMPEG_COMMIT tree=$FFMPEG_TREE_SHA256"
MODE="${1:-tarball}"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

tree_hash() { # $1 = directory; same recipe as FFMPEG_TREE_SHA256 ("<sha256>  ./<path>" lines, byte-sorted)
  local tool=sha256sum
  command -v sha256sum >/dev/null || tool="shasum -a 256"
  (cd "$1" && LC_ALL=C find . -type f ! -name .clp-ffmpeg-source -print0 | LC_ALL=C sort -z | xargs -0 $tool) \
    | sha256 | cut -d' ' -f1
}

if [[ -f "$MARK" && "$(cat "$MARK")" == "$WANT" ]]; then
  echo "FFmpeg $FFMPEG_COMMIT already unpacked in $DEST"
  exit 0
fi

mkdir -p "$DL"
TMP="$(mktemp -d "$DL/unpack.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

if [[ "$MODE" == "--git" ]]; then
  git -C "$TMP" init -q src
  git -C "$TMP/src" fetch -q --depth 1 "$FFMPEG_GIT_URL" "$FFMPEG_COMMIT"
  got="$(git -C "$TMP/src" rev-parse FETCH_HEAD)"
  [[ "$got" == "$FFMPEG_COMMIT" ]] || { echo "error: fetched $got, expected $FFMPEG_COMMIT" >&2; exit 1; }
  mkdir "$TMP/tree"
  git -C "$TMP/src" archive --format=tar FETCH_HEAD | tar -x -C "$TMP/tree"
else
  TARBALL="${FFMPEG_TARBALL:-$DL/$FFMPEG_TARBALL_NAME}"
  if [[ ! -f "$TARBALL" ]]; then
    echo "Downloading $FFMPEG_TARBALL_URL"
    curl -fL --retry 3 -o "$TARBALL.part" "$FFMPEG_TARBALL_URL"
    mv "$TARBALL.part" "$TARBALL"
  fi
  got="$(sha256 "$TARBALL" | cut -d' ' -f1)"
  if [[ "$got" != "$FFMPEG_TARBALL_SHA256" ]]; then
    echo "error: $TARBALL has sha256 $got, expected $FFMPEG_TARBALL_SHA256" >&2
    echo "       (if the download host re-compressed it, retry with: $0 --git)" >&2
    exit 1
  fi
  mkdir "$TMP/tree"
  tar -xzf "$TARBALL" -C "$TMP/tree" --strip-components=1
fi

got="$(tree_hash "$TMP/tree")"
if [[ "$got" != "$FFMPEG_TREE_SHA256" ]]; then
  echo "error: FFmpeg source tree hash $got, expected $FFMPEG_TREE_SHA256" >&2
  exit 1
fi

rm -rf "$DEST"
mv "$TMP/tree" "$DEST"
echo "$WANT" > "$MARK"
echo "FFmpeg $FFMPEG_COMMIT verified (tree $got) and unpacked in $DEST"
