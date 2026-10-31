package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.OpenRules
import com.clpstudio.filesmanager.ui.screens.DraggableMiniPlayer
import com.clpstudio.filesmanager.ui.screens.HomeContent
import com.clpstudio.filesmanager.ui.screens.HomeState
import com.clpstudio.filesmanager.ui.screens.MiniPlayerPosition
import com.clpstudio.filesmanager.ui.screens.PullToSearch
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.ui.vault.VaultResetDialogs
import com.clpstudio.filesmanager.ui.vault.resetConfirmEnabled
import com.clpstudio.filesmanager.vault.SoftwareKeyWrapper
import com.clpstudio.filesmanager.vault.VaultStore
import com.clpstudio.filesmanager.viewer.FONT_PREVIEW_TAG
import com.clpstudio.filesmanager.viewer.FontPreviewContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 57 (draggable mini player), 58 (vault reset), 59 (open directly + font preview), 60 (pull down to search). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class Round10cTest {
    @get:Rule val compose = createComposeRule()
    private val shots = "/home/user/tab/files"
    private lateinit var ctx: Application

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Graph.init(ctx)
        ctx.getSharedPreferences("mini_player", 0).edit().clear().commit()
        MiniPlayerPosition.resetForTest()
    }

    // ---------- mini player drag ----------

    @Test fun miniPlayerDefaultsToBottomClampsAndPersists() {
        assertEquals(0f, MiniPlayerPosition.fraction.value)
        assertEquals(0f, MiniPlayerPosition.clampLift(-50f, 1000f))
        assertEquals(1000f, MiniPlayerPosition.clampLift(5000f, 1000f))
        assertEquals(300f, MiniPlayerPosition.clampLift(300f, 1000f))
        assertEquals(0f, MiniPlayerPosition.clampLift(300f, -10f)) // no room: stays docked
        assertEquals(0.3f, MiniPlayerPosition.fractionOf(300f, 1000f))
        assertEquals(0f, MiniPlayerPosition.snap(0.02f)) // near the bottom snaps back to docked
        MiniPlayerPosition.set(0.55f)
        assertEquals(0.55f, MiniPlayerPosition.fraction.value)
        MiniPlayerPosition.resetForTest() // "restart": read again from disk
        assertEquals(0.55f, MiniPlayerPosition.fraction.value)
        // Source: the floating one is used, lists keep room only while docked.
        val root = File("src/main/java/com/clpstudio/filesmanager/ui/AppRoot.kt").readText()
        assertTrue(root.contains("DraggableMiniPlayer("))
        assertTrue(root.contains("MiniPlayerPosition.fraction.collectAsState().value == 0f"))
    }

    private val song = PlayerUi(hasMedia = true, isPlaying = true, title = "Kesariya", artist = "Arijit Singh", duration = 268_000, position = 61_000)

    private fun miniShot(name: String) {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        HomeContent(HomeState(used = 61_000_000_000, total = 128_000_000_000), {}, {}, {}, {}, { _, _ -> })
                        DraggableMiniPlayer(song, onOpen = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Kesariya").assertExists()
        compose.onRoot().captureRoboImage("$shots/$name")
    }

    @Test fun miniPlayerAtBottom_render() = miniShot("files_r10_mini_bottom.png")

    @Test fun miniPlayerMovedUp_render() {
        MiniPlayerPosition.set(0.6f)
        miniShot("files_r10_mini_moved_up.png")
    }

    // ---------- vault reset ----------

    @Test fun resetWipesVaultKeepsOtherFilesAndAllowsNewSetup() {
        val dir = File(ctx.cacheDir, "vault_reset").apply { deleteRecursively() }
        val other = File(ctx.cacheDir, "my_notes.txt").apply { writeText("keep me") }
        val s = VaultStore(dir, SoftwareKeyWrapper(), iterations = 2000, chunk = 4096)
        s.setup(CredentialType.PIN, "4321")
        s.importFile(File(ctx.cacheDir, "secret.jpg").apply { writeBytes(ByteArray(4096) { 7 }) })
        s.lock()
        assertTrue(dir.walkTopDown().any { it.name == "keys.properties" })
        // Forgot the PIN: reset without it.
        s.deleteVault()
        assertFalse(s.isSetUp)
        assertTrue(dir.walkTopDown().none { it.isFile })
        assertEquals("keep me", other.readText())
        // A new vault works.
        s.setup(CredentialType.PASSWORD, "newpass12")
        assertTrue(s.isSetUp)
        assertEquals(0, s.list().size)
        val src = File("src/main/java/com/clpstudio/filesmanager/vault/Vault.kt").readText()
        assertTrue(src.contains("suspend fun resetForgotten()"))
        assertTrue(src.substringAfter("suspend fun resetForgotten()").substringBefore("fun lock()").contains("attempts = AttemptState()"))
    }

    @Test fun resetNeedsTypedDeleteAndFiveSeconds() {
        assertFalse(resetConfirmEnabled("DELETE", 3))
        assertFalse(resetConfirmEnabled("delete", 0))
        assertFalse(resetConfirmEnabled("", 0))
        assertTrue(resetConfirmEnabled("DELETE", 0))
        assertTrue(resetConfirmEnabled(" DELETE ", 0))
        // The link is on the unlock screen, outside the lockout check.
        assertTrue(File("src/main/java/com/clpstudio/filesmanager/ui/vault/VaultScreens.kt").readText().contains("? Reset vault"))
    }

    @Test fun resetWarning_render() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { VaultResetDialogs(1, onStep = {}, onReset = {}) } }
        compose.onNodeWithText("cannot be recovered", substring = true).assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_vault_reset_warning.png")
    }

    // (Step 2 — "type DELETE" — is not rendered here: any Material dialog holding a text field never goes idle in
    // this test machine, checked with a plain AlertDialog + OutlinedTextField. Its rules are tested above.)

    // ---------- open directly ----------

    @Test fun supportedTypesNeverGoToTheCardOrOutside() {
        val supported = listOf(
            "a.jpg", "a.heic", "a.avif", "a.webp", "a.gif", "a.svg", "a.mp4", "a.mkv", "a.3gp", "a.mp3", "a.m4a", "a.opus", "a.flac",
            "a.pdf", "a.txt", "a.json", "a.xml", "a.log", "a.csv", "a.srt", "a.vcf", "a.ics", "a.html", "a.md",
            "a.docx", "a.xlsx", "a.pptx", "a.epub", "a.zip", "a.apk", "a.xapk", "a.ttf", "a.otf", "a.psd",
        )
        for (n in supported) assertFalse(n, OpenRules.opensOutside(n))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("contact.vcf"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("event.ics"))
        assertEquals(FileKind.FONT, FileTypes.kindOf("ABHILASH (1).ttf"))
        // Truly unsupported → Android's "Open with".
        for (n in listOf("a.bin", "a.doc", "a.odt", "a.xyz")) assertTrue(n, OpenRules.opensOutside(n))
        // The pager draws fonts itself.
        assertTrue(File("src/main/java/com/clpstudio/filesmanager/viewer/FilePagerScreen.kt").readText().contains("FileKind.FONT -> FontPreviewContent("))
    }

    @Test fun fontPreview_render() {
        val font = File("/usr/share/fonts").walkTopDown().firstOrNull { it.extension.lowercase() == "ttf" }
            ?: File(System.getProperty("java.home"), "lib/fonts").walkTopDown().firstOrNull { it.extension.lowercase() == "ttf" }
        org.junit.Assume.assumeTrue("no ttf on this machine", font != null)
        val copy = File(ctx.cacheDir, "ABHILASH (1).ttf").apply { writeBytes(font!!.readBytes()) }
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FontPreviewContent(android.net.Uri.fromFile(copy), copy.name, topPadding = 24.dp)
                }
            }
        }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(FONT_PREVIEW_TAG).assertExists() }.isSuccess }
        compose.onNodeWithText("Aa Bb Cc").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_font_preview.png")
    }

    // ---------- search: blank tap ----------

    @Test fun searchBlankTapClosesOnlyWhenEmpty() {
        assertEquals(com.clpstudio.filesmanager.ui.screens.SearchBlankTap.Action.CLOSE, com.clpstudio.filesmanager.ui.screens.SearchBlankTap.action("", emptySet(), com.clpstudio.filesmanager.data.QuickDate.ANY))
        assertEquals(com.clpstudio.filesmanager.ui.screens.SearchBlankTap.Action.CLOSE, com.clpstudio.filesmanager.ui.screens.SearchBlankTap.action("   ", emptySet(), com.clpstudio.filesmanager.data.QuickDate.ANY))
        assertEquals(com.clpstudio.filesmanager.ui.screens.SearchBlankTap.Action.HIDE_KEYBOARD, com.clpstudio.filesmanager.ui.screens.SearchBlankTap.action("inv", emptySet(), com.clpstudio.filesmanager.data.QuickDate.ANY))
        assertEquals(com.clpstudio.filesmanager.ui.screens.SearchBlankTap.Action.HIDE_KEYBOARD, com.clpstudio.filesmanager.ui.screens.SearchBlankTap.action("", emptySet(), com.clpstudio.filesmanager.data.QuickDate.TODAY))
    }

    private fun searchTap(text: String): Boolean {
        var closed = false
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                com.clpstudio.filesmanager.ui.screens.SearchContent(
                    root = null, text = text, onText = {}, typedRangeLabel = null,
                    quick = com.clpstudio.filesmanager.data.QuickDate.ANY, onQuick = {}, customLabel = null,
                    types = emptySet(), onToggleType = {}, queryEmpty = text.isBlank(), results = emptyList(), searching = false,
                    onBack = { closed = true }, onOpen = {}, autoFocus = false,
                )
            }
        }
        compose.onNodeWithTag("searchBlank").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height - 60f)) }
        compose.waitForIdle()
        return closed
    }

    @Test fun searchEmptyBlankTapCloses() { assertTrue(searchTap("")) }

    @Test fun searchTypedBlankTapStays() { assertFalse(searchTap("invoice")) }

    // ---------- pull to search ----------

    @Test fun pullToSearchThreshold() {
        val t = 264f
        assertFalse(PullToSearch.shouldOpen(200f, t, startedAtTop = true))
        assertTrue(PullToSearch.shouldOpen(264f, t, startedAtTop = true))
        assertFalse("a pull that began mid-list only scrolls", PullToSearch.shouldOpen(400f, t, startedAtTop = false))
        assertTrue(PullToSearch.crossed(250f, 270f, t)); assertFalse(PullToSearch.crossed(270f, 300f, t))
        assertTrue(PullToSearch.resist(100f, 0f, t) < 100f)
        assertTrue(PullToSearch.resist(100f, 300f, t) < PullToSearch.resist(100f, 0f, t))
        assertEquals(0.5f, PullToSearch.progress(132f, t))
        assertEquals(88.dp, PullToSearch.THRESHOLD)
    }

    @Test fun pullToSearchMidPull_render() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                HomeContent(HomeState(used = 61_000_000_000, total = 128_000_000_000), {}, {}, {}, {}, { _, _ -> }, initialPull = 0.75f)
            }
        }
        compose.onNodeWithText("Pull to search").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_pull_to_search.png")
    }
}
