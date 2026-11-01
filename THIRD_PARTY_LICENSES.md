# Third-party licences and notices

> **Licence decision pending.** The project licence has not been decided: the
> proposed Apache-2.0 text is kept, not in force, in `LICENSE.pending-review.md`,
> and it would apply only to CLP Studio's own code. The APK as a whole is not
> under one licence and is not "entirely Apache-2.0": it contains components
> under their own licences, including FFmpeg (LGPL-2.1-or-later, section 3.1)
> and Junrar (UnRAR licence, section 3.2).

Files (`com.clpstudio.filesmanager`) is built from CLP Studio's own source code plus the third-party
components listed here. This file covers:

1. the libraries declared in `app/build.gradle.kts` (direct dependencies),
2. every library that ends up in the release APK (the resolved `releaseRuntimeClasspath`: 204 coordinates at `783e7a7`;
   203 external coordinates plus the in-repository `:decoder-ffmpeg` module after the Jellyfin package was removed),
3. the components with special conditions (FFmpeg, Junrar/UnRAR, PdfBox-Android resources, fonts),
4. build-only and test-only components, and the third-party test fixtures in this repository.

How this list was made (1 November 2026, repository state `783e7a7`): `./gradlew :app:dependencies
--configuration releaseRuntimeClasspath`, then the licence of every resolved module was read from its
published POM (or its parent POM), and checked against the upstream LICENSE/NOTICE files where the POM was
missing, vague or unusual (Bouncy Castle, Junrar, PdfBox-Android, XZ, Haze, Zip4j, Coil). The
native FFmpeg library was inspected directly (see 3.1). When you change dependencies, regenerate this list.

Update (1 November 2026, Option 1): the prebuilt Jellyfin package `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`
was replaced by the in-repository module `:decoder-ffmpeg`, built from AndroidX Media 1.8.0 and FFmpeg
`d388c347` (section 3.1). No Jellyfin code or package is used any more.

The licence texts referred to below are available at the linked URLs. The Apache License 2.0 text is also in
this repository's `LICENSE.pending-review.md` file (kept for review; not in force as the project licence).

## 1. Direct dependencies

