package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.FileNames
import com.clpstudio.filesmanager.data.RenameOps
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.components.RenameFields
import com.clpstudio.filesmanager.ui.components.RenameForm
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 45: renaming changes the name, never the type by accident. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FileNamesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun split() {
        assertEquals(FileNames.Parts("photo", ".jpg"), FileNames.split("photo.jpg"))
        assertEquals(FileNames.Parts("backup", ".tar.gz"), FileNames.split("backup.tar.gz"))
        assertEquals(FileNames.Parts("my.trip.2024", ".mp4"), FileNames.split("my.trip.2024.mp4"))
        assertEquals(FileNames.Parts("README", ""), FileNames.split("README"))
        assertEquals(FileNames.Parts(".nomedia", ""), FileNames.split(".nomedia"))
        assertEquals(FileNames.Parts(".hidden", ".txt"), FileNames.split(".hidden.txt"))
        assertEquals(FileNames.Parts("notes.", ""), FileNames.split("notes."))
        assertEquals(FileNames.Parts("a", ".TAR.GZ"), FileNames.split("a.TAR.GZ"))
    }

    @Test fun lockedNameKeepsTheType() {
        assertEquals("holiday.jpg", FileNames.lockedName("holiday", ".jpg"))
        // Typing an extension doesn't replace the real one.
        assertEquals("holiday.txt.jpg", FileNames.lockedName("holiday.txt", ".jpg"))
        assertTrue(FileNames.extensionChanged("a.jpg", "a.txt"))
        assertFalse(FileNames.extensionChanged("a.jpg", "b.JPG"))
        assertTrue(FileNames.extensionChanged("a.jpg", "a"))
        assertEquals("Changing .jpg to .txt may make the file unusable.", FileNames.changeWarning("a.jpg", "a.txt"))
    }

    @Test fun renameWorksForEveryCommonTypeAndKeepsTheExtension() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(ctx.cacheDir, "rename-types").apply { deleteRecursively(); mkdirs() }
        val exts = listOf("pdf", "md", "txt", "docx", "csv", "json", "mp3", "m4a", "wav", "flac", "ogg", "mp4", "mkv", "mov", "3gp", "jpg", "jpeg", "png", "heic", "webp", "gif")
        for (e in exts) {
            val f = File(dir, "old.$e").apply { writeText("x") }
            val renamed = RenameOps.rename(ctx, f, FileNames.lockedName("New name", FileNames.split(f.name).ext))
            assertEquals("New name.$e", renamed.name)
            assertTrue(renamed.exists()); assertFalse(f.exists())
        }
        // Case-only change works too.
        val c = File(dir, "photo.jpg").apply { writeText("x") }
        assertEquals("Photo.jpg", RenameOps.rename(ctx, c, "Photo.jpg").name)
    }

    @Test fun lockedFormEditsOnlyTheName() {
        val f = RenameForm("Holiday photo.jpg", allowExtensionChange = false)
        assertEquals("Holiday photo", f.value.text)
        f.value = TextFieldValue("Beach.txt")
        assertEquals("Beach.txt.jpg", f.submit()) // a typed extension never replaces the real one
        f.value = TextFieldValue("  ")
        assertEquals(null, f.submit()); assertEquals("Type a name", f.error)
        compose.mainClock.autoAdvance = false
        val shown = RenameForm("Holiday photo.jpg", allowExtensionChange = false)
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF6B7280)), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(28.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp)) {
                        Text("Rename", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 14.dp))
                        RenameFields(shown)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("/home/user/tab/files/files_r8_rename_locked.png")
    }

    @Test fun allowedFormAsksBeforeChangingTheType() {
        val f = RenameForm("report.pdf", allowExtensionChange = true)
        assertEquals("report.pdf", f.value.text)
        f.value = TextFieldValue("Q3 report.pdf")
        assertEquals("Q3 report.pdf", f.submit()) // same type: no question
        f.value = TextFieldValue("report.txt")
        assertEquals(null, f.submit())
        assertEquals("report.txt", f.confirmTypeChange)
        assertEquals("report.pdf", f.keepType("report.txt"))
        assertEquals("Changing .pdf to .txt may make the file unusable.", FileNames.changeWarning("report.pdf", "report.txt"))
        val g = RenameForm("report.pdf", allowExtensionChange = true)
        g.value = TextFieldValue("Report.PDF")
        assertEquals("Report.PDF", g.submit()) // case only: no question
    }
}
