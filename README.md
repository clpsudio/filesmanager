# Files

> **Licence: pending review.** No open-source licence has been granted for
> this repository yet. Apache-2.0 is the *proposed* licence for CLP Studio's own
> code; its text is kept in [`LICENSE.pending-review.md`](LICENSE.pending-review.md)
> and is **not in force**; it would cover only CLP Studio's own code. The APK
> as a whole is not under a single licence and is not "entirely Apache-2.0": it
> also contains FFmpeg (LGPL-2.1-or-later) and Junrar (UnRAR licence), among
> others. See [Licence (pending)](#licence-pending).

A native Android file manager by **CLP Studio** (`com.clpstudio.filesmanager`).
Kotlin + Jetpack Compose + Material 3, Android 8.0+ (API 26), fully offline:
the app has **no INTERNET permission** (the CI build fails if one appears).

## What it does (phase 1)

- **Home**: Recent, Photos, Videos, Audios, Documents, Downloads, Apps,
  Internal storage (opens the phone's storage screen), Bin, Vault (phase 2).
  Every category is grouped by folder, with real thumbnails (photos, video
  frames, album art, PDF first pages, APK icons).
- **Storage browser**: copy, move, rename, delete to Bin, share, new folder,
  multi-select, sort (name/date/size/type), grid/list, filter, SD cards,
  show-hidden-files toggle.
- **Search**: by name and/or date (`31/10/2026`, `2026-10-31`, `31 Oct 2026`,
  `Oct 2026`, `2026`, today, yesterday, this week...), quick date chips, custom
  range, type filters. Indexed (MediaStore) + background walk, results stream in.
- **Viewers inside the app**: photos (zoom, swipe), video (Media3, gestures,
  subtitles, screen stays on, sound continues in the background), music
  (background playback, notification, lock-screen controls, queue), PDF, text /
  code, ZIP browsing. Registered as "Open with" for every file type, including
  `content://` files from WhatsApp, Gmail, Telegram, Drive.
- **Bin**: deleted files are kept 30 days, then removed automatically.
- **Liquid glass** look (blur on Android 12+, frosted tint on 8-11), switchable
  to a flat Material look; theme System / Light / Dark.

## Build

Requirements:

- JDK 17 or newer (CI uses Temurin 21);
- the Android SDK with platform `android-36`, **NDK `26.1.10909125` (r26b)** and
  **CMake `3.22.1`** (`sdkmanager "platforms;android-36" "ndk;26.1.10909125" "cmake;3.22.1"`);
  point `ANDROID_HOME` or `sdk.dir` in `local.properties` at it;
- Linux or macOS with `bash`, `make`, `curl` and `tar` (the FFmpeg build is a
  shell script; Windows is not supported for release builds, use WSL);
- internet access for the first build (Gradle, the libraries, and the FFmpeg
  source tarball).

```
git clone https://github.com/clpsudio/filesmanager.git
cd filesmanager
./gradlew :app:testDebugUnitTest :app:lintDebug :decoder-ffmpeg:lintDebug :app:assembleRelease
```

Optional, Linux only: `scripts/ffmpeg/build-host-jni-for-tests.sh` builds a
host copy of the decoder library so that
`./gradlew :decoder-ffmpeg:testDebugUnitTest` decodes real AC-3, E-AC-3, DTS,
MP3, AAC, FLAC, ALAC and G.711 packets through the Java/JNI/FFmpeg path (CI
always runs it; without the host library those tests are skipped).

The first build also downloads FFmpeg at the pinned commit, checks its sha256
and builds its static libraries (about a minute on a fast machine; see
[FFmpeg decoder](#ffmpeg-decoder) below). Later builds reuse them.

The release APK is written to `app/build/outputs/apk/release/`. Without
signing variables it is **unsigned** (`app-release-unsigned.apk`); sign it with
your own key (for example with `apksigner`) before installing. Release signing
reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` from the environment. Never commit a keystore or a
password; `.gitignore` excludes the usual keystore and signing files.
`./gradlew :app:assembleDebug` builds a debug APK signed with your local debug
key.

The APK contains native code for ARM phones only (`arm64-v8a`,
`armeabi-v7a`), so it does not install on x86 emulators.

Screenshots: `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Proborazzi.test.record=true`
(written to `app/build/screenshots`).

Official builds are made by GitHub Actions (`.github/workflows/android-apk.yml`)
and signed by CLP Studio with the certificate
`CN=CLP Studio Files, O=CLP Studio, C=IN`, SHA-256
`febc1f7c94c20f90ba40ba1cb1d58c9c6209ee430b98a60a479852e99e8b353e`. Builds signed
with any other key are not official CLP Studio builds.

## FFmpeg decoder

Audio formats the phone can't decode itself (AC-3, E-AC-3, DTS, TrueHD/MLP,
and others) are played with FFmpeg through Media3's FFmpeg extension. It is
built in this repository by the `:decoder-ffmpeg` module:

- `decoder-ffmpeg/`: AndroidX Media 1.8.0's `decoder_ffmpeg` Java and JNI files,
  unmodified (Apache-2.0); origins and hashes in `decoder-ffmpeg/UPSTREAM.md`.
- `scripts/ffmpeg/versions.env`: every pin (FFmpeg commit and tarball sha256,
  the 11 decoders, NDK, CMake).
- `scripts/ffmpeg/build-ffmpeg.sh`: fetches and checks FFmpeg, then runs
  upstream's unmodified `build_ffmpeg.sh`. Gradle runs it before the native
  build; it skips the work when its inputs haven't changed.
- `scripts/ffmpeg/make-corresponding-source.sh`: writes the FFmpeg
  "corresponding source" bundle that must accompany distributed APKs (LGPL-2.1).
- [`RELINKING.md`](RELINKING.md): how to rebuild `libffmpegJNI.so` with a
  modified FFmpeg and put it into an APK.

### Reproducibility

With the pinned NDK and CMake, two clean builds give byte-identical
`libffmpegJNI.so` files, also from different checkout paths: debug info is
dropped at link time (so the GNU build-ID doesn't depend on paths) and the JNI
glue is compiled with `-ffile-prefix-map`. The stripped libraries are also
byte-identical to those in Jellyfin's `1.8.0+1` package (used up to Files
1.0.23) apart from the 20-byte build-ID. `scripts/ffmpeg/build-jni-standalone.sh`
reproduces the same bytes without Gradle.

## Code map

- `data/` files, categories (MediaStore), search + date parser, Bin, apps, settings
- `ui/glass/` liquid glass system, `ui/screens/` screens, `ui/components/` shared UI
- `viewer/` photo, video, music, PDF, text, ZIP viewers and the "Open with" entry
- `playback/` the media session service shared by music and video
- `Graph.kt` the single place for shared services; phase 2 adds the vault
  (Keystore/StrongBox), app lock and archive tools there.

## Licence (pending)

**Decision pending.** CLP Studio has not yet granted a licence for this
repository. The proposed licence for Files' own source code (Copyright 2026
CLP Studio) is the Apache License, Version 2.0; its unchanged text is kept for
review in [`LICENSE.pending-review.md`](LICENSE.pending-review.md) and is not
in force. Until the decision is confirmed, nothing in this repository grants
permission to use, modify or redistribute the code, and nothing here claims
that the release APK, which also contains third-party components, is
licence-compatible.

If Apache-2.0 is confirmed, redistributing the code or an app built from it
will require (Apache-2.0, section 4):

- a copy of the licence;
- the [`NOTICE`](NOTICE) file (or its text);
- the existing copyright, patent, trademark and attribution notices;
- marking files you changed as changed.

The Apache licence would not grant rights to the CLP Studio name or logo
(section 6). A fork published as a separate app should use its own name,
icon and application ID.

### Third-party components

The app depends on third-party components that keep their own licences. They
are listed, with versions, copyright holders, licences and source links, in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md), and the app shows the
licence texts and notices under **Settings > Open-source licences**. Most are
Apache-2.0, MIT, BSD or 0BSD. Two need particular care when you **distribute an
APK**:

- **FFmpeg** (audio decoders, in `libffmpegJNI.so`) is LGPL-2.1-or-later
  (configured without GPL, version3 or non-free parts; one file, musl's
  `av_sscanf`, is MIT). Anyone distributing an APK must give the LGPL notice
  and licence text, make the corresponding FFmpeg source available (the
  `ffmpeg-corresponding-source` bundle), and allow the library to be replaced
  ([`RELINKING.md`](RELINKING.md)). See `licenses/FFMPEG-NOTICE.txt`.
- **Junrar** (RAR4 archives) is under the UnRAR licence, which is not an
  open-source licence: the code may not be used to re-create the RAR
  compression algorithm, and modified versions must state that the code may
  not be used to develop a RAR (WinRAR) compatible archiver. The UnRAR licence
  is not compatible with the GPL, so the app can't be relicensed under the GPL
  while it includes Junrar. Full text: `licenses/UnRAR-junrar-7.5.5.txt`.

Because of these components, the APK must not be described as "fully open
source" or as "Apache-2.0 licensed" as a whole; Apache-2.0 (once confirmed)
applies only to CLP Studio's own code.

See `THIRD_PARTY_LICENSES.md`, sections 3.1 and 3.2, for the details.
Two unit-test fixtures (`app/src/test/resources/fmt/real_*_prefix.dwg`) are
derived from LibreDWG test data (GPL-3.0-or-later); they are used only by
tests and are not part of the app.

## Contributing

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first: it
covers building, tests, the offline rule, which dependency licences can be
accepted, and how contributions will be licensed once the licence is
confirmed (proposed: Apache-2.0).

## Security

Please do not report security problems in public issues. See
[`SECURITY.md`](SECURITY.md) for how to report a vulnerability privately.