| Library | Version (resolved) | Licence | Copyright holder(s) | Source |
|---|---|---|---|---|
| Jetpack Compose UI `androidx.compose.ui:ui` | 1.9.0 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.compose.ui:ui-graphics` | 1.9.0 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.compose.ui:ui-tooling-preview` | 1.9.0 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.compose.foundation:foundation` | 1.9.0 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.compose.animation:animation` | 1.9.0 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.compose.material3:material3` | 1.3.2 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.compose.material:material-icons-extended` | 1.7.8 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.core:core-ktx` | 1.16.0 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.core:core-splashscreen` | 1.0.1 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.2 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.2 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.lifecycle:lifecycle-process` | 2.9.2 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.navigation:navigation-compose` | 2.9.3 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.datastore:datastore-preferences` | 1.1.7 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.work:work-runtime-ktx` | 2.10.3 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.profileinstaller:profileinstaller` | 1.4.1 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `androidx.exifinterface:exifinterface` | 1.4.1 | Apache-2.0 | The Android Open Source Project | https://android.googlesource.com/platform/frameworks/support |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 | JetBrains s.r.o. and Kotlin Programming Language contributors | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx:kotlinx-coroutines-guava` | 1.10.2 | Apache-2.0 | JetBrains s.r.o. and Kotlin Programming Language contributors | https://github.com/Kotlin/kotlinx.coroutines |
| `androidx.media3:media3-exoplayer` | 1.8.0 | Apache-2.0 | The Android Open Source Project | https://github.com/androidx/media |
| `androidx.media3:media3-session` | 1.8.0 | Apache-2.0 | The Android Open Source Project | https://github.com/androidx/media |
| `androidx.media3:media3-ui` | 1.8.0 | Apache-2.0 | The Android Open Source Project | https://github.com/androidx/media |
| `io.coil-kt.coil3:coil-compose` | 3.3.0 | Apache-2.0 | Coil Contributors | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3:coil-video` | 3.3.0 | Apache-2.0 | Coil Contributors | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3:coil-gif` | 3.3.0 | Apache-2.0 | Coil Contributors | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3:coil-svg` | 3.3.0 | Apache-2.0 | Coil Contributors | https://github.com/coil-kt/coil |
| Zip4j `net.lingala.zip4j:zip4j` | 2.11.5 | Apache-2.0 | Srikanth Reddy Lingala | https://github.com/srikanth-lingala/zip4j |
| Apache Commons Compress `org.apache.commons:commons-compress` | 1.28.0 | Apache-2.0 | The Apache Software Foundation | https://commons.apache.org/ |
| XZ for Java `org.tukaani:xz` | 1.10 | 0BSD | The XZ for Java authors and contributors | https://github.com/tukaani-project/xz-java |
| Junrar `com.github.junrar:junrar` | 7.5.5 | UnRAR licence (not OSI-approved; see section 3.2) | Alexander L. Roshal (UnRAR); junrar contributors | https://github.com/junrar/junrar |
| FFmpeg decoder module `:decoder-ffmpeg` (in this repository) | AndroidX Media 1.8.0 + FFmpeg 6.0 (`d388c347`) | Apache-2.0 (AndroidX Media Java/JNI code) + LGPL-2.1-or-later (FFmpeg; one MIT file), see section 3.1 | The Android Open Source Project (decoder code); FFmpeg developers (native code); Rich Felker et al. (musl `av_sscanf`) | https://github.com/androidx/media/tree/1.8.0/libraries/decoder_ffmpeg, https://git.ffmpeg.org/ffmpeg.git |
| PdfBox-Android `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache-2.0 | The Apache Software Foundation; Tom Roush (Android port) | https://github.com/TomRoush/PdfBox-Android |
| Haze `dev.chrisbanes.haze:haze-android` | 1.6.10 | Apache-2.0 | Chris Banes | https://github.com/chrisbanes/haze |
| Kotlin standard library `org.jetbrains.kotlin:kotlin-stdlib` (added by the Kotlin plugin) | 2.2.20 | Apache-2.0 | JetBrains s.r.o. and Kotlin Programming Language contributors | https://github.com/JetBrains/kotlin |

`androidx.compose:compose-bom:2025.08.00` (Apache-2.0) only pins versions; it ships no code.

## 2. Licence summary of everything in the release APK

| Licence | Modules | Examples |
|---|---|---|
| Apache-2.0 | 195 | AndroidX, Jetpack Compose, Media3, Kotlin, kotlinx.coroutines, Coil, Haze, Zip4j, Apache Commons (Compress, IO, Codec, Lang), PdfBox-Android, Guava, Okio, AndroidSVG, Accompanist |
| MIT | 2 | Checker Framework qualifiers (`checker-qual` 3.43.0), SLF4J API 1.7.36 |
| MIT-style (Bouncy Castle Licence) | 3 | `bcprov`, `bcpkix`, `bcutil` (`-jdk15to18` 1.72), pulled in by PdfBox-Android |
| BSD-3-Clause | 1 | `datastore-preferences-external-protobuf` 1.1.7 (repackaged Protocol Buffers) |
| 0BSD | 1 | XZ for Java 1.10 |
| UnRAR licence | 1 | Junrar 7.5.5 |
| Apache-2.0 (Java/JNI glue) + LGPL-2.1-or-later (FFmpeg, one MIT file) | 1 | `:decoder-ffmpeg` (AndroidX Media 1.8.0 decoder_ffmpeg + FFmpeg 6.0), built in this repository |

The full list is in section 5.

## 3. Components with special conditions

### 3.1 FFmpeg decoder (`:decoder-ffmpeg`): AndroidX Media 1.8.0 + FFmpeg 6.0

