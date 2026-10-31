package com.clpstudio.filesmanager.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file to show, from our own storage or from another app (content:// with no path). */
data class OpenedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val kind: FileKind,
    /** Real path when known (our own storage), else null. */
    val path: String? = null,
)

/** Recognises common formats from their first bytes. Pure Kotlin, unit tested. */
object MagicSniffer {
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { (head[i + it].toInt() and 0xFF) == b[it] }
        fun ascii(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it].toInt().toChar() == s[it] }
        return when {
            ascii(0, "%PDF") -> "application/pdf"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "video/x-msvideo"
            ascii(0, "BM") && head.size > 14 -> "image/bmp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1")) -> "image/heic"
            ascii(4, "ftyp") && (ascii(8, "M4A ") || ascii(8, "M4B ")) -> "audio/mp4"
            ascii(4, "ftyp") && ascii(8, "3gp") -> "video/3gpp"
            ascii(4, "ftyp") -> "video/mp4"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/x-matroska"
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) -> "audio/mpeg"
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9) -> "audio/aac"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "#!AMR") -> "audio/amr"
            ascii(0, "FLV") -> "video/x-flv"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> "application/zip"
            ascii(0, "Rar!") -> "application/vnd.rar"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "application/x-7z-compressed"
            at(0, 0x1F, 0x8B) -> "application/gzip"
            ascii(0, "<svg") || ascii(0, "<?xml") -> "text/xml"
            else -> null
        }
    }

    /**
     * The MIME to use for a file opened from another app: a specific type from the intent wins,
     * else the name's extension, else the first bytes, else octet-stream.
     */
    fun resolveMime(intentType: String?, name: String, head: ByteArray?): String {
        val fromIntent = intentType?.takeIf { it.isNotBlank() && it != "*/*" && it != "application/octet-stream" && !it.endsWith("/*") }
        if (fromIntent != null) return fromIntent
        val byName = FileTypes.mimeOf(name).takeIf { it != "*/*" && !it.endsWith("/*") }
        if (byName != null) return byName
        val sniffed = head?.let { sniff(it) }
        // A ZIP container named .apk / .docx is decided by name above; a bare ZIP stays zip.
        if (sniffed != null) return sniffed
        return intentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    }
}

object ExternalFiles {
    /** Name, size and type of any uri (content:// or file://). Never throws. */
    suspend fun resolve(context: Context, uri: Uri, intentType: String?): OpenedFile = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        var path: String? = null
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: "")
            name = f.name
            size = f.length()
            path = f.absolutePath
        } else {
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
        }
        val crType = runCatching { cr.getType(uri) }.getOrNull()
        val head = runCatching {
            cr.openInputStream(uri)?.use { s ->
                val buf = ByteArray(32)
                val n = s.read(buf)
                if (n > 0) buf.copyOf(n) else null
            }
        }.getOrNull()
        val finalName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = MagicSniffer.resolveMime(intentType ?: crType, finalName, head)
        val kindByName = FileTypes.kindOf(finalName)
        val kind = FileTypes.kindOfMime(mime)?.let { mk ->
            // Office files, APKs and subtitles are ZIP / text inside: trust the name for those.
            if (kindByName in setOf(FileKind.DOCUMENT, FileKind.APK, FileKind.TEXT) && mk in setOf(FileKind.ARCHIVE, FileKind.TEXT)) kindByName else mk
        } ?: kindByName
        OpenedFile(uri, finalName, size, mime, kind, path)
    }

    fun fromFile(file: File): OpenedFile {
        val kind = FileTypes.kindOf(file.name)
        return OpenedFile(Uri.fromFile(file), file.name, file.length(), FileTypes.mimeOf(file.name), kind, file.absolutePath)
    }

    /** Copies a uri to the app cache (only for viewers that need a seekable file). */
    suspend fun copyToCache(context: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.deleteRecursively() }
        val out = File(dir, name.replace('/', '_'))
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw java.io.FileNotFoundException("Can't read this file")
        out
    }
}
