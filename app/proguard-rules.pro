# Files app: R8 keeps what the code references. Nothing is loaded by reflection
# except the WorkManager worker and the media service, both kept by their
# manifest / WorkManager rules shipped with the libraries.
-keep class com.clpstudio.filesmanager.work.** { *; }
-dontwarn org.slf4j.**

# PdfBox-Android: JPEG2000 support is optional (not bundled); security handlers are created by reflection.
-dontwarn com.gemalto.jp2.**
-keep class com.tom_roush.pdfbox.pdmodel.encryption.** { *; }
-keep class com.tom_roush.pdfbox.filter.** { *; }

# zip4j (locked zips): keep it whole, it's small and some paths are only reached at runtime.
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**
# PdfBox: the parser/writer and document model used to open, lock and unlock PDFs.
-keep class com.tom_roush.pdfbox.cos.** { *; }
-keep class com.tom_roush.pdfbox.pdfparser.** { *; }
-keep class com.tom_roush.pdfbox.pdfwriter.** { *; }

# Final build: keep PdfBox-Android (pdfbox, fontbox, its awt shims) whole. Parts of it are reached by reflection
# (security handlers, filters, font/CMap loaders) and a stripped class only fails at runtime on a locked PDF.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
# Our own offline Word/Excel/PowerPoint parsers and data classes (cheap to keep; nothing breaks if R8 inlines less).
-keep class com.clpstudio.filesmanager.viewer.office.** { *; }
# Crash reports read class names from stack traces: keep line numbers readable.
-keepattributes SourceFile,LineNumberTable

# Archives (7z via commons-compress + xz). Optional codecs commons-compress can call are not bundled.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
-keep class org.tukaani.xz.** { *; }
# FFmpeg decoder (JNI): natives call back into these classes.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