Built in this repository (module `decoder-ffmpeg/`, provenance in `decoder-ffmpeg/UPSTREAM.md`). It
replaces the prebuilt `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`, which packaged the same two
upstream sources; no Jellyfin file is used.

- **Java classes and JNI glue**: `androidx.media3.decoder.ffmpeg` (6 Java files) and `ffmpeg_jni.cc`,
  `CMakeLists.txt`, `build_ffmpeg.sh`, copied unmodified from AndroidX Media (https://github.com/androidx/media,
  tag `1.8.0` = commit `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`, `libraries/decoder_ffmpeg/`).
  Copyright The Android Open Source Project, **Apache-2.0** (text: `licenses/Apache-2.0.txt`).
- **Native library** `libffmpegJNI.so` (arm64-v8a and armeabi-v7a only): `ffmpeg_jni.cc` statically linked with
  FFmpeg's libavcodec, libavutil and libswresample.
- **FFmpeg version**: release/6.0 branch, commit `d388c347d41e4eb516dec05910551c5461e65615` (`RELEASE` 6.0.2,
  libavcodec 60.3.100). Tarball sha256 `f89916923b8b935d1f0aaf8b77d21cb0c507c270d66c00d0f256a62cc7ff22ec`
  (pinned in `scripts/ffmpeg/versions.env`). Built with Android NDK r26b (`26.1.10909125`, clang 17.0.2).
