package com.clpstudio.filesmanager.data

import java.util.Locale

/** What kind of file something is, decided by its extension (fast, no I/O). */
enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOCUMENT, APK, ARCHIVE, OTHER }

object FileTypes {
    val imageExt = setOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg", "ico", "dng")
    val videoExt = setOf(
        "mp4", "mkv", "avi", "webm", "3gp", "3gpp", "3g2", "mov", "ts", "m2ts", "mts", "flv", "m4v",
        "wmv", "mpg", "mpeg", "vob", "ogv", "f4v", "divx", "rmvb", "asf",
    )
    val audioExt = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "awb", "wma", "mid", "midi",
        "aiff", "aif", "3ga", "m4b", "mka", "ac3", "weba",
    )
    val textExt = setOf(
        "txt", "log", "json", "xml", "csv", "tsv", "md", "markdown", "html", "htm", "css", "js", "kt", "kts",
        "java", "py", "sh", "bat", "yaml", "yml", "ini", "conf", "cfg", "properties", "srt", "vtt", "ass",
        "ssa", "sql", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "dart", "php", "rb", "gradle",
        "toml", "nfo", "lrc", "tex",
    )
    val officeExt = setOf(
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "pages",
        "numbers", "key", "wps", "xlsm", "dotx",
    )
    val apkExt = setOf("apk", "apks", "xapk", "apkm")
    val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "cab", "iso", "jar")

    /** Extensions shown in the Documents category. */
    val documentExt: Set<String> = officeExt + textExt + setOf("pdf")

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun kindOf(name: String, isDirectory: Boolean = false): FileKind {
        if (isDirectory) return FileKind.FOLDER
        return when (val ext = extension(name)) {
            in imageExt -> FileKind.IMAGE
            in videoExt -> FileKind.VIDEO
            in audioExt -> FileKind.AUDIO
            "pdf" -> FileKind.PDF
            in textExt -> FileKind.TEXT
            in officeExt -> FileKind.DOCUMENT
            in apkExt -> FileKind.APK
            in archiveExt -> FileKind.ARCHIVE
            else -> if (ext.isEmpty()) FileKind.OTHER else FileKind.OTHER
        }
    }

    /** Kind from a MIME type (used for files opened from other apps). */
    fun kindOfMime(mime: String?): FileKind? {
        if (mime.isNullOrBlank()) return null
        val m = mime.lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") -> FileKind.IMAGE
            m.startsWith("video/") -> FileKind.VIDEO
            m.startsWith("audio/") || m == "application/ogg" -> FileKind.AUDIO
            m == "application/pdf" -> FileKind.PDF
            m == "application/vnd.android.package-archive" -> FileKind.APK
            m.startsWith("text/") || m == "application/json" || m == "application/xml" ||
                m == "application/x-subrip" || m == "application/javascript" -> FileKind.TEXT
            m == "application/zip" || m == "application/x-7z-compressed" || m == "application/x-rar-compressed" ||
                m == "application/vnd.rar" || m == "application/x-tar" || m == "application/gzip" -> FileKind.ARCHIVE
            m.contains("officedocument") || m == "application/msword" || m == "application/vnd.ms-excel" ||
                m == "application/vnd.ms-powerpoint" || m.contains("opendocument") || m == "application/rtf" ||
                m == "application/epub+zip" -> FileKind.DOCUMENT
            else -> null
        }
    }

    private val fallbackMime = mapOf(
        "mkv" to "video/x-matroska", "webm" to "video/webm", "mp4" to "video/mp4", "3gp" to "video/3gpp",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "ts" to "video/mp2t", "flv" to "video/x-flv",
        "m4v" to "video/x-m4v", "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "flac" to "audio/flac", "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/opus",
        "amr" to "audio/amr", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "webp" to "image/webp", "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif",
        "bmp" to "image/bmp", "svg" to "image/svg+xml", "pdf" to "application/pdf", "txt" to "text/plain",
        "json" to "application/json", "xml" to "text/xml", "csv" to "text/csv", "html" to "text/html",
        "apk" to "application/vnd.android.package-archive", "zip" to "application/zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "srt" to "application/x-subrip", "vtt" to "text/vtt", "md" to "text/markdown",
    )

    /** A MIME type for sharing / "open with" (pure, no Android calls). */
    fun mimeOf(name: String): String {
        val ext = extension(name)
        fallbackMime[ext]?.let { return it }
        return when (kindOf(name)) {
            FileKind.IMAGE -> "image/*"
            FileKind.VIDEO -> "video/*"
            FileKind.AUDIO -> "audio/*"
            FileKind.TEXT -> "text/plain"
            else -> "*/*"
        }
    }
}
