import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// Release signing comes from the CI secrets (see .github/workflows/android-apk.yml).
// Without them the release APK is built unsigned (local builds).
val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.clpstudio.filesmanager"
    compileSdk = 36
    // The pinned NDK of :decoder-ffmpeg (scripts/ffmpeg/versions.env). The app module uses it only to
    // strip native libraries when packaging, so the APK doesn't depend on which NDKs a machine has.
    ndkVersion = rootProject.file("scripts/ffmpeg/versions.env").readLines()
        .first { it.startsWith("NDK_VERSION=") }.substringAfter('=').trim()

    defaultConfig {
        applicationId = "com.clpstudio.filesmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0.0-dev"
        vectorDrawables.useSupportLibrary = true
        // Phones and tablets only (ARM): halves the size the FFmpeg decoders add (x86 emulators can't install it).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Network is never used: the app has no INTERNET permission at all.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }
}

// Licence texts and notices shown in Settings > Open-source licences (assets/licenses/).
// Copied at build time from the repository root, so the APK always carries exactly the
// texts in the repository: NOTICE, THIRD_PARTY_LICENSES.md and every file in licenses/.
abstract class LicenceAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("licenses")
        out.deleteRecursively()
        out.mkdirs()
        sources.files.sortedBy { it.name }.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
        check(listOf("FFMPEG-NOTICE.txt", "FFMPEG-SOURCE.txt", "FFMPEG-RELINKING.txt", "LGPL-2.1.txt", "MIT-musl-avsscanf.txt", "UnRAR-junrar-7.5.5.txt").all { out.resolve(it).isFile }) { "licence texts missing" }
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<LicenceAssetsTask>("${variant.name}LicenceAssets") {
            sources.from(rootProject.file("NOTICE"), rootProject.file("THIRD_PARTY_LICENSES.md"))
            sources.from(rootProject.fileTree("licenses"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, LicenceAssetsTask::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-process:2.9.2")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    // Media: local playback only (no network modules).
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    // Thumbnails (no network modules).
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-video:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")

    // ZIP with passwords (AES-256 / ZipCrypto). Pure Java, no network code.
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // RAR / 7z archives (pure Java, offline): commons-compress reads 7z, xz supplies its LZMA codecs, junrar reads RAR4.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    // FFmpeg audio decoders for Media3 (offline native libs) — plays codecs the phone's own decoders can't.
    // Built in this repo from AndroidX Media 1.8.0 + FFmpeg 6.0 (LGPL-2.1+), see decoder-ffmpeg/UPSTREAM.md.
    implementation(project(":decoder-ffmpeg"))

    // PDF passwords (open / add / remove, AES-256). Offline; no network permission.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Liquid glass blur.
    implementation("dev.chrisbanes.haze:haze-android:1.6.10")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.50.0")
}