- **FFmpeg configuration**: AndroidX Media's unmodified `build_ffmpeg.sh` (`--disable-everything
  --enable-swresample --disable-avformat --disable-avfilter --disable-swscale --disable-postproc
  --disable-avdevice --disable-programs --enable-static --disable-shared ...`), API level 21, and only these
  decoders: `flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd`. **No `--enable-gpl`, no
  `--enable-version3`, no `--enable-nonfree`**, no external libraries. FFmpeg built this way is licensed under
  the **GNU Lesser General Public License, version 2.1 or later** (FFmpeg's `LICENSE.md`). Copyright the
  FFmpeg developers. Full licence text: `licenses/LGPL-2.1.txt` (FFmpeg's own `COPYING.LGPLv2.1`).
- **One MIT file**: `libavutil/avsscanf.c` (`av_sscanf`) is derived from musl libc, Copyright (c) 2005-2014
  Rich Felker, et al., MIT licence. Notice reproduced in full in `licenses/MIT-musl-avsscanf.txt`. All other
  compiled FFmpeg files are LGPL-2.1-or-later (no GPL-only file is compiled; see the evidence report).
- **The same binary as before**: the stripped `libffmpegJNI.so` built here is byte-identical to the one in
  Jellyfin's 1.8.0+1 package (and in Files 1.0.23) for both ABIs, apart from the 20-byte GNU build-ID.
- **LGPL-2.1 obligations and how this repository meets them** (wording to be confirmed by a lawyer):
  - notice and licence text: `NOTICE`, `licenses/FFMPEG-NOTICE.txt`, `licenses/LGPL-2.1.txt`, shown in the
    app under Settings > Open-source licences;
  - corresponding source: `scripts/ffmpeg/make-corresponding-source.sh` writes the bundle (FFmpeg source
    tarball, build scripts and configure options, decoder module source, JNI object files, relinking steps);
    CI attaches it to every build as `ffmpeg-corresponding-source`, and it is to be attached to every
    published release; `licenses/FFMPEG-SOURCE.txt` says where it is published, gives the exact build
    configuration and contains a written offer;
  - relinking: FFmpeg is linked statically into `libffmpegJNI.so`, which is itself a separate shared library
    in the APK; `RELINKING.md` and `scripts/ffmpeg/build-jni-standalone.sh` show how to rebuild it with a
    modified FFmpeg and replace it as a whole (summary in the app: `licenses/FFMPEG-RELINKING.txt`).

### 3.2 Junrar 7.5.5 (UnRAR licence)

Junrar is a Java port of the UnRAR source code. It is distributed under the UnRAR licence, which is not an
OSI-approved open-source licence: it allows use in any software to handle RAR archives, but forbids using
the code to re-create the RAR compression algorithm or to develop a RAR (WinRAR) compatible archiver. The
licence is not compatible with the GNU GPL. Source: https://github.com/junrar/junrar (tag `v7.5.5`).
Junrar is used unmodified (Maven `com.github.junrar:junrar:7.5.5`). Its restriction applies to anyone who
reuses or modifies it: **the code may not be used to re-create the RAR compression algorithm, and modified
copies must state, in their documentation and source comments, that the code may not be used to develop a
RAR (WinRAR) compatible archiver.** The text is also in `licenses/UnRAR-junrar-7.5.5.txt` and in the app
(Settings > Open-source licences).
Its licence text, reproduced in full as required:

```
 ******    *****   ******   UnRAR - free utility for RAR archives
 **   **  **   **  **   **  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ******   *******  ******    License for use and distribution of
 **   **  **   **  **   **   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 **   **  **   **  **   **         FREE portable version
                                   ~~~~~~~~~~~~~~~~~~~~~

      The source code of UnRAR utility is freeware. This means:

   1. All copyrights to RAR and the utility UnRAR are exclusively
      owned by the author - Alexander Roshal.

   2. The UnRAR sources may be used in any software to handle RAR
      archives without limitations free of charge, but cannot be used
      to re-create the RAR compression algorithm, which is proprietary.
      Distribution of modified UnRAR sources in separate form or as a
      part of other software is permitted, provided that it is clearly
      stated in the documentation and source comments that the code may
      not be used to develop a RAR (WinRAR) compatible archiver.

   3. The UnRAR utility may be freely distributed. It is allowed
      to distribute UnRAR inside of other software packages.

   4. THE RAR ARCHIVER AND THE UnRAR UTILITY ARE DISTRIBUTED "AS IS".
      NO WARRANTY OF ANY KIND IS EXPRESSED OR IMPLIED.  YOU USE AT 
      YOUR OWN RISK. THE AUTHOR WILL NOT BE LIABLE FOR DATA LOSS, 
      DAMAGES, LOSS OF PROFITS OR ANY OTHER KIND OF LOSS WHILE USING
      OR MISUSING THIS SOFTWARE.

   5. Installing and using the UnRAR utility signifies acceptance of
      these terms and conditions of the license.

   6. If you don't agree with terms of the license you must remove
      UnRAR files from your storage devices and cease to use the
      utility.

      Thank you for your interest in RAR and UnRAR.


                                            Alexander L. Roshal
```

### 3.3 PdfBox-Android 2.0.27.0 (Apache PDFBox) and its bundled resources

Licence Apache-2.0. Source: https://github.com/TomRoush/PdfBox-Android. Its NOTICE:

```
Apache PDFBox
Copyright 2014 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).

Based on source code originally developed in the PDFBox and
FontBox projects.

Copyright (c) 2002-2007, www.pdfbox.org

Based on source code originally developed in the PaDaF project.
Copyright (c) 2010 Atos Worldline SAS

Includes the Adobe Glyph List
Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.

Includes the Zapf Dingbats Glyph List
Copyright 2002, 2010 Adobe Systems Incorporated.

Includes the Script Property (Scripts-10.0.0.txt)
Copyright 2017 Unicode, Inc.
```

The library puts these files into the APK's `assets/` folder, each under its own terms (see the
"EXTERNAL COMPONENTS" part of https://github.com/TomRoush/PdfBox-Android/blob/master/LICENSE.txt):

- **Adobe Font Metrics for the 14 PDF core fonts** (14 `.afm` files). Adobe's terms:

```
   This file and the 14 PostScript(R) AFM files it accompanies may be used,
   copied, and distributed for any purpose and without charge, with or without
   modification, provided that all copyright notices are retained; that the
   AFM files are not distributed without this file; that all modifications
   to this file or any of the AFM files are prominently noted in the modified
   file(s); and that this paragraph is not modified. Adobe Systems has no
   responsibility or obligation to support the use of the AFM files.
