# Rebuilding or replacing the FFmpeg library (LGPL-2.1 relinking)

Files' audio decoders are FFmpeg (LGPL-2.1-or-later) linked **statically**
into one shared library, `libffmpegJNI.so`, together with AndroidX Media's JNI
glue `ffmpeg_jni.cc` (Apache-2.0). The app's Java/Kotlin code loads that
library at run time (`System.loadLibrary("ffmpegJNI")`). Everything needed to
rebuild it with a modified FFmpeg is published:

| What | Where |
|---|---|
| FFmpeg source, commit `d388c347` | downloaded by `scripts/ffmpeg/fetch-ffmpeg.sh` (pinned sha256), and included as a tarball in the `ffmpeg-corresponding-source` bundle |
| FFmpeg configure options | `decoder-ffmpeg/src/main/jni/build_ffmpeg.sh` (AndroidX Media, unmodified), called by `scripts/ffmpeg/build-ffmpeg.sh` with API 21 and the 11 decoders in `scripts/ffmpeg/versions.env` |
| JNI glue source | `decoder-ffmpeg/src/main/jni/ffmpeg_jni.cc` |
| JNI glue object files | `relink-kit/<abi>/ffmpeg_jni.cc.o` in the bundle, with the exact link command (`relink-kit/<abi>/link-command.txt`) |
| CMake files | `decoder-ffmpeg/src/main/cpp/CMakeLists.txt` (wrapper) and `decoder-ffmpeg/src/main/jni/CMakeLists.txt` (upstream) |
| Toolchain | Android NDK r26b (`26.1.10909125`), CMake 3.22.1 |

The bundle is built by `scripts/ffmpeg/make-corresponding-source.sh` and is
attached to every CI build (artifact `ffmpeg-corresponding-source`) and, once
CLP Studio publishes releases, to each release.

## 1. Build FFmpeg (unmodified or modified)

On Linux or macOS with bash, make, curl and the NDK:

```
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"     # once
export ANDROID_HOME=/path/to/android/sdk
scripts/ffmpeg/build-ffmpeg.sh
```

This unpacks FFmpeg into `decoder-ffmpeg/src/main/jni/ffmpeg/` and writes
`android-libs/<abi>/lib{avcodec,avutil,swresample}.a`. To use a modified
FFmpeg, edit the files in that directory (or replace it with your own tree of
the same FFmpeg libraries), delete `android-libs/`, and run
`decoder-ffmpeg/src/main/jni/build_ffmpeg.sh` yourself (its arguments are
printed by `build-ffmpeg.sh`), or put your own static libraries in
`android-libs/<abi>/`. The JNI glue is written for the FFmpeg 6.0 API.

## 2. Link a new libffmpegJNI.so

Without Gradle:

```
scripts/ffmpeg/build-jni-standalone.sh arm64-v8a
scripts/ffmpeg/build-jni-standalone.sh armeabi-v7a
```

Each writes `build/ffmpeg-jni-standalone/<abi>/libffmpegJNI.stripped.so`.
With the unmodified FFmpeg this is byte-for-byte the library in the official
APK (checked for both ABIs; see "Reproducibility" in `README.md`).

By hand from the object file: `relink-kit/<abi>/link-command.txt` in the
bundle is the exact clang++ command the build ran; replace the `.a` paths with
your libraries.

Or build the whole app: `./gradlew :app:assembleRelease` (README, "Build").

## 3. Put it into an APK

Android installs only signed APKs, so the result carries **your** signature.
Uninstall the official app first (an APK with another signature can't update
it; the app's data on the phone is removed). This is an Android rule, not a
restriction added by CLP Studio.

```
cp files-<version>.apk files-relinked.apk
mkdir -p lib/arm64-v8a lib/armeabi-v7a
cp build/ffmpeg-jni-standalone/arm64-v8a/libffmpegJNI.stripped.so   lib/arm64-v8a/libffmpegJNI.so
cp build/ffmpeg-jni-standalone/armeabi-v7a/libffmpegJNI.stripped.so lib/armeabi-v7a/libffmpegJNI.so
zip -d files-relinked.apk 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/MANIFEST.MF'   # old v1 signature, if any
zip -0 files-relinked.apk lib/arm64-v8a/libffmpegJNI.so lib/armeabi-v7a/libffmpegJNI.so
zipalign -f -P 16 4 files-relinked.apk files-relinked-aligned.apk      # build-tools 35+
apksigner sign --ks your.keystore files-relinked-aligned.apk
adb install files-relinked-aligned.apk
```

Native libraries are stored uncompressed (`zip -0`) and 16 KB page-aligned
(`zipalign -P 16`), as the Android Gradle plugin stores them.
