package com.clpstudio.filesmanager

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.InfoEdits
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.picker.FilesDocumentsProvider
import com.clpstudio.filesmanager.picker.PickerActivity
import com.clpstudio.filesmanager.picker.PickerContent
import com.clpstudio.filesmanager.picker.PickerRequest
import com.clpstudio.filesmanager.picker.PickerSection
import com.clpstudio.filesmanager.picker.PickerUi
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.vault.SoftwareKeyWrapper
import com.clpstudio.filesmanager.vault.VaultStore
import com.clpstudio.filesmanager.viewer.CameraReview
import com.clpstudio.filesmanager.viewer.InfoEdit
import com.clpstudio.filesmanager.viewer.editableFor
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 56 (rename + date for normal files only), item 54 (picker), item 55 (camera review intents). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class Round10bTest {
    @get:Rule val compose = createComposeRule()
    private val shots = "/home/user/tab/files"
    private lateinit var ctx: Application
    private lateinit var root: File

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Graph.init(ctx)
        // The provider's own volume list (in tests it may differ from getExternalStorageDirectory()).
        root = File(com.clpstudio.filesmanager.data.StoragePaths.volumes(ctx).firstOrNull()?.path ?: Environment.getExternalStorageDirectory().path).apply { mkdirs() }
    }

    @After fun tearDown() {
        FilesDocumentsProvider.ownPickerOverride = null
        FilesDocumentsProvider.showHiddenOverride = null
    }

    private fun file(rel: String, text: String = "x", modified: Long? = null) = File(root, rel).apply {
        parentFile!!.mkdirs(); writeText(text); if (modified != null) setLastModified(modified)
    }

    // ---------- rename / date: normal files yes, hidden no ----------

    @Test fun renameCantMakeAFileHidden() {
        assertEquals("Names can't start with a dot (.) — that would hide the file", InfoEdits.nameError(".secret.jpg", "secret.jpg") { false })
        assertNull(InfoEdits.nameError("holiday.jpg", "secret.jpg") { false })
    }

    @Test fun hiddenFilesHaveNoRenameOrDate() {
        val normal = file("Docs/report.pdf")
        val dot = file("Docs/.private.pdf")
        val inDotFolder = file(".stash/a.jpg")
        val media = file("Pics/Secret/b.jpg").also { File(it.parentFile, ".nomedia").writeText("") }
        assertEquals(setOf(InfoEdit.NAME, InfoEdit.DATE, InfoEdit.FOLDER), editableFor(normal.path))
        assertTrue(editableFor(File(root, "Docs").path).contains(InfoEdit.NAME)) // folders too
        for (h in listOf(dot, inDotFolder, media)) assertEquals(h.path, emptySet<InfoEdit>(), editableFor(h.path))
    }

    @Test fun menusOfferRenameAndDateOnlyForNormalFiles() {
        fun src(rel: String) = File("src/main/java/com/clpstudio/filesmanager/$rel").readText()
        val fa = src("ui/components/FileActions.kt")
        assertTrue(fa.contains("if (selected.size == 1 && !hideChoice.deletePermanently) {"))
        assertTrue(fa.contains("\"Edit date & time\""))
        val pager = src("viewer/FilePagerScreen.kt")
        assertTrue(pager.contains("!currentHidden) ViewerMenuEntry(Icons.Filled.DriveFileRenameOutline, \"Rename\")"))
        assertTrue(pager.contains("!currentHidden) ViewerMenuEntry(Icons.Filled.Schedule, \"Edit date & time\")"))
        assertTrue(src("ui/vault/VaultScreens.kt").contains("Text(\"Rename\")"))
    }

    @Test fun editDateChangesModifiedTimeAndSortOrder() {
        val a = file("Sort/a.txt", modified = 1_700_000_000_000L)
        val b = file("Sort/b.txt", modified = 1_700_000_100_000L)
        fun newestFirst() = File(root, "Sort").listFiles()!!.sortedByDescending { it.lastModified() }.map { it.name }
        assertEquals(listOf("b.txt", "a.txt"), newestFirst())
        val msg = com.clpstudio.filesmanager.viewer.applyDate(ctx, a, 1_760_000_000_000L, exif = false)
        assertEquals("Date changed", msg)
        assertEquals(1_760_000_000_000L, a.lastModified())
        assertEquals(listOf("a.txt", "b.txt"), newestFirst())
    }

    @Test fun vaultItemsCanBeRenamed() {
        val dir = File(ctx.cacheDir, "vault_r10").apply { deleteRecursively() }
        val s = VaultStore(dir, SoftwareKeyWrapper(), iterations = 2000, chunk = 4096)
        s.setup(CredentialType.PIN, "4321")
        val src = File(ctx.cacheDir, "passport.jpg").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val item = s.importFile(src)
        s.rename(item, "passport 2026.jpg")
        assertEquals(listOf("passport 2026.jpg"), s.list().map { it.name })
        s.lock(); s.unlock("4321")
        assertEquals("passport 2026.jpg", s.list().single().name)
    }

    // ---------- provider (Android's picker) ----------

    private fun provider(): FilesDocumentsProvider =
        Robolectric.buildContentProvider(FilesDocumentsProvider::class.java).create(
            android.content.pm.ProviderInfo().apply {
                authority = ctx.packageName + ".documents"; exported = true; grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS; writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            },
        ).get()

    @Test fun ownPickerByDefaultSoAndroidOpensOurs() {
        assertTrue(com.clpstudio.filesmanager.data.AppSettings().ownPickerInOtherApps)
        FilesDocumentsProvider.ownPickerOverride = true
        assertEquals(0, provider().queryRoots(null).count)
        FilesDocumentsProvider.ownPickerOverride = false
        provider().queryRoots(null).use { c ->
            assertTrue(c.count >= 1)
            c.moveToFirst()
            val flags = c.getInt(c.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_SUPPORTS_SEARCH != 0)
            assertTrue(flags and Root.FLAG_SUPPORTS_RECENTS != 0)
        }
    }

    @Test fun providerListsSizeDateHidesHiddenAndVault() {
        FilesDocumentsProvider.ownPickerOverride = false
        FilesDocumentsProvider.showHiddenOverride = false
        val dir = File(root, "Pick").apply { deleteRecursively(); mkdirs() }
        val doc = file("Pick/invoice.pdf", "%PDF-1.4 hello", modified = 1_750_000_000_000L)
        file("Pick/.secret.txt")
        File(dir, "Private").mkdirs(); File(dir, "Private/.nomedia").writeText(""); file("Pick/Private/p.jpg")
        val p = provider()
        val names = ArrayList<String>()
        p.queryChildDocuments(dir.path, null, null as String?).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
                names += name
                if (name == "invoice.pdf") {
                    assertEquals(doc.length(), c.getLong(c.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
                    assertEquals(1_750_000_000_000L, c.getLong(c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)))
                    assertEquals("application/pdf", c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)))
                    assertTrue(c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS)) and Document.FLAG_SUPPORTS_THUMBNAIL != 0)
                }
            }
        }
        assertEquals(listOf("invoice.pdf"), names.sorted())
        FilesDocumentsProvider.showHiddenOverride = true
        p.queryChildDocuments(dir.path, null, null as String?).use { assertEquals(3, it.count) }
        // The vault / private app storage is never reachable.
        val priv = com.clpstudio.filesmanager.data.StoragePaths.privateRoots(ctx)
        assertTrue(priv.isNotEmpty())
        for (pr in priv) assertTrue(runCatching { p.queryDocument(pr, null) }.isFailure)
    }

    @Test fun providerSearchFindsByName() {
        FilesDocumentsProvider.ownPickerOverride = false
        FilesDocumentsProvider.showHiddenOverride = false
        file("Search/Trip/Goa itinerary.pdf"); file("Search/notes.txt"); file("Search/.goa-hidden.txt")
        provider().querySearchDocuments(FilesDocumentsProvider.rootId(0, com.clpstudio.filesmanager.data.StoragePaths.volumes(ctx).first()), "goa", null).use { c ->
            val names = ArrayList<String>(); while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            assertTrue(names.contains("Goa itinerary.pdf")); assertFalse(names.contains(".goa-hidden.txt"))
        }
    }

    // ---------- our picker ----------

    @Test fun mimeFilterAndUrisReturned() {
        val req = PickerRequest.from("image/*", null, allowMultiple = true)
        val jpg = FileItem.of(file("Pick2/a.jpg")); val pdf = FileItem.of(file("Pick2/b.pdf"))
        assertTrue(req.accepts(jpg)); assertFalse(req.accepts(pdf))
        val two = PickerActivity.resultFor(ctx, listOf(jpg, FileItem.of(file("Pick2/c.png"))))!!
        assertEquals("content", two.data!!.scheme)
        assertEquals(2, two.clipData!!.itemCount)
        assertTrue(two.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(PickerActivity.resultFor(ctx, emptyList()))
    }

    private fun pickerItems(): List<FileItem> {
        val now = System.currentTimeMillis()
        return listOf(
            FileItem.of(file("Download/Offer letter CLP.pdf", "x".repeat(182_000), now - 3_600_000)),
            FileItem.of(file("Download/Budget 2026.xlsx", "x".repeat(48_000), now - 86_400_000)),
            FileItem.of(file("Download/notes.txt", "Meeting at 7\nShip 1.0.21\nCall Ravi", now - 2 * 86_400_000)),
            FileItem.of(file("Download/song.mp3", "x".repeat(4_200_000), now - 5 * 86_400_000)),
        )
    }

    @Test fun pickerList_render() {
        val items = pickerItems()
        var previewed: FileItem? = null
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                PickerContent(
                    ui = PickerUi(PickerRequest.from("*/*", null, false), PickerSection.BROWSE, items, folder = File(root, "Download").path),
                    selected = emptySet(), onSection = {}, onQuery = {}, onOpenFolder = {}, onUp = {},
                    onTap = { previewed = it }, onLongPress = {}, onDone = {}, onCancel = {}, onAllowAccess = {},
                )
            }
        }
        compose.onNodeWithText("Offer letter CLP.pdf").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_picker_list.png")
        compose.onNodeWithText("notes.txt").performClick()
        assertEquals("notes.txt", previewed?.name)
    }

    @Test fun pickerPreview_render() {
        val items = pickerItems()
        var picked: FileItem? = null
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                PickerContent(
                    ui = PickerUi(PickerRequest.from("*/*", null, false), PickerSection.BROWSE, items, folder = File(root, "Download").path, preview = items[2]),
                    selected = emptySet(), onSection = {}, onQuery = {}, onOpenFolder = {}, onUp = {},
                    onTap = {}, onLongPress = {}, onDone = {}, onCancel = {}, onAllowAccess = {},
                    onPreviewSelect = { picked = it },
                )
            }
        }
        compose.onNodeWithText("Ship 1.0.21", substring = true).assertExists()
        compose.onNodeWithText("TXT ·", substring = true).assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_picker_preview.png")
        compose.onNodeWithText("Select").performClick()
        assertEquals("notes.txt", picked?.name)
    }

    // ---------- camera review ----------

    @Test fun cameraReviewIntentsReachFiles() {
        val pm = ctx.packageManager
        val photo = file("DCIM/Camera/IMG_20261031_195500.jpg")
        for (action in listOf(CameraReview.REVIEW, CameraReview.REVIEW_SECURE, CameraReview.OLD_REVIEW, Intent.ACTION_VIEW)) {
            for (uri in listOf(Uri.parse("content://media/external/images/media/42"), Uri.fromFile(photo))) {
                val i = Intent(action).setDataAndType(uri, "image/jpeg").setPackage(ctx.packageName)
                val hits = pm.queryIntentActivities(i, 0).map { it.activityInfo.name }
                assertTrue("$action $uri → $hits", hits.any { it.endsWith("OpenWithActivity") })
            }
            val v = Intent(action).setDataAndType(Uri.parse("content://media/external/video/media/7"), "video/mp4").setPackage(ctx.packageName)
            assertTrue(action, pm.queryIntentActivities(v, 0).any { it.activityInfo.name.endsWith("OpenWithActivity") })
        }
    }

    @Test fun cameraReviewUriFromDataClipOrStream() {
        val u = Uri.parse("content://media/external/images/media/42")
        assertEquals(u, CameraReview.uriOf(Intent(CameraReview.REVIEW).setData(u)))
        assertEquals(u, CameraReview.uriOf(Intent(CameraReview.REVIEW).apply { clipData = android.content.ClipData.newRawUri("", u) }))
        assertEquals(u, CameraReview.uriOf(Intent(CameraReview.OLD_REVIEW).putExtra(Intent.EXTRA_STREAM, u)))
        assertTrue(CameraReview.isSecure(Intent(CameraReview.REVIEW_SECURE)))
        assertFalse(CameraReview.isSecure(Intent(CameraReview.REVIEW)))
        // Lock screen: only that photo, over the lock screen, no app-lock PIN on top.
        val ow = File("src/main/java/com/clpstudio/filesmanager/viewer/OpenWithActivity.kt").readText()
        assertTrue(ow.contains("if (CameraReview.isSecure(intent)) listOf(file.absolutePath)"))
        assertTrue(ow.contains("setShowWhenLocked(true)"))
        assertTrue(File("src/main/java/com/clpstudio/filesmanager/security/AppLock.kt").readText().contains("CameraReview.REVIEW_SECURE) return"))
    }
}