```

- **Adobe CMaps** (92 files), Copyright 1990-2009 Adobe Systems Incorporated, BSD-3-Clause.
- **Adobe Glyph List and Zapf Dingbats Glyph List**, Copyright Adobe Systems Incorporated, BSD-3-Clause.
- **Unicode data** (`Scripts.txt`, `BidiMirroring.txt`), Copyright Unicode, Inc., Unicode licence
  (https://www.unicode.org/license.txt).
- **Liberation Sans Regular 2.1.5** font (`LiberationSans-Regular.ttf`), "Digitized data copyright (c) 2010
  Google Corporation. Copyright (c) 2012 Red Hat, Inc.", licensed under the **SIL Open Font License 1.1**
  (https://openfontlicense.org). The OFL lets the font be bundled with software; the copyright notice and
  licence must accompany it, and the font itself may not be sold on its own.
- PDFBox/FontBox original code (Copyright (c) 2002-2007, www.pdfbox.org, BSD-3-Clause) and PaDaF
  (Copyright 2010 Atos Worldline SAS, Apache-2.0).

### 3.4 Bouncy Castle 1.72 (`bcprov`, `bcpkix`, `bcutil`, `-jdk15to18`)

Pulled in by PdfBox-Android for PDF encryption. Their POMs name no licence; the upstream licence
(https://github.com/bcgit/bc-java/blob/r1rv72/LICENSE.html) is:

```
Copyright (c) 2000-2021 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
and associated documentation files (the "Software"), to deal in the Software without restriction, 
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### 3.5 XZ for Java 1.10 (0BSD)

```
Copyright (C) The XZ for Java authors and contributors

Permission to use, copy, modify, and/or distribute this
software for any purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL
THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR
CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### 3.6 Apache Commons (pulled in by Commons Compress 1.28.0)

Commons Compress 1.28.0, Commons IO 2.20.0, Commons Codec 1.19.0 and Commons Lang 3.18.0 are Apache-2.0
and each ships a NOTICE file ("Copyright 2001/2002-2025 The Apache Software Foundation. This product
includes software developed at The Apache Software Foundation (https://www.apache.org/)."). These notices
are reproduced in this repository's `NOTICE` file.

### 3.7 Fonts and icons inside other libraries

- `androidx.media3:media3-ui` 1.8.0 contains `roboto_medium_numbers.ttf` (Roboto 2.137, Copyright 2011
  Google Inc.), distributed by AndroidX Media under Apache-2.0.
- Material icons come from `androidx.compose.material:material-icons-extended` 1.7.8 (Apache-2.0).
- The app's own launcher icon and logo are CLP Studio's (see `NOTICE`). The app bundles no other fonts,
  images or sounds.

### 3.8 Other notices

- Checker Framework qualifiers 3.43.0 (MIT), https://github.com/typetools/checker-framework, via Guava.
- SLF4J API 1.7.36 (MIT), Copyright (c) 2004-2017 QOS.ch, https://github.com/qos-ch/slf4j, via Junrar.
- `androidx.datastore:datastore-preferences-external-protobuf` 1.1.7 contains a repackaged copy of
  Protocol Buffers, Copyright Google Inc., BSD-3-Clause.

## 4. Build-only and test-only components (not in the APK)

| Component | Version | Licence |
|---|---|---|
| Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`, checksum verified against gradle.org) | 8.14.3 | Apache-2.0 |
| Android Gradle Plugin | 8.13.0 | Apache-2.0 |
| Kotlin Gradle plugin and Compose compiler plugin | 2.2.20 | Apache-2.0 |
| Roborazzi (plugin, `roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule`) | 1.50.0 | Apache-2.0 |
| JUnit | 4.13.2 | Eclipse Public License 1.0 |
| Robolectric | 4.16 | MIT |
| AndroidX Test (`core-ktx` 1.6.1, `ext:junit` 1.2.1), Compose UI test, `ui-tooling`, `ui-test-manifest` | see `app/build.gradle.kts` | Apache-2.0 |
| kotlinx-coroutines-test | 1.10.2 | Apache-2.0 |

