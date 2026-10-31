package com.clpstudio.filesmanager

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.playback.AudioSpeed
import com.clpstudio.filesmanager.playback.AudioSpeedChip
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.security.AttemptLimiter
import com.clpstudio.filesmanager.security.AttemptState
import com.clpstudio.filesmanager.security.AttemptStore
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.security.LockClock
import com.clpstudio.filesmanager.ui.components.DOC_SELECTION_TAG
import com.clpstudio.filesmanager.ui.components.LocalTextSelectionOverride
import com.clpstudio.filesmanager.ui.security.UnlockForm
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.MusicPlayerContent
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 51 (text selection setting), item 52 (audio speed), item 53 (vault/app-lock wrong-attempt limit with countdown). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class Round10Test {
    @get:Rule val compose = createComposeRule()
    private val shots = "/home/user/tab/files"
    private lateinit var ctx: Application

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Graph.init(ctx)
    }

    // ---------- text selection ----------

    @Test fun textSelectionSettingDefaultsOn() {
        assertTrue(AppSettings().allowTextSelection)
    }

    private fun textFile(): File = File(ctx.cacheDir, "notes.txt").apply {
        writeText("Meeting notes 31 Oct\nBudget: 4,500\nCall Ravi about the Files app release\nShip 1.0.21 tonight\n")
    }

    @Test fun textViewerSelectableWhenOn_render() {
        val f = textFile()
        compose.setContent {
            CompositionLocalProvider(LocalTextSelectionOverride provides true) {
                FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                    com.clpstudio.filesmanager.viewer.TextContent(android.net.Uri.fromFile(f), Modifier.fillMaxSize(), topPadding = 24.dp())
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(DOC_SELECTION_TAG).fetchSemanticsNodes().isNotEmpty() }
        // (A long-press in this test machine crashes inside the platform's text-classifier, so the handles
        // themselves can't be drawn here; the selectable container is checked instead.)
        compose.onNodeWithText("Call Ravi about the Files app release").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_text_selection.png")
    }

    @Test fun textViewerNotSelectableWhenOff() {
        val f = textFile()
        compose.setContent {
            CompositionLocalProvider(LocalTextSelectionOverride provides false) {
                FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                    com.clpstudio.filesmanager.viewer.TextContent(android.net.Uri.fromFile(f), Modifier.fillMaxSize())
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("x").fetchSemanticsNodes().isEmpty() && runCatching { compose.onNodeWithText("Budget: 4,500").assertExists() }.isSuccess }
        compose.onAllNodesWithTag(DOC_SELECTION_TAG).assertCountEquals(0)
    }

    @Test fun markdownSelectableWhenOn() {
        compose.setContent {
            CompositionLocalProvider(LocalTextSelectionOverride provides true) {
                FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { com.clpstudio.filesmanager.viewer.MarkdownDocument("# Title\n\nSome **bold** text.") }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(DOC_SELECTION_TAG).assertCountEquals(1)
    }

    @Test fun markdownNotSelectableWhenOff() {
        compose.setContent {
            CompositionLocalProvider(LocalTextSelectionOverride provides false) {
                FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { com.clpstudio.filesmanager.viewer.MarkdownDocument("# Title\n\nSome **bold** text.") }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(DOC_SELECTION_TAG).assertCountEquals(0)
    }

    @Test fun pdfSelectTextSheet_render() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { com.clpstudio.filesmanager.viewer.PdfPageTextDialog(0, "Invoice 2026-10-31\nCLP Studio\nTotal: 4,500", onDismiss = {}) } }
        compose.onAllNodesWithTag(DOC_SELECTION_TAG).assertCountEquals(1)
        compose.onNodeWithText("Copy all").assertExists()
        captureScreenRoboImage("$shots/files_r10_pdf_select_text.png")
    }

    @Test fun pdfScannedPageSaysNoText() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { com.clpstudio.filesmanager.viewer.PdfPageTextDialog(0, "", onDismiss = {}) } }
        compose.onNodeWithText("No selectable text on this page", substring = true).assertExists()
    }

    @Test fun everyDocumentViewerUsesTheSetting() {
        fun src(rel: String) = File("src/main/java/com/clpstudio/filesmanager/$rel").readText()
        for (f in listOf("viewer/TextViewerScreen.kt", "viewer/MarkdownViewer.kt", "viewer/office/DocxViewer.kt", "viewer/office/XlsxViewer.kt", "viewer/office/PptxViewer.kt")) {
            assertTrue("$f has no DocumentSelection", src(f).contains("DocumentSelection"))
        }
        assertTrue(src("viewer/EpubReaderScreen.kt").contains("textSelectionOn()"))
        assertTrue(src("viewer/PdfViewerScreen.kt").contains("textSelectionOn()"))
    }

    // ---------- audio speed ----------

    @Test fun audioSpeedOptionsAndNaturalPitch() {
        AudioSpeed.resetForTest()
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), AudioSpeed.OPTIONS)
        assertEquals(1f, AudioSpeed.current)
        AudioSpeed.set(1.5f)
        assertEquals(1.5f, AudioSpeed.current)
        assertEquals(1.5f, AudioSpeed.params().speed); assertEquals(1f, AudioSpeed.params().pitch)
        assertEquals(2f, AudioSpeed.clamp(3f)); assertEquals(0.5f, AudioSpeed.clamp(0.1f))
        assertEquals("1.25x", AudioSpeed.label(1.25f)); assertEquals("2x", AudioSpeed.label(2f))
        // On a real player: the speed holds across songs in the queue.
        val p = ExoPlayer.Builder(ctx).build()
        p.setMediaItems(listOf(MediaItem.fromUri("file:///a.mp3"), MediaItem.fromUri("file:///b.mp3")))
        p.playbackParameters = AudioSpeed.params()
        p.seekToNextMediaItem()
        assertEquals(1.5f, p.playbackParameters.speed); assertEquals(1f, p.playbackParameters.pitch)
        p.release()
        AudioSpeed.resetForTest()
    }

    @Test fun musicPlayerHasSpeedChip_render() {
        var chosen = 0f
        compose.setContent {
            FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                MusicPlayerContent(
                    ui = PlayerUi(hasMedia = true, isPlaying = true, title = "Kesariya", artist = "Arijit Singh", duration = 268_000, position = 61_000, speed = 1.25f),
                    onBack = {}, onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {}, onPick = {},
                    onSpeed = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("Speed 1.25x").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_audio_speed.png")
        compose.onNodeWithText("Speed 1.25x").performClick()
        compose.onNodeWithText("1.75x").performClick()
        assertEquals(1.75f, chosen)
    }

    // ---------- attempt limit + lockout ----------

    @Test fun lockoutAfterNFailsWithEscalation() {
        for (allowed in listOf(3, 5, 10)) {
            var s = AttemptState()
            var c = LockClock(10_000L, 1)
            repeat(allowed - 1) { s = AttemptLimiter.onFailure(s, c, allowed); assertEquals(0L, AttemptLimiter.remaining(s, c)) }
            val expected = listOf(30_000L, 60_000L, 300_000L, 900_000L, 1_800_000L, 1_800_000L)
            for (w in expected) {
                s = AttemptLimiter.onFailure(s, c, allowed)
                assertEquals("allowed=$allowed", w, AttemptLimiter.remaining(s, c))
                c = c.copy(elapsed = c.elapsed + w) // wait it out
                assertEquals(0L, AttemptLimiter.remaining(s, c))
            }
        }
        assertEquals(5, AttemptLimiter.allowed(7)); assertEquals(3, AttemptLimiter.allowed(3))
        assertEquals(5, AppSettings().wrongAttemptsAllowed)
    }

    @Test fun countdownTextAndReset() {
        val c = LockClock(0L, 1)
        var s = AttemptState()
        repeat(5) { s = AttemptLimiter.onFailure(s, c) }
        assertEquals("Too many wrong tries. Try again in 0:30", AttemptLimiter.message(s, c))
        assertEquals("Too many wrong tries. Try again in 0:01", AttemptLimiter.message(s, c.copy(elapsed = 29_200)))
        assertEquals("4:59", AttemptLimiter.clockText(299_000)); assertEquals("30:00", AttemptLimiter.clockText(1_800_000))
        assertEquals(AttemptState(), AttemptLimiter.onSuccess())
    }

    @Test fun lockoutSurvivesRestartRebootAndClockChange() {
        val prefs = ctx.getSharedPreferences("t_attempts", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val store = AttemptStore { prefs }
        val c0 = LockClock(50_000L, 3)
        var s = AttemptState()
        repeat(6) { s = AttemptLimiter.onFailure(s, c0) } // 6th → 1 min
        store.save(s)
        // App killed and opened again 20 s later: a new store over the same file.
        val again = AttemptStore { prefs }
        assertEquals(40_000L, again.remainingNow(c0.copy(elapsed = 70_000L)))
        // The phone's date/time changed: wall clock isn't used at all, nothing changes.
        assertEquals(40_000L, again.remainingNow(c0.copy(elapsed = 70_000L)))
        // Reboot (new boot, elapsed starts near 0): the time left is kept, then counts down again.
        val boot = LockClock(5_000L, 4)
        assertEquals(40_000L, again.remainingNow(boot))
        assertEquals(30_000L, again.remainingNow(boot.copy(elapsed = 15_000L)))
        // A right PIN resets it.
        again.save(AttemptLimiter.onSuccess())
        assertEquals(0L, again.remainingNow(boot.copy(elapsed = 16_000L)))
        assertEquals(0, again.load(boot).failures)
        // Old saves (wall-clock "locked_until") are read once.
        prefs.edit().clear().putInt("failures", 5).putLong("locked_until", System.currentTimeMillis() + 20_000).commit()
        val r = AttemptStore { prefs }.remainingNow(boot)
        assertTrue(r in 18_000L..20_000L)
    }

    @Test fun lockoutScreen_render() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    UnlockForm(
                        type = CredentialType.PIN, title = "Vault is locked", subtitle = "12 files · enter your vault PIN",
                        check = { "Wrong" }, onUnlocked = {}, remainingMs = { 27_000L },
                        extra = { locked -> if (!locked) androidx.compose.material3.Text("Use fingerprint") },
                    )
                }
            }
        }
        compose.onNodeWithText("Try again in 0:27").assertExists()
        compose.onAllNodesWithTag("x").assertCountEquals(0)
        assertTrue(runCatching { compose.onNodeWithText("Use fingerprint").assertExists() }.isFailure)
        compose.onRoot().captureRoboImage("$shots/files_r10_vault_lockout.png")
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
