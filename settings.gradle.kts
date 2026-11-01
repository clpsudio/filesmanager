pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FilesManager"
include(":app")
// FFmpeg audio decoder built from AndroidX Media 1.8.0 + FFmpeg 6.0 sources (replaces the Jellyfin package).
include(":decoder-ffmpeg")
