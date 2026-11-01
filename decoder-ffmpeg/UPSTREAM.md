# decoder-ffmpeg: where the code comes from

This module builds Media3's FFmpeg audio decoder (`libffmpegJNI.so` plus the
`androidx.media3.decoder.ffmpeg` classes) from upstream sources. It replaces the
prebuilt package `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`, which was
built from the same two sources with the same options.

## 1. AndroidX Media (Apache-2.0), copied unmodified

- Repository: https://github.com/androidx/media
- Tag `1.8.0` = commit `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9` (2025-07-30)
- Directory: `libraries/decoder_ffmpeg/`
- Licence: Apache License 2.0, copyright The Android Open Source Project. Each
  file keeps its original header. Full text: `licenses/Apache-2.0.txt` (the
  `LICENSE` file of that repository at that commit).

| File here | Upstream path | Upstream git blob | sha256 |
|---|---|---|---|
| `src/main/AndroidManifest.xml` | `libraries/decoder_ffmpeg/src/main/AndroidManifest.xml` | `4d7f260868d8bbeaeaaf2e02eccb0e1876c13cde` | `b7b6e92543a6bddffe23364606b48aa9954ae76b5926e3ea6c1fb81dc86853a3` |
| `proguard-rules.txt` | `libraries/decoder_ffmpeg/proguard-rules.txt` | `aaf9280fd9b76c1a5eba14029d4fe2a104746742` | `49fb967a8749fee4dadc2d8b829e919e5ae5034180e4d856df82df95d4659154` |
| `README.upstream.md` | `libraries/decoder_ffmpeg/README.md` | `1d39442f0a9aa5dfe68714aef39ec0fd60b5cccf` | `483cee4d879afbc17a83e33af04c220f3293ea8d3241f04a52bd2616f19f0f45` |
| `src/main/java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoRenderer.java` | `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoRenderer.java` | `82dce022c616a2060f3167907a4eaec785c1ac45` | `07bf6355389c2d55198e8f04277ccb15041f31ef71975c5ef1257722f28a0b65` |
| `src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java` | `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java` | `8182dad297ce6aab11687206eb9bf8420beac471` | `3e29133f941d3f94af40496d05fb18b96754e0066c23a95951baee276524e1e5` |
| `src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java` | `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java` | `421d3adac0498c1a9b74d956e1cfb0b93e8ed4f5` | `340a614e99c0725a38a6c978539fe36fe28a99414da53534a9914e6242a55234` |
| `src/main/java/androidx/media3/decoder/ffmpeg/FfmpegDecoderException.java` | `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegDecoderException.java` | `f8a8b9bee58d46319f173c987831f6e112849fc0` | `9e73968d0802b2d7f8add911ec4dc5a6e621777e997eca74332e173c5463ad7a` |
| `src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java` | `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java` | `e01b04f2ff3f7e75b64156a3794dbc1bf179761e` | `d7d00601c1864f742c3e0d73f7e9b77d9946f2a9dbe5a6e8e255272bc54d2356` |
| `src/main/java/androidx/media3/decoder/ffmpeg/package-info.java` | `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/package-info.java` | `2bd220beea9617c7f93fdec9500cac76e5d60ab2` | `1d75d02151fcec7a1c0036ee4a216e885ae91e1f85e8c9ddfbd72b4def6709be` |
| `src/main/jni/CMakeLists.txt` | `libraries/decoder_ffmpeg/src/main/jni/CMakeLists.txt` | `12cfd49660ff509b554027da88a692b0e4ee9b6b` | `8044f1d53d8f115e5fee59255931ca2886698f7cd7b6a8b03f38539b998b5f3d` |
| `src/main/jni/build_ffmpeg.sh` | `libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh` | `e0a9aa4f8441fbaf67d7a0d7c20043ae96261c30` | `337c88b9240b67e290c361a59c39cb2812c5bfeb5c04833b5f63b227d07c9dc2` |
| `src/main/jni/ffmpeg_jni.cc` | `libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc` | `fe477dba525c3be03b801a8167790bea5e9cd16e` | `d60f46f794883a16cb200f454b004b224d9ec230ff73cb3aa9ee6a8c913fa407` |

`README.upstream.md` is upstream's `README.md`, kept for reference (the build
steps in it are automated here; see below). The 6 Java and 3 JNI/build files
are byte-identical to upstream; `git diff --no-index` against a checkout of
`b7bbc6e` shows no difference.

Upstream's `build.gradle` (Groovy, part of AndroidX Media's multi-project build)
is replaced by `build.gradle.kts`, which declares the same dependencies
(`media3-decoder` as `api`, `media3-exoplayer`, `androidx.annotation`) and the
same minSdk (21).

## 2. FFmpeg (LGPL-2.1-or-later)

- Commit `d388c347d41e4eb516dec05910551c5461e65615` on the `release/6.0`
  branch of https://git.ffmpeg.org/ffmpeg.git (GitHub mirror
  https://github.com/FFmpeg/FFmpeg). `RELEASE` file: `6.0.2`. libavcodec 60.3.100.
- Not stored in this repository. `scripts/ffmpeg/fetch-ffmpeg.sh` downloads the
  tarball of that commit, checks its sha256 and a hash of the unpacked tree
  (both pinned in `scripts/ffmpeg/versions.env`), and unpacks it to
  `src/main/jni/ffmpeg/` (git-ignored).
- Built by upstream's unmodified `src/main/jni/build_ffmpeg.sh`, called by
  `scripts/ffmpeg/build-ffmpeg.sh` with Android API 21 and these 11 decoders:
  `flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd`. No
  `--enable-gpl`, `--enable-version3` or `--enable-nonfree`; libavformat,
  libavfilter, libswscale, libpostproc and libavdevice are disabled.
- Linked: libavcodec, libavutil and libswresample, statically, into
  `libffmpegJNI.so`. All compiled FFmpeg files are LGPL-2.1-or-later except
  `libavutil/avsscanf.c` (MIT, from musl; notice in
  `licenses/MIT-musl-avsscanf.txt`).
- How to rebuild or replace it: `RELINKING.md` in the repository root.

## 3. What CLP Studio added (Apache-2.0, licence decision pending)

- `build.gradle.kts`, `src/main/cpp/CMakeLists.txt` (a wrapper that includes
  upstream's `src/main/jni/CMakeLists.txt` and adds reproducibility flags only),
  this file, and `scripts/ffmpeg/`.

No Jellyfin file is used: Jellyfin's repository only held build scripts and a
Gradle setup around the same two upstream projects.

## 4. Pinned toolchain

Android NDK r26b (`26.1.10909125`), CMake 3.22.1, both from the Android SDK.
The Gradle build checks the NDK version (`android.ndkVersion`) and the FFmpeg
script refuses any other NDK.
