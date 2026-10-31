package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.DesignPreview
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.SafAccess
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.SafAccessDialogContent
import com.clpstudio.filesmanager.ui.VaultOpen
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.vault.SoftwareKeyWrapper
import com.clpstudio.filesmanager.vault.VaultStore
import com.clpstudio.filesmanager.vault.VaultTemp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** F11 build 2: SD card writes on 8–10 (1), AI previews (9), vault opens every type (13). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class F11Round2Test {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shots = File("build/screenshots").apply { mkdirs() }.path

    private fun fmt(name: String): File {
        val out = File(app.cacheDir, name)
        F11Round2Test::class.java.getResourceAsStream("/fmt/$name")!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    // ---------- 1: SD card writes on Android 8–10 ----------

    @Test fun safOnlyForCardsOnAndroid8to10() {
        val cards = listOf("/storage/1A2B-3C4D")
        assertTrue(SafAccess.applies("/storage/1A2B-3C4D/DCIM/a.jpg", cards, sdk = 26))
        assertTrue(SafAccess.applies("/storage/1A2B-3C4D/x", cards, sdk = 29))
        assertFalse(SafAccess.applies("/storage/1A2B-3C4D/x", cards, sdk = 30)) // 11+: All files access writes directly
        assertFalse(SafAccess.applies("/storage/emulated/0/DCIM/a.jpg", cards, sdk = 28)) // phone storage
        assertEquals("1A2B-3C4D", SafAccess.volumeId("/storage/1A2B-3C4D"))
        assertEquals("1A2B-3C4D:DCIM/Camera/a.jpg", SafAccess.documentId("/storage/1A2B-3C4D", "/storage/1A2B-3C4D/DCIM/Camera/a.jpg"))
        assertEquals("1A2B-3C4D:", SafAccess.documentId("/storage/1A2B-3C4D", "/storage/1A2B-3C4D"))
        assertTrue(SafAccess.isCardRoot("1A2B-3C4D:", "/storage/1A2B-3C4D"))
        assertFalse(SafAccess.isCardRoot("1A2B-3C4D:DCIM", "/storage/1A2B-3C4D")) // a folder, not the card
        assertFalse(SafAccess.isCardRoot("primary:", "/storage/1A2B-3C4D"))
    }

    @Test fun pickerAnswerIsChecked() {
        assertTrue(SafAccess.onPicked(app, "/storage/1A2B-3C4D", null)!!.startsWith("Not allowed"))
        val folder = android.provider.DocumentsContract.buildTreeDocumentUri(SafAccess.PROVIDER, "1A2B-3C4D:DCIM")
        assertTrue(SafAccess.onPicked(app, "/storage/1A2B-3C4D", folder)!!.contains("top level"))
    }

    @Test fun everyWritePathGoesThroughTheCardPermission() {
        fun src(p: String) = File("src/main/java/com/clpstudio/filesmanager/$p").readText()
        val ops = src("data/FileOps.kt")
        assertTrue(ops.contains("SafAccess.mkdirsAny(")) // new folder + copy folders
        assertTrue(ops.contains("SafAccess.renameAny(context, file, target)")) // rename
        assertTrue(ops.contains("SafAccess.outputAny(context, dest)")) // copy / move in
        assertTrue(ops.contains("SafAccess.deleteRecursively(context, src)")) // move out
        assertTrue(ops.contains("SafAccess.canWrite(context, targetDir)"))
        assertTrue(src("data/DeleteRules.kt").contains("SafAccess.deleteRecursively(context, f)"))
        assertTrue(src("data/BinRepository.kt").contains("SafAccess.deleteRecursively(context, f)"))
        assertTrue(src("data/BinRepository.kt").contains("SafAccess.copyTree(context, e.dataFile, target)"))
        assertTrue(src("data/Archives.kt").contains("SafAccess.outputAny(SafAccess.appContext(), out)"))
        assertTrue(src("ui/AppRoot.kt").contains("SafAccessDialog()"))
    }

    @Test fun plainFilesStillWorkWithoutTheCardPath() {
        val dir = File(app.cacheDir, "saf_plain").apply { deleteRecursively() }
        assertTrue(SafAccess.mkdirsAny(app, File(dir, "a/b")))
        SafAccess.outputAny(app, File(dir, "a/b/x.txt")).use { it.write("hi".toByteArray()) }
        assertTrue(SafAccess.copyTree(app, File(dir, "a"), File(dir, "c")))
        assertEquals("hi", File(dir, "c/b/x.txt").readText())
        assertTrue(SafAccess.deleteRecursively(app, File(dir, "a")))
        assertFalse(File(dir, "a").exists())
    }

    @Test fun cardAccessDialog_render() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { SafAccessDialogContent({}, {}) } }
        compose.onNodeWithText("Allow access to the SD card").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_f11_sd_access.png")
    }

    // ---------- 9: Illustrator files without "PDF compatible" ----------

    private fun assertLooksRight(p: DesignPreview.Image.Pixels) {
        assertEquals(120, p.width); assertEquals(80, p.height)
        fun at(x: Int, y: Int) = p.argb[y * p.width + x]
        val blue = at(30, 30)
        assertTrue("blue square: ${Integer.toHexString(blue)}", (blue and 0xFF) > 200 && ((blue shr 16) and 0xFF) < 60)
        val white = at(5, 75)
        assertEquals(0xFFFFFFFF.toInt(), white)
        val red = at(90, 45)
        assertTrue("red circle: ${Integer.toHexString(red)}", ((red shr 16) and 0xFF) > 200 && (red and 0xFF) < 80)
    }

    @Test fun oldIllustratorTiffPreviews() {
        for (n in listOf("old_tiff_raw.ai", "old_tiff_lzw.ai", "old_tiff_packbits.ai")) {
            val r = DesignPreview.read(fmt(n))
            val img = r.image as? DesignPreview.Image.Pixels
            assertNotNull("$n: ${r.note}", img)
            assertLooksRight(img!!)
            assertEquals("Preview saved with the file", r.note)
        }
    }

    @Test fun oldIllustratorEpsiPreview() {
        val r = DesignPreview.read(fmt("old_epsi.ai"))
        val img = r.image as DesignPreview.Image.Pixels
        assertEquals(120, img.width)
        // Gray: the blue square is darker than the white page.
        assertTrue((img.argb[30 * 120 + 30] and 0xFF) < 200)
        assertEquals(0xFFFFFFFF.toInt(), img.argb[75 * 120 + 5])
    }

    // ---------- 13: vault opens every type ----------

    @Test fun vaultTargets() {
        assertEquals(VaultOpen.Target.PDF, VaultOpen.target(FileKind.PDF, "a.pdf"))
        assertEquals(VaultOpen.Target.TEXT, VaultOpen.target(FileKind.TEXT, "a.md"))
        assertEquals(VaultOpen.Target.OFFICE, VaultOpen.target(FileKind.DOCUMENT, "a.docx"))
        assertEquals(VaultOpen.Target.OFFICE, VaultOpen.target(FileKind.DOCUMENT, "a.xls"))
        assertEquals(VaultOpen.Target.EPUB, VaultOpen.target(FileKind.DOCUMENT, "book.epub"))
        assertEquals(VaultOpen.Target.ARCHIVE, VaultOpen.target(FileKind.ARCHIVE, "a.zip"))
        assertEquals(VaultOpen.Target.APK, VaultOpen.target(FileKind.APK, "a.apk"))
        assertEquals(VaultOpen.Target.FONT, VaultOpen.target(FileKind.FONT, "a.ttf"))
        assertEquals(VaultOpen.Target.PAGER, VaultOpen.target(FileKind.OTHER, "art.psd"))
        assertEquals(VaultOpen.Target.HEX, VaultOpen.target(FileKind.OTHER, "data.bin"))
        assertFalse(VaultOpen.needsTemp(VaultOpen.Target.PDF))
        assertFalse(VaultOpen.needsTemp(VaultOpen.Target.HEX))
        assertTrue(VaultOpen.needsTemp(VaultOpen.Target.OFFICE))
    }

    @Test fun decryptedCopyIsPrivateAndWiped() {
        val dir = File(app.cacheDir, "vault_temp_store").apply { deleteRecursively() }
        val base = File(app.noBackupFilesDir, "vault-open-test").apply { deleteRecursively() }
        val s = VaultStore(dir, SoftwareKeyWrapper(), iterations = 2000, chunk = 4096)
        s.setup(CredentialType.PIN, "1357")
        val plain = ByteArray(20_000) { (it % 251).toByte() }
        val item = s.importFile(File(app.cacheDir, "report.docx").apply { writeBytes(plain) })
        val out = VaultTemp.export(item, s, base)
        assertEquals("report.docx", out.name)
        assertTrue(out.readBytes().contentEquals(plain))
        assertTrue(VaultTemp.isTemp(out.path, base))
        assertFalse(VaultTemp.isTemp(File(app.cacheDir, "report.docx").path, base))
        assertTrue(out.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath)) // app-private, not shared storage
        VaultTemp.wipe(out.path, base)
        assertFalse(out.exists())
        assertFalse(out.parentFile!!.exists())
        // Locking wipes everything left.
        val again = VaultTemp.export(item, s, base)
        VaultTemp.wipeAll(base)
        assertFalse(again.exists())
        assertTrue(base.listFiles().isNullOrEmpty())
    }

    @Test fun vaultScreensCoverDecryptedFiles() {
        val routes = File("src/main/java/com/clpstudio/filesmanager/ui/AppRoot.kt").readText()
        assertTrue(routes.split("VaultTempGuard(").size - 1 >= 5) // pager, zip, apk, epub, font
        val vault = File("src/main/java/com/clpstudio/filesmanager/vault/Vault.kt").readText()
        assertTrue(vault.substringAfter("fun lock()").substringBefore("}").contains("VaultTemp.wipeAll()"))
        val viewer = File("src/main/java/com/clpstudio/filesmanager/ui/vault/VaultViewer.kt").readText()
        assertFalse(viewer.contains("This type can't be opened inside the vault. Move it out") && viewer.contains("else -> VaultDocPage(item, null) {}"))
    }

    // ---------- 12: WMV says why instead of a black screen ----------

    @Test fun unplayableVideosSayWhy() {
        val E = com.clpstudio.filesmanager.viewer.VideoErrors
        assertTrue(E.message("clip.wmv", 4001).contains("WMV"))
        assertTrue(E.message("clip.ASF", 1000).contains("WMV"))
        assertTrue(E.message("x.mkv", androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).contains("can't decode"))
        assertTrue(E.message("x.mp4", 1000).contains("another app"))
        val src = File("src/main/java/com/clpstudio/filesmanager/viewer/PagerVideo.kt").readText()
        assertTrue(src.contains("override fun onPlayerError"))
        assertTrue(src.contains("VideoErrorCard(item, err"))
        assertFalse(File("src/main/java/com/clpstudio/filesmanager/playback/MediaRenderers.kt").readText().contains("(experimental) H.264"))
    }
}
