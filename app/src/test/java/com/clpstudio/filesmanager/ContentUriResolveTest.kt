package com.clpstudio.filesmanager

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.ExternalFiles
import com.clpstudio.filesmanager.data.FileKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A stand-in for WhatsApp / Gmail / Drive: content:// uris with a display name and no file path. */
class FakeShareProvider : ContentProvider() {
    companion object {
        val files = HashMap<String, Pair<String, File>>() // id -> (display name, data)
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val (name, file) = files.getValue(uri.lastPathSegment!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(name, file.length())) }
    }
    override fun getType(uri: Uri): String? = null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(uri.lastPathSegment!!).second, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContentUriResolveTest {
    private val authority = "test.share"
    private lateinit var dir: File

    @Before fun setUp() {
        Robolectric.setupContentProvider(FakeShareProvider::class.java, authority)
        dir = File(System.getProperty("java.io.tmpdir"), "share-" + System.nanoTime()).apply { mkdirs() }
    }

    private fun add(id: String, name: String, bytes: ByteArray): Uri {
        val f = File(dir, id).apply { writeBytes(bytes) }
        FakeShareProvider.files[id] = name to f
        return Uri.parse("content://$authority/item/$id")
    }

    private fun resolve(uri: Uri, type: String?) = runBlocking {
        ExternalFiles.resolve(ApplicationProvider.getApplicationContext(), uri, type)
    }

    @Test fun pdfFromWhatsAppByName() {
        val f = resolve(add("1", "Bill Oct.pdf", "%PDF-1.7 hello".toByteArray()), null)
        assertEquals("Bill Oct.pdf", f.name)
        assertEquals(14L, f.size)
        assertEquals("application/pdf", f.mime)
        assertEquals(FileKind.PDF, f.kind)
    }

    @Test fun noExtensionIsSniffedFromBytes() {
        val f = resolve(add("2", "download", "%PDF-1.4 x".toByteArray()), "application/octet-stream")
        assertEquals("application/pdf", f.mime)
        assertEquals(FileKind.PDF, f.kind)
    }

    @Test fun intentTypeWins() {
        val f = resolve(add("3", "clip", byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()), "video/mp4")
        assertEquals(FileKind.VIDEO, f.kind)
    }

    @Test fun officeZipKeepsItsName() {
        val f = resolve(add("4", "Letter.docx", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2)), null)
        assertEquals(FileKind.DOCUMENT, f.kind)
    }

    @Test fun apkByMime() {
        val f = resolve(add("5", "app.apk", byteArrayOf(0x50, 0x4B, 0x03, 0x04)), "application/vnd.android.package-archive")
        assertEquals(FileKind.APK, f.kind)
    }

    @Test fun unknownTypeIsOther() {
        val f = resolve(add("6", "data.xyz", byteArrayOf(9, 9, 9)), null)
        assertEquals(FileKind.OTHER, f.kind)
        assertEquals("application/octet-stream", f.mime)
    }
}
