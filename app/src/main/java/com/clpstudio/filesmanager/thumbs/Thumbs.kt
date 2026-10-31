package com.clpstudio.filesmanager.thumbs

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import java.io.File

/**
 * Camera RAW (DNG, CR2, NEF, ARW...): the JPEG preview embedded in the file, read with
 * ExifInterface (offline). Returns null for anything else so Coil's own decoders take over.
 */
class RawPreviewFetcher(private val path: String) : Fetcher {
    override suspend fun fetch(): FetchResult? = try {
        val exif = androidx.exifinterface.media.ExifInterface(path)
        exif.thumbnailBitmap?.let { ImageFetchResult(it.asImage(), true, DataSource.DISK) }
    } catch (_: Exception) {
        null
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = data.path ?: return null
            if (data.scheme != null && data.scheme != "file") return null
            if (com.clpstudio.filesmanager.data.FileTypes.extension(path) !in com.clpstudio.filesmanager.data.FileTypes.rawExt) return null
            return RawPreviewFetcher(path)
        }
    }
}

/** Thumbnail models: Coil picks the right fetcher from the model's type. */
data class AudioArt(val path: String, val modified: Long)
data class PdfThumb(val path: String, val modified: Long)
data class ApkIcon(val path: String, val modified: Long)
data class AppIcon(val packageName: String)
data class VideoThumb(val path: String, val modified: Long)
/** PSD / AI / CDR / DWG: the preview stored in the file. */
data class DesignThumb(val path: String, val modified: Long)
/** EPUB: the book's cover picture. */
data class EpubCover(val path: String, val modified: Long)

/** Word / Excel / PowerPoint: the file's stored thumbnail, or (Word) a first-page snapshot. */
data class OfficeThumb(val path: String, val modified: Long)

/** The model to load for a file's thumbnail, or null for a plain type icon. */
fun thumbModel(item: FileItem): Any? = when (item.kind) {
    FileKind.IMAGE -> File(item.path)
    // Android 10+: the system's cached video thumbnail (fast); older: Coil's frame decoder.
    FileKind.VIDEO -> if (Build.VERSION.SDK_INT >= 29) VideoThumb(item.path, item.modified) else File(item.path)
    FileKind.AUDIO -> AudioArt(item.path, item.modified)
    FileKind.PDF -> PdfThumb(item.path, item.modified)
    FileKind.APK -> if (item.extension == "apk") ApkIcon(item.path, item.modified) else null
    FileKind.DOCUMENT -> when {
        item.extension == "epub" -> EpubCover(item.path, item.modified)
        com.clpstudio.filesmanager.viewer.office.OfficeFormats.byExtension(item.name) != null -> OfficeThumb(item.path, item.modified)
        else -> null
    }
    else -> if (com.clpstudio.filesmanager.data.DesignPreview.isDesign(item.name)) DesignThumb(item.path, item.modified) else null
}

private const val THUMB_PX = 256

private fun scaled(bmp: Bitmap, max: Int = THUMB_PX): Bitmap {
    val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
    if (scale >= 1f) return bmp
    return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
}

private fun Drawable.toBitmap(size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(c)
    return bmp
}

class AudioArtFetcher(private val data: AudioArt) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(data.path)
            val bytes = mmr.embeddedPicture ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= THUMB_PX && opts.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            return ImageFetchResult(scaled(bmp).asImage(), true, DataSource.DISK)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { mmr.release() }
        }
    }

    class Factory : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher = AudioArtFetcher(data)
    }
}

class PdfThumbFetcher(private val data: PdfThumb) : Fetcher {
    override suspend fun fetch(): FetchResult? = try {
        ParcelFileDescriptor.open(File(data.path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val w = THUMB_PX
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, THUMB_PX * 2)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ImageFetchResult(bmp.asImage(), true, DataSource.DISK)
                }
            }
        }
    } catch (_: Exception) {
        null // password-protected or damaged: the type icon shows instead
    }

    class Factory : Fetcher.Factory<PdfThumb> {
        override fun create(data: PdfThumb, options: Options, imageLoader: ImageLoader): Fetcher = PdfThumbFetcher(data)
    }
}

