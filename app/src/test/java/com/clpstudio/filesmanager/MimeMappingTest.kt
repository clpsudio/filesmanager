package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Open with" gets an exact MIME type for every known extension, never a wildcard. */
class MimeMappingTest {
    private val known = listOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp",
        "mp4", "mkv", "webm", "3gp", "mov", "avi",
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr",
        "pdf", "txt", "csv", "json", "xml", "html", "zip", "rar", "7z", "apk", "epub",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    )

    @Test fun knownExtensionsHaveExactTypes() {
        for (ext in known) {
            val mime = FileTypes.exactMimeOf("file.$ext")
            assertNotNull("no type for .$ext", mime)
            assertFalse(".$ext maps to a wildcard: $mime", FileTypes.isWildcard(mime))
            assertFalse(".$ext: */*", mime == "*/*")
        }
    }

    @Test fun upperCaseAndSpecificValues() {
        assertEquals("image/jpeg", FileTypes.exactMimeOf("IMG_1.JPG"))
        assertEquals("application/vnd.android.package-archive", FileTypes.exactMimeOf("app.apk"))
        assertEquals("application/pdf", FileTypes.exactMimeOf("a.b.pdf"))
        assertEquals("video/x-matroska", FileTypes.exactMimeOf("movie.mkv"))
    }

    @Test fun unknownIsNullNotWildcard() {
        assertNull(FileTypes.exactMimeOf("data.qwerty"))
        assertNull(FileTypes.exactMimeOf("noextension"))
        assertTrue(FileTypes.isWildcard("*/*"))
        assertTrue(FileTypes.isWildcard("image/*"))
        assertTrue(FileTypes.isWildcard(null))
        assertFalse(FileTypes.isWildcard("image/png"))
    }
}
