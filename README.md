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

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Release signing reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` from the environment (GitHub
Actions secrets). Without them the release APK is built unsigned.

Screenshots: `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Proborazzi.test.record=true`
(written to `app/build/screenshots`).

## Code map

- `data/` files, categories (MediaStore), search + date parser, Bin, apps, settings
- `ui/glass/` liquid glass system, `ui/screens/` screens, `ui/components/` shared UI
- `viewer/` photo, video, music, PDF, text, ZIP viewers and the "Open with" entry
- `playback/` the media session service shared by music and video
- `Graph.kt` the single place for shared services; phase 2 adds the vault
  (Keystore/StrongBox), app lock and archive tools there.
