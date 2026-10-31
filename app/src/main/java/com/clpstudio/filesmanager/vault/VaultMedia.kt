package com.clpstudio.filesmanager.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.clpstudio.filesmanager.data.FileKind
import java.io.IOException
import java.nio.ByteBuffer

/** Media3 reads a vault file through this: decrypts only the chunks the player asks for (seeking works). */
@OptIn(UnstableApi::class)
class VaultDataSource(private val open: () -> VaultReader) : BaseDataSource(false) {
    private var reader: VaultReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val r = reader ?: open().also { reader = it }
        if (dataSpec.position > r.size) throw androidx.media3.datasource.DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, r.size - position) else r.size - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("closed")
        val n = r.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
        runCatching { reader?.close() }
        reader = null
    }

    class Factory(private val open: () -> VaultReader) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(open)
    }
}

/** MediaMetadataRetriever (video frame / audio cover) over a vault file, never via a temp file. */
class VaultMediaDataSource(private val reader: VaultReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (position >= reader.size) -1 else reader.read(position, buffer, offset, size)
    override fun getSize(): Long = reader.size
    override fun close() = reader.close()
}

/** Decrypt-to-memory image decoding and previews for the vault. Nothing is written to disk. */
object VaultImages {
    private const val MAX_IN_MEMORY = 80L * 1024 * 1024

    /** A bitmap no bigger than [maxPx] on its long side, upright (EXIF), or null. */
    fun decode(reader: VaultReader, maxPx: Int): Bitmap? {
        if (reader.size > MAX_IN_MEMORY) return null
        val bytes = reader.readAll()
        if (Build.VERSION.SDK_INT >= 28) {
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val long = maxOf(w, h)
                    if (long > maxPx) {
                        val s = maxPx.toFloat() / long
                        d.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
                    }
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull() ?: decodeLegacy(bytes, maxPx)
        }
        return decodeLegacy(bytes, maxPx)
    }

    private fun decodeLegacy(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** A frame from a video or the cover of a song, decrypted on the fly. */
    fun mediaFrame(reader: VaultReader, video: Boolean, maxPx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(VaultMediaDataSource(reader))
            val bmp = if (video) mmr.getFrameAtTime(1_000_000) ?: mmr.frameAtTime
            else mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            bmp?.let { scale(it, maxPx) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun scale(b: Bitmap, maxPx: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= maxPx) return b
        val s = maxPx.toFloat() / long
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    /** The grid preview for an item (cached in memory until the vault locks). */
    fun thumb(item: VaultItem, maxPx: Int = 384): Bitmap? {
        Vault.thumbs.get(item.id)?.let { return it }
        val bmp = runCatching {
            when (item.kind) {
                FileKind.IMAGE -> Vault.store.openReader(item).use { decode(it, maxPx) }
                FileKind.VIDEO -> mediaFrame(Vault.store.openReader(item), true, maxPx)
                FileKind.AUDIO -> mediaFrame(Vault.store.openReader(item), false, maxPx)
                else -> null
            }
        }.getOrNull() ?: return null
        if (Vault.store.isUnlocked) Vault.thumbs.put(item.id, bmp)
        return bmp
    }
}
