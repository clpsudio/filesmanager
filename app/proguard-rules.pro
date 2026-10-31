# Files app: R8 keeps what the code references. Nothing is loaded by reflection
# except the WorkManager worker and the media service, both kept by their
# manifest / WorkManager rules shipped with the libraries.
-keep class com.clpstudio.filesmanager.work.** { *; }
-dontwarn org.slf4j.**

# PdfBox-Android: JPEG2000 support is optional (not bundled); security handlers are created by reflection.
-dontwarn com.gemalto.jp2.**
-keep class com.tom_roush.pdfbox.pdmodel.encryption.** { *; }
-keep class com.tom_roush.pdfbox.filter.** { *; }
