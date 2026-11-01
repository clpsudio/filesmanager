# Files

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

Requirements: JDK 17 or newer (CI uses Temurin 21), the Android SDK with
platform `android-36` (point `ANDROID_HOME` or `sdk.dir` in `local.properties`
at it), and internet access for the first build (Gradle and the libraries are
downloaded from Maven Central and Google's Maven repository).

```
git clone https://github.com/clpsudio/filesmanager.git
cd filesmanager
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

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

## Code map

- `data/` files, categories (MediaStore), search + date parser, Bin, apps, settings
- `ui/glass/` liquid glass system, `ui/screens/` screens, `ui/components/` shared UI
- `viewer/` photo, video, music, PDF, text, ZIP viewers and the "Open with" entry
- `playback/` the media session service shared by music and video
- `Graph.kt` the single place for shared services; phase 2 adds the vault
  (Keystore/StrongBox), app lock and archive tools there.

## Licence

Files' own source code is Copyright 2026 CLP Studio and is licensed under the
[Apache License, Version 2.0](LICENSE). You may use, modify and redistribute
it, including commercially, under the terms of that licence.

When you redistribute the code or an app built from it (Apache-2.0, section 4):

- include a copy of [`LICENSE`](LICENSE);
- keep the [`NOTICE`](NOTICE) file (or its text) with your distribution;
- keep the existing copyright, patent, trademark and attribution notices;
- mark files you changed as changed.

The Apache licence does not grant rights to the CLP Studio name or logo
(section 6). A fork published as a separate app should use its own name,
icon and application ID.

### Third-party components

The app depends on third-party libraries that keep their own licences. They
are listed, with versions, copyright holders, licences and source links, in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md). Most are Apache-2.0,
MIT, BSD or 0BSD. Two need particular care when you **distribute an APK**:

- **FFmpeg** (audio decoders) comes in through the Jellyfin Media3 FFmpeg
  Decoder. FFmpeg in that build is LGPL-2.1-or-later (no GPL or non-free parts
  enabled), and the decoder's own code is Apache-2.0, but Jellyfin publishes
  the package under a GPL-3.0 label. An APK must meet the LGPL terms for FFmpeg
  (notice, FFmpeg source for the exact version, replaceable library).
- **Junrar** (RAR4 archives) is under the UnRAR licence, which is not an
  open-source licence: the code may not be used to re-create the RAR
  compression algorithm or to build a RAR-compatible archiver. That licence is
  not compatible with the GPL.

See `THIRD_PARTY_LICENSES.md`, sections 3.1 and 3.2, for the details.
Two unit-test fixtures (`app/src/test/resources/fmt/real_*_prefix.dwg`) are
derived from LibreDWG test data (GPL-3.0-or-later); they are used only by
tests and are not part of the app.

## Contributing

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first: it
covers building, tests, the offline rule, which dependency licences can be
accepted, and how contributions are licensed (Apache-2.0).

## Security

Please do not report security problems in public issues. See
[`SECURITY.md`](SECURITY.md) for how to report a vulnerability privately.

