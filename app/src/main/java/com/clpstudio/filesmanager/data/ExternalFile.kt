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
    /** HEIF still-image / image-sequence brands (Samsung Camera saves "heic"; bursts and motion photos use others). */
    private val HEIF_BRANDS = listOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")
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
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> "image/avif"
            ascii(4, "ftyp") && HEIF_BRANDS.any { ascii(8, it) } -> "image/heic"
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
        // A photo is never a video (item 28): bytes that are an image win over a "video/*" type or a video name.
        val sniffedFirst = head?.let { sniff(it) }
        if (sniffedFirst != null && sniffedFirst.startsWith("image/") && intentType?.lowercase()?.startsWith("image/") != true) {
            val byNameMime = FileTypes.mimeOf(name)
            return if (byNameMime.startsWith("image/")) byNameMime else sniffedFirst
        }
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
    /** How long to wait for a file another app is still writing (Samsung Camera's preview opens before the photo is saved). */
    const val PENDING_WAIT_MS = 3_000L
    private const val PENDING_STEP_MS = 250L

    /**
     * Name, size, type and (when it can be found) the real path of any uri (content:// or file://). Never throws.
     * A foreign content:// uri without a name (Samsung Camera's content://secmedia/media/<id>) is matched to its
     * file through MediaStore, so it opens like any photo on storage (item 28).
     */
    suspend fun resolve(context: Context, uri: Uri, intentType: String?): OpenedFile = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var meta: ForeignMeta
        var head: ByteArray?
        var waited = 0L
        while (true) {
            meta = if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val f = File(uri.path ?: "")
                ForeignMeta(name = f.name, size = f.length(), path = f.absolutePath)
            } else ForeignResolve.queryMeta(cr, uri)
            head = runCatching {
                if (uri.scheme == ContentResolver.SCHEME_FILE) ForeignResolve.headOf(File(uri.path ?: ""))
                else cr.openInputStream(uri)?.use { ForeignResolve.headOf(it) }
            }.getOrNull()
            val stillWriting = meta.pending || head == null || head.isEmpty() || meta.size == 0L
            if (!stillWriting || uri.scheme == ContentResolver.SCHEME_FILE || waited >= PENDING_WAIT_MS) break
            kotlinx.coroutines.delay(PENDING_STEP_MS)
            waited += PENDING_STEP_MS
        }
        var path = meta.path?.takeIf { File(it).isFile }
        if (path == null && uri.scheme == ContentResolver.SCHEME_CONTENT && !ForeignResolve.isOwnAuthority(context, uri)) {
            path = runCatching { ForeignResolve.findRealFile(cr, uri, meta, head)?.absolutePath }.getOrNull()
        }
        val real = path?.let { File(it) }
        val crType = runCatching { cr.getType(uri) }.getOrNull()
        val rawName = real?.name ?: meta.name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val size = real?.length() ?: meta.size
        val mime0 = MagicSniffer.resolveMime(intentType ?: crType ?: meta.mime, rawName, head)
        // No name at all (only a row id): give it the extension of what it is, so it reads as a file.
        val finalName = if (real == null && meta.name.isNullOrBlank() && !rawName.contains('.')) ForeignResolve.nameFor(rawName, meta.title, mime0) else rawName
        val mime = MagicSniffer.resolveMime(intentType ?: crType ?: meta.mime, finalName, head)
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
