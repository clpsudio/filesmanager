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

/** Thumbnail models: Coil picks the right fetcher from the model's type. */
data class AudioArt(val path: String, val modified: Long)
data class PdfThumb(val path: String, val modified: Long)
data class ApkIcon(val path: String, val modified: Long)
data class AppIcon(val packageName: String)

/** The model to load for a file's thumbnail, or null for a plain type icon. */
fun thumbModel(item: FileItem): Any? = when (item.kind) {
    FileKind.IMAGE, FileKind.VIDEO -> File(item.path)
    FileKind.AUDIO -> AudioArt(item.path, item.modified)
    FileKind.PDF -> PdfThumb(item.path, item.modified)
    FileKind.APK -> if (item.extension == "apk") ApkIcon(item.path, item.modified) else null
    else -> null
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
        return ImageFetchResult(d.toBitmap(144).asImage(), true, DataSource.DISK)
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

object ThumbKeyers {
    val audio = Keyer<AudioArt> { d, _ -> "audio:${d.path}:${d.modified}" }
    val pdf = Keyer<PdfThumb> { d, _ -> "pdf:${d.path}:${d.modified}" }
    val apk = Keyer<ApkIcon> { d, _ -> "apk:${d.path}:${d.modified}" }
    val app = Keyer<AppIcon> { d, _ -> "app:${d.packageName}" }
}