Test fixtures in `app/src/test/resources/` that did not originate with CLP Studio:

- `legacy/*.doc`, `legacy/*.xls`, `legacy/*.ppt` (9 files): byte-for-byte copies of files from Apache POI's
  `test-data` folder (https://github.com/apache/poi/tree/trunk/test-data), distributed by the Apache
  Software Foundation under Apache-2.0.
- `fmt/real_2018_prefix.dwg` and `fmt/real_r2000_prefix.dwg`: the first bytes (header and preview image)
  of `test/test-data/example_2018.dwg` and `example_2000.dwg` from LibreDWG
  (https://github.com/LibreDWG/libredwg), a project licensed GPL-3.0-or-later. These two files are not part
  of the app and are only used by unit tests, but their licence differs from the project licence; see the
  open item in `README.md`.
- `docx/word_sample.docx` and `docx/with_thumbnail.docx` were generated with python-docx and contain its
  default document template (python-docx is MIT-licensed).
- The other fixtures (archives, PSD, AI, APK, DOCX/DOC samples) were generated for this project.

## 5. Full inventory of the release runtime classpath

Resolved versions of `releaseRuntimeClasspath` (direct and transitive). "Metadata only" entries are Kotlin
Multiplatform or BOM coordinates that resolve to the `-android`/`-jvm` artifact listed next to them.

| Module | Version | Licence |
|---|---|---|
| `androidx.activity:activity` | 1.10.1 | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 |
| `androidx.activity:activity-ktx` | 1.10.1 | Apache-2.0 |
| `androidx.annotation:annotation` | 1.9.1 | Apache-2.0 (metadata only, no code) |
| `androidx.annotation:annotation-experimental` | 1.4.1 | Apache-2.0 |
| `androidx.annotation:annotation-jvm` | 1.9.1 | Apache-2.0 |
| `androidx.appcompat:appcompat-resources` | 1.7.1 | Apache-2.0 |
| `androidx.arch.core:core-common` | 2.2.0 | Apache-2.0 |
| `androidx.arch.core:core-runtime` | 2.2.0 | Apache-2.0 |
| `androidx.autofill:autofill` | 1.0.0 | Apache-2.0 |
| `androidx.collection:collection` | 1.5.0 | Apache-2.0 (metadata only, no code) |
| `androidx.collection:collection-jvm` | 1.5.0 | Apache-2.0 |
| `androidx.collection:collection-ktx` | 1.5.0 | Apache-2.0 |
| `androidx.compose:compose-bom` | 2025.08.00 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.animation:animation` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.animation:animation-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.animation:animation-core` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.animation:animation-core-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.foundation:foundation` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.foundation:foundation-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.foundation:foundation-layout` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.foundation:foundation-layout-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material:material-icons-core` | 1.7.8 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material:material-icons-core-android` | 1.7.8 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material:material-icons-extended` | 1.7.8 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material:material-icons-extended-android` | 1.7.8 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material:material-ripple` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material:material-ripple-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material3:material3` | 1.3.2 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.material3:material3-android` | 1.3.2 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.runtime:runtime` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.runtime:runtime-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.runtime:runtime-annotation` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.runtime:runtime-annotation-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.runtime:runtime-saveable` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.runtime:runtime-saveable-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-geometry` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-geometry-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-graphics` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-graphics-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-text` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-text-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-tooling-preview` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-tooling-preview-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-unit` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-unit-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-util` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.compose.ui:ui-util-android` | 1.9.0 | Apache-2.0 (metadata only, no code) |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | Apache-2.0 |
| `androidx.concurrent:concurrent-futures-ktx` | 1.1.0 | Apache-2.0 |
| `androidx.core:core` | 1.16.0 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.16.0 | Apache-2.0 |
| `androidx.core:core-splashscreen` | 1.0.1 | Apache-2.0 |
| `androidx.core:core-viewtree` | 1.0.0 | Apache-2.0 |
| `androidx.customview:customview` | 1.0.0 | Apache-2.0 |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 | Apache-2.0 |
| `androidx.datastore:datastore` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-android` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-core` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-core-android` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-core-okio` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-core-okio-jvm` | 1.1.7 | Apache-2.0 |
| `androidx.datastore:datastore-preferences` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-preferences-android` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-preferences-core` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-preferences-core-android` | 1.1.7 | Apache-2.0 (metadata only, no code) |
| `androidx.datastore:datastore-preferences-external-protobuf` | 1.1.7 | BSD-3-Clause |
| `androidx.datastore:datastore-preferences-proto` | 1.1.7 | Apache-2.0 |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache-2.0 |
| `androidx.exifinterface:exifinterface` | 1.4.1 | Apache-2.0 |
| `androidx.graphics:graphics-path` | 1.0.1 | Apache-2.0 |
| `androidx.interpolator:interpolator` | 1.0.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-common-java8` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata-core-ktx` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-process` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-runtime-android` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-service` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-viewmodel-android` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.9.2 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.9.2 | Apache-2.0 (metadata only, no code) |
| `androidx.media:media` | 1.7.0 | Apache-2.0 |
| `androidx.media3:media3-common` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-container` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-database` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-datasource` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-decoder` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-exoplayer` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-extractor` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-session` | 1.8.0 | Apache-2.0 |
| `androidx.media3:media3-ui` | 1.8.0 | Apache-2.0 |
| `androidx.navigation:navigation-common` | 2.9.3 | Apache-2.0 (metadata only, no code) |
| `androidx.navigation:navigation-common-android` | 2.9.3 | Apache-2.0 (metadata only, no code) |
| `androidx.navigation:navigation-compose` | 2.9.3 | Apache-2.0 (metadata only, no code) |
| `androidx.navigation:navigation-compose-android` | 2.9.3 | Apache-2.0 (metadata only, no code) |
| `androidx.navigation:navigation-runtime` | 2.9.3 | Apache-2.0 (metadata only, no code) |
| `androidx.navigation:navigation-runtime-android` | 2.9.3 | Apache-2.0 (metadata only, no code) |
| `androidx.profileinstaller:profileinstaller` | 1.4.1 | Apache-2.0 |
| `androidx.recyclerview:recyclerview` | 1.3.0 | Apache-2.0 |
| `androidx.room:room-common` | 2.6.1 | Apache-2.0 |
| `androidx.room:room-ktx` | 2.6.1 | Apache-2.0 |
| `androidx.room:room-runtime` | 2.6.1 | Apache-2.0 |
| `androidx.savedstate:savedstate` | 1.3.1 | Apache-2.0 (metadata only, no code) |
| `androidx.savedstate:savedstate-android` | 1.3.1 | Apache-2.0 (metadata only, no code) |
| `androidx.savedstate:savedstate-compose` | 1.3.1 | Apache-2.0 (metadata only, no code) |
| `androidx.savedstate:savedstate-compose-android` | 1.3.1 | Apache-2.0 (metadata only, no code) |
| `androidx.savedstate:savedstate-ktx` | 1.3.1 | Apache-2.0 |
| `androidx.sqlite:sqlite` | 2.4.0 | Apache-2.0 |
| `androidx.sqlite:sqlite-framework` | 2.4.0 | Apache-2.0 |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache-2.0 |
| `androidx.tracing:tracing` | 1.3.0 | Apache-2.0 (metadata only, no code) |
| `androidx.tracing:tracing-android` | 1.3.0 | Apache-2.0 (metadata only, no code) |
| `androidx.tracing:tracing-ktx` | 1.3.0 | Apache-2.0 |
| `androidx.vectordrawable:vectordrawable` | 1.2.0 | Apache-2.0 |
| `androidx.vectordrawable:vectordrawable-animated` | 1.2.0 | Apache-2.0 |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | Apache-2.0 |
| `androidx.work:work-runtime` | 2.10.3 | Apache-2.0 |
| `androidx.work:work-runtime-ktx` | 2.10.3 | Apache-2.0 |
| `com.caverock:androidsvg-aar` | 1.4 | Apache-2.0 |
| `com.github.junrar:junrar` | 7.5.5 | UnRAR licence (not OSI-approved; see section 3.2) |
| `com.google.accompanist:accompanist-drawablepainter` | 0.37.3 | Apache-2.0 |
| `com.google.code.findbugs:jsr305` | 3.0.2 | Apache-2.0 |
| `com.google.errorprone:error_prone_annotations` | 2.28.0 | Apache-2.0 |
| `com.google.guava:failureaccess` | 1.0.2 | Apache-2.0 |
| `com.google.guava:guava` | 33.3.1-android | Apache-2.0 (metadata only, no code) |
| `com.google.guava:listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |
| `com.google.j2objc:j2objc-annotations` | 3.0.0 | Apache-2.0 |
| `com.squareup.okio:okio` | 3.15.0 | Apache-2.0 (metadata only, no code) |
| `com.squareup.okio:okio-jvm` | 3.15.0 | Apache-2.0 |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache-2.0 |
| `commons-codec:commons-codec` | 1.19.0 | Apache-2.0 |
| `commons-io:commons-io` | 2.20.0 | Apache-2.0 |
| `dev.chrisbanes.haze:haze-android` | 1.6.10 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-android` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-compose` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-compose-android` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-compose-core` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-compose-core-android` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-core` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-core-android` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-gif` | 3.3.0 | Apache-2.0 |
| `io.coil-kt.coil3:coil-svg` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-svg-android` | 3.3.0 | Apache-2.0 (metadata only, no code) |
| `io.coil-kt.coil3:coil-video` | 3.3.0 | Apache-2.0 |
| `net.lingala.zip4j:zip4j` | 2.11.5 | Apache-2.0 |
| `org.apache.commons:commons-compress` | 1.28.0 | Apache-2.0 |
| `org.apache.commons:commons-lang3` | 3.18.0 | Apache-2.0 |
| `org.bouncycastle:bcpkix-jdk15to18` | 1.72 | MIT (Bouncy Castle Licence) |
| `org.bouncycastle:bcprov-jdk15to18` | 1.72 | MIT (Bouncy Castle Licence) |
| `org.bouncycastle:bcutil-jdk15to18` | 1.72 | MIT (Bouncy Castle Licence) |
| `org.checkerframework:checker-qual` | 3.43.0 | MIT |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 |
| `org.jetbrains.androidx.lifecycle:lifecycle-common` | 2.8.4 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime` | 2.8.4 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | 2.8.4 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel` | 2.8.4 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.animation:animation` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.animation:animation-core` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.annotation-internal:annotation` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.collection-internal:collection` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.foundation:foundation` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.foundation:foundation-layout` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.runtime:runtime` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.runtime:runtime-saveable` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.ui:ui` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.ui:ui-geometry` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.ui:ui-graphics` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.ui:ui-text` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.ui:ui-unit` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.compose.ui:ui-util` | 1.8.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlin:kotlin-android-extensions-runtime` | 1.9.22 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-bom` | 1.8.0 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlin:kotlin-parcelize-runtime` | 1.9.22 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 2.2.20 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7` | 1.8.22 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | 1.8.22 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.10.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-guava` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.7.3 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | Apache-2.0 (metadata only, no code) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.7.3 | Apache-2.0 |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 |
| `org.slf4j:slf4j-api` | 1.7.36 | MIT |
| `org.tukaani:xz` | 1.10 | 0BSD |
