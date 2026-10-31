package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.components.CompressFields
import com.clpstudio.filesmanager.ui.components.rememberCompressForm
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.FileDetails
import com.clpstudio.filesmanager.viewer.FileInfoSheetContent
import com.clpstudio.filesmanager.viewer.InfoTarget
import com.clpstudio.filesmanager.viewer.SheetAction
import com.clpstudio.filesmanager.viewer.SpeedPill
import com.clpstudio.filesmanager.viewer.TextContent
import com.clpstudio.filesmanager.viewer.ViewerTopBar
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the round-2 screens: info sheet, locked-zip dialog, 2x pill, paper-page preview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class Round2ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")

    @Before fun setUp() { out.mkdirs() }

    private fun photo(w: Int = 1080, h: Int = 1440): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1E6FF0.toInt(), 0xFFF6B800.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.35f, h * 0.35f, w * 0.18f, Paint().apply { color = 0x66FFFFFF })
        c.drawCircle(w * 0.7f, h * 0.65f, w * 0.25f, Paint().apply { color = 0x33000000 })
        return bmp
    }

    private fun set(content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
    }

    @Test fun infoSheet() {
        val target = InfoTarget(Uri.parse("file:///storage/emulated/0/DCIM/Camera/IMG_20261031_104512.jpg"), "IMG_20261031_104512.jpg", "/storage/emulated/0/DCIM/Camera/IMG_20261031_104512.jpg", "image/jpeg")
        val details = FileDetails(
            target,
            listOf(
                "Name" to target.name,
                "Size" to "3.4 MB (3,561,204 bytes)",
                "Type" to "Photo · image/jpeg",
                "Modified" to "31 Oct 2026, 10:45 AM",
                "Folder" to "Internal storage/DCIM/Camera",
                "Resolution" to "4000 × 3000 (12.0 MP)",
                "Camera" to "samsung SM-X210",
            ),
        )
        val bmp = photo()
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                        Box(Modifier.padding(top = 18.dp)) {
                            FileInfoSheetContent(
                                target, details,
                                listOf(
                                    SheetAction(Icons.Filled.Share, "Share") {}, SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open with") {},
                                    SheetAction(Icons.Filled.Folder, "Show in folder") {}, SheetAction(Icons.Filled.Delete, "Delete") {},
                                ),
                            )
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_info_sheet.png").path)
    }

    /** The locked-zip dialog as it looks on screen (drawn in place: dialog windows don't render here). */
    @Test fun lockedZipDialog() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                val form = rememberCompressForm("Trip photos.zip").apply { pass = "s3cret!"; repeat = "s3cret!"; visible = false }
                Box(Modifier.fillMaxSize().background(Color(0xFF6B7280)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(28.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.secondary)
                        Text("Locked zip", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 14.dp))
                        CompressFields(form, withPassword = true)
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {}) { Text("Cancel") }
                            TextButton(onClick = {}) { Text("Lock & compress") }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage(File(out, "files_r2_zip_password.png").path)
    }

    @Test fun speedPill() {
        val bmp = photo(1080, 1920)
        set {
            FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    SpeedPill(Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_2x_pill.png").path)
    }

    @Test fun docPreview() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val json = File(ctx.cacheDir, "settings.json").apply {
            writeText(
                """
                {
                  "app": "Files",
                  "publisher": "CLP Studio",
                  "version": "1.0.3",
                  "offline": true,
                  "features": [
                    "All files by default",
                    "Locked zip (AES-256)",
                    "PDF passwords",
                    "Swipe up for file info",
                    "2x hold on videos"
                  ],
                  "theme": { "mode": "system", "liquidGlass": true, "accent": "#1E6FF0" }
                }
                """.trimIndent(),
            )
        }
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    TextContent(Uri.fromFile(json))
                    ViewerTopBar("settings.json", "1 of 4", {}, titleColor = MaterialTheme.colorScheme.primary) {
                        IconButton(onClick = {}) { Icon(Icons.Filled.Info, null) }
                        IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, null) }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_doc_preview.png").path)
    }
}
