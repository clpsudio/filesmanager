package com.clpstudio.filesmanager

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.FileDetails
import com.clpstudio.filesmanager.viewer.FileInfoSheetContent
import com.clpstudio.filesmanager.viewer.InfoEdit
import com.clpstudio.filesmanager.viewer.InfoTarget
import com.clpstudio.filesmanager.viewer.SheetAction
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 15: the info sheet with editable Name, Modified (date & time) and Folder rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class InfoEditScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun infoSheetEditable() {
        val path = "/storage/emulated/0/DCIM/Camera/IMG_20261031_104512.jpg"
        val target = InfoTarget(Uri.parse("file://$path"), "IMG_20261031_104512.jpg", path, "image/jpeg")
        val details = FileDetails(
            target,
            listOf(
                "Name" to target.name,
                "Size" to "3.4 MB (3,561,204 bytes)",
                "Type" to "Photo · image/jpeg",
                "Modified" to "31 Oct 2026, 10:45 AM",
                "Folder" to "Internal storage/DCIM/Camera",
                "Path" to path,
                "Resolution" to "4000 × 3000 (12.0 MP)",
                "Taken" to "2026:10:31 10:45:12",
                "Camera" to "samsung SM-X210",
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0F17))) {
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                    Column(
                        Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                        Box(Modifier.padding(top = 18.dp)) {
                            FileInfoSheetContent(
                                target, details,
                                listOf(
                                    SheetAction(Icons.Filled.Share, "Share") {}, SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open with") {},
                                    SheetAction(Icons.Filled.Folder, "Show in folder") {}, SheetAction(Icons.Filled.Delete, "Delete") {},
                                ),
                                editable = setOf(InfoEdit.NAME, InfoEdit.DATE, InfoEdit.FOLDER),
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage(File(out, "files_r5_info_edit.png").path)
    }
}
