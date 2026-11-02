// FFmpeg audio decoder for Media3, built in this repository from upstream sources:
//   - Java/JNI: AndroidX Media 1.8.0 (commit b7bbc6e), libraries/decoder_ffmpeg, Apache-2.0, unmodified
//   - native:   FFmpeg 6.0 commit d388c347, LGPL-2.1-or-later, 6 audio decoders
//               (flac alac pcm_mulaw pcm_alaw mp3 ac3; no AAC, E-AC-3, DTS or TrueHD)
// It replaces org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1, which packaged the same sources
// (with 11 decoders).
// The package and class names (androidx.media3.decoder.ffmpeg.*) are unchanged, so the
// app's player code is unchanged. Provenance: UPSTREAM.md. Pins: scripts/ffmpeg/versions.env.
//
// Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0.

plugins {
    id("com.android.library")
}

val pins: Map<String, String> = rootProject.file("scripts/ffmpeg/versions.env").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
    .associate { line -> line.substringBefore('=') to line.substringAfter('=').trim('"') }

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = pins.getValue("NDK_VERSION")

    defaultConfig {
        // Same as AndroidX Media 1.8.0 / Jellyfin's build; the app itself needs Android 8.0 (26).
        minSdk = 21
        consumerProguardFiles("proguard-rules.txt")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = pins.getValue("CMAKE_VERSION")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Upstream code, kept verbatim; the app module's lint covers how it is used.
        checkReleaseBuilds = false
        abortOnError = true
        // This module is part of Media3 itself and uses Media3's @UnstableApi classes the way the
        // AndroidX Media project does internally (61 hits, all in the unmodified upstream files).
        // ARM-only is intentional (same ABIs as the app); version warnings are pinned on purpose.
        disable += setOf("UnsafeOptInUsageError", "ChromeOsAbiSupport", "GradleDependency")
    }
}

dependencies {
    // Same dependencies as upstream decoder_ffmpeg (lib-decoder api, lib-exoplayer, annotation).
    api("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.annotation:annotation:1.6.0")
    // Compile-time nullness annotations only (not packaged).
    compileOnly("org.checkerframework:checker-qual:3.43.0")

    testImplementation("junit:junit:4.13.2")
}

// JVM tests load a host (x86_64 Linux) build of the same decoder sources when it exists
// (scripts/ffmpeg/build-host-jni-for-tests.sh); -Pclp.requireHostFfmpeg=true makes it mandatory.
tasks.withType<Test>().configureEach {
    val hostLibDir = rootProject.layout.projectDirectory.dir("build/ffmpeg-host/lib").asFile
    systemProperty("java.library.path", hostLibDir.absolutePath)
    systemProperty("clp.ffmpeg.hostLibDir", hostLibDir.absolutePath)
    systemProperty("clp.ffmpeg.requireHost", (findProperty("clp.requireHostFfmpeg") ?: "false").toString())
}

// Fetch (sha256-checked) and build FFmpeg's static libraries before CMake links libffmpegJNI.so.
val buildFfmpeg = tasks.register<Exec>("buildFfmpeg") {
    group = "build"
    description = "Fetches FFmpeg at the pinned commit and builds its static libraries (scripts/ffmpeg/build-ffmpeg.sh)."
    val script = rootProject.file("scripts/ffmpeg/build-ffmpeg.sh")
    inputs.file(script)
    inputs.file(rootProject.file("scripts/ffmpeg/fetch-ffmpeg.sh"))
    inputs.file(rootProject.file("scripts/ffmpeg/versions.env"))
    inputs.file(file("src/main/jni/build_ffmpeg.sh"))
    outputs.dir(file("src/main/jni/ffmpeg/android-libs"))
    val ndkDir = androidComponents.sdkComponents.ndkDirectory
    doFirst { executable = "bash"; args(script.absolutePath, ndkDir.get().asFile.absolutePath) }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(buildFfmpeg)
}