class AppIconFetcher(private val pm: PackageManager, private val load: (PackageManager) -> Drawable?) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val d = runCatching { load(pm) }.getOrNull() ?: return null
        // A broken APK icon (0×0 or huge drawable) falls back to the type icon instead of failing.
        val bmp = runCatching { d.toBitmap(144) }.getOrNull() ?: return null
        return ImageFetchResult(bmp.asImage(), true, DataSource.DISK)
    }

    class ApkFactory : Fetcher.Factory<ApkIcon> {
        override fun create(data: ApkIcon, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(options.context.packageManager) { pm ->
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageArchiveInfo(data.path, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(data.path, 0)
                }
                info?.applicationInfo?.let { ai ->
                    ai.sourceDir = data.path
                    ai.publicSourceDir = data.path
                    ai.loadIcon(pm)
                }
            }
    }

    class PackageFactory : Fetcher.Factory<AppIcon> {
        override fun create(data: AppIcon, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(options.context.packageManager) { pm -> pm.getApplicationIcon(data.packageName) }
    }
}

/** Design-file previews (small; a PSD prefers its embedded thumbnail, which is fast). */
class DesignThumbFetcher(private val data: DesignThumb) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bmp = runCatching {
            com.clpstudio.filesmanager.viewer.DesignBitmaps.load(File(data.path), maxPx = THUMB_PX, thumbnail = true).bitmap
        }.getOrNull() ?: return null
        return ImageFetchResult(scaled(bmp).asImage(), true, DataSource.DISK)
    }

    class Factory : Fetcher.Factory<DesignThumb> {
        override fun create(data: DesignThumb, options: Options, imageLoader: ImageLoader): Fetcher = DesignThumbFetcher(data)
    }
}

class EpubCoverFetcher(private val data: EpubCover) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bmp = com.clpstudio.filesmanager.viewer.epubCover(File(data.path), THUMB_PX) ?: return null
        return ImageFetchResult(scaled(bmp).asImage(), true, DataSource.DISK)
    }

    class Factory : Fetcher.Factory<EpubCover> {
        override fun create(data: EpubCover, options: Options, imageLoader: ImageLoader): Fetcher = EpubCoverFetcher(data)
    }
}

class OfficeThumbFetcher(private val data: OfficeThumb) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = File(data.path)
        val format = com.clpstudio.filesmanager.viewer.office.OfficeFormats.byExtension(file.name)
        val bmp = runCatching { com.clpstudio.filesmanager.viewer.office.OfficeThumbnails.load(file, format, THUMB_PX) }.getOrNull() ?: return null
        return ImageFetchResult(scaled(bmp).asImage(), true, DataSource.DISK)
    }

    class Factory : Fetcher.Factory<OfficeThumb> {
        override fun create(data: OfficeThumb, options: Options, imageLoader: ImageLoader): Fetcher = OfficeThumbFetcher(data)
    }
}

object ThumbKeyers {
    val office = Keyer<OfficeThumb> { d, _ -> "office:${d.path}:${d.modified}" }
    val design = Keyer<DesignThumb> { d, _ -> "design:${d.path}:${d.modified}" }
    val epub = Keyer<EpubCover> { d, _ -> "epub:${d.path}:${d.modified}" }
    val audio = Keyer<AudioArt> { d, _ -> "audio:${d.path}:${d.modified}" }
    val pdf = Keyer<PdfThumb> { d, _ -> "pdf:${d.path}:${d.modified}" }
    val apk = Keyer<ApkIcon> { d, _ -> "apk:${d.path}:${d.modified}" }
    val app = Keyer<AppIcon> { d, _ -> "app:${d.packageName}" }
}

/** Video thumbnails from MediaStore's own cache (much faster than decoding a frame), frame grab as fallback. */
class VideoThumbFetcher(private val data: VideoThumb, private val context: android.content.Context) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        // Hidden files (dot-folders) are never in MediaStore: go straight to the frame grab.
        val bmp = (if (Build.VERSION.SDK_INT >= 29 && !data.path.contains("/.")) runCatching { fromMediaStore() }.getOrNull() else null)
            ?: runCatching { frame() }.getOrNull()
            ?: return null
        return ImageFetchResult(scaled(bmp, 384).asImage(), true, DataSource.DISK)
    }

    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(29)
    private fun fromMediaStore(): Bitmap? {
        val base = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val id = context.contentResolver.query(
            base, arrayOf(android.provider.MediaStore.Video.Media._ID),
            "${android.provider.MediaStore.Video.Media.DATA} = ?", arrayOf(data.path), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        val uri = android.content.ContentUris.withAppendedId(base, id)
        return context.contentResolver.loadThumbnail(uri, android.util.Size(384, 384), null)
    }

    private fun frame(): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(data.path)
            if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 384)
            else mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { mmr.release() }
        }
    }

    class Factory(private val context: android.content.Context) : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher = VideoThumbFetcher(data, context)
    }

    companion object {
        val keyer = Keyer<VideoThumb> { data, _ -> "video:${data.path}:${data.modified}" }
    }
}
