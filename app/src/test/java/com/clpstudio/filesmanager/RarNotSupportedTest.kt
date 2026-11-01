package com.clpstudio.filesmanager

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.clpstudio.filesmanager.data.ArchiveFormat
import com.clpstudio.filesmanager.data.Archives
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.OpenedFile
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.UnsupportedArchiveException
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.FileInfoScreen
import com.clpstudio.filesmanager.viewer.ZipViewerScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * RAR is not supported (Nov 2026): Junrar, the RAR reader, was removed because its UnRAR licence is not an
 * open-source licence. Every RAR file (RAR 1.5-4.x, RAR5, split volumes .r00, a RAR renamed to .zip or .7z) must
 * get the same clear "not supported" message as tar.gz, and nothing may crash.
 *
 * No RAR fixtures are shipped: the files are built here from the published RAR signatures plus a few header
 * bytes, which is all the app reads before it refuses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class RarNotSupportedTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** RAR 1.5-4.x: "Rar!" 1A 07 00, then a main archive header (type 0x73). */
    private val rar4 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0xCF, 0x90, 0x73, 0x00, 0x00, 0x0D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** RAR5: "Rar!" 1A 07 01 00, then a main archive header (CRC32, size 3, type 1, flags 0, archive flags 0). */
    private val rar5 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0x33, 0x92, 0xB5, 0xE5, 0x0A, 0x01, 0x05, 0x06, 0x00, 0x05, 0x01, 0x01, 0x80, 0x80, 0x00)

    private fun file(name: String, content: ByteArray) = File(tmp.root, name).apply { writeBytes(content + ByteArray(256) { (it * 7).toByte() }) }

    private val samples get() = listOf(
        file("movie.rar", rar4),
        file("photos.RAR", rar5),
        file("backup.part1.rar", rar5),
        file("backup.r00", rar4),
        file("disguised.zip", rar4),
        file("disguised5.7z", rar5),
    )

    @Test fun rarIsNotBrowsableByName() {
        for (n in listOf("a.rar", "a.RAR", "a.part1.rar", "a.r00", "a.r01", "a.r99")) {
            assertFalse(n, Archives.canBrowse(n))
            assertEquals(n, ArchiveFormat.OTHER, Archives.formatOfName(n))
        }
        // The same answer as tar.gz.
        assertFalse(Archives.canBrowse("a.tar.gz"))
    }

    /** RAR files keep the archive icon and their place under Archives: being recognised is not a claim they open. */
    @Test fun rarStillLooksLikeAnArchive() {
        for (n in listOf("a.rar", "a.RAR", "a.r00", "a.r42")) {
            assertEquals(n, FileKind.ARCHIVE, FileTypes.kindOf(n))
            assertTrue(n, FileTypes.isArchive(n))
        }
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOfMime("application/vnd.rar"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("a.r0"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("a.rxx"))
    }

    /** Every RAR version is detected from its bytes and refused, even renamed to .zip or .7z (never fed to the ZIP / 7z reader). */
    @Test fun everyRarIsRefusedClearlyWithoutACrash() = runBlocking {
        for (f in samples) {
            assertEquals(f.name, ArchiveFormat.OTHER, Archives.formatOf(f))
            val calls: List<Pair<String, () -> Unit>> = listOf(
                "probe" to { Archives.probe(f); Unit },
                "list" to { Archives.list(f, null); Unit },
                "list with password" to { Archives.list(f, "pw".toCharArray()); Unit },
                "extract" to { runBlocking { Archives.extract(f, null, tmp.newFolder()) }; Unit },
                "extract one" to { runBlocking { Archives.extractEntry(f, null, "x.txt", File(tmp.root, "x-${f.name}.txt")) }; Unit },
            )
            for ((what, call) in calls) {
                try {
                    call()
                    fail("${f.name}: $what opened a RAR")
                } catch (e: UnsupportedArchiveException) {
                    assertEquals("This kind of archive can't be opened in Files.", e.message)
                    assertEquals(Archives.UNSUPPORTED_MESSAGE, Archives.failureMessage(e))
                    assertEquals("Archive type not supported", Archives.failureTitle(e))
                }
            }
            assertFalse(f.name, Archives.checkPassword(f, "pw".toCharArray()))
            assertFalse(f.name, Archives.isWrongPassword(UnsupportedArchiveException()))
        }
    }

    @Test fun theMessageNamesWhatDoesOpen() {
        assertEquals("Files opens ZIP and 7z archives. This archive type isn't supported yet.", Archives.UNSUPPORTED_MESSAGE)
        assertFalse(Archives.UNSUPPORTED_MESSAGE.contains("RAR"))
    }

    /** Opening movie.rar from the file list: canBrowse is false, so the app shows the info screen, as for tar.gz. */
    @Test fun infoScreenShowsTheNotSupportedMessage() {
        val f = file("movie.rar", rar4)
        val opened = OpenedFile(Uri.fromFile(f), f.name, f.length(), FileTypes.exactMimeOf(f.name) ?: "application/octet-stream", FileTypes.kindOf(f.name), f.path)
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { FileInfoScreen(opened, onBack = {}) } }
        compose.onNodeWithText(Archives.UNSUPPORTED_MESSAGE).assertExists()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("movie.rar")).fetchSemanticsNodes().isNotEmpty())
    }

    /** A RAR renamed to .zip reaches the archive screen: it says "not supported" instead of trying to read it. */
    @Test fun archiveScreenRefusesARenamedRar() {
        val f = file("disguised.zip", rar5)
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { ZipViewerScreen(f.path, onBack = {}) {} } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(Archives.UNSUPPORTED_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Archive type not supported").assertExists()
    }
}
