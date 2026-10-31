package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.BinEntry
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.TrashItem
import com.clpstudio.filesmanager.ui.glass.GlassBackground
import com.clpstudio.filesmanager.ui.screens.BinContent
import com.clpstudio.filesmanager.ui.screens.BinItemSheetContent
import com.clpstudio.filesmanager.ui.screens.toRow
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** Renders the Samsung-style Bin with sample entries (drawn in place, no repository). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class BinScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private val tmp = File("build/bin-samples")
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = System.currentTimeMillis()

    @Before fun setUp() { out.mkdirs(); tmp.mkdirs() }

    private fun photo(name: String, a: Int, b: Int): File {
        val f = File(tmp, name)
        val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 240f, a, b, Shader.TileMode.CLAMP) })
        c.drawCircle(90f, 90f, 50f, Paint().apply { color = 0x66FFFFFF })
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private fun entry(name: String, ago: Long, size: Long, dir: Boolean = false, data: File = File(tmp, name)) =
        BinEntry(name.hashCode().toString(), name, "/storage/emulated/0/Download/$name", now - ago, dir, size, data)

    private fun rows(): Pair<List<com.clpstudio.filesmanager.ui.screens.BinRowUi>, List<com.clpstudio.filesmanager.ui.screens.BinRowUi>> {
        val p1 = photo("IMG_20261031_105112.jpg", 0xFF1E6FF0.toInt(), 0xFFF6B800.toInt())
        val p2 = photo("Screenshot_20261030_221544_WhatsApp.jpg", 0xFF16A34A.toInt(), 0xFF0EA5E9.toInt())
        val p3 = photo("trash_photo.jpg", 0xFFE11D48.toInt(), 0xFF9333EA.toInt())
        File(tmp, "Old project").mkdirs()
        val local = listOf(
            entry("IMG_20261031_105112.jpg", 2 * 60_000, 3_561_204, data = p1),
            entry("Panthula Venkata Charan Raj Aadhaar Card.pdf", 40 * 60_000, 845_000),
            entry("Old project", 3 * 3600_000, 0, dir = true, data = File(tmp, "Old project")).toRow(now, count = 14).local!!,
            entry("Screenshot_20261030_221544_WhatsApp.jpg", day + 3600_000, 412_000, data = p2),
            entry("backup_2026_10_29.zip", day + 7200_000, 128_400_000),
            entry("Quarterly report final v3.docx", 5 * day + 1000, 98_200),
        ).map { if (it.isDir) it.toRow(now, count = 14) else it.toRow(now, count = -1) }
        val others = listOf(
            TrashItem("ms:external:1", "PXL_20261027_181203.jpg", p3.absolutePath, null, 2_400_000, false, now - 4 * day, now + 26 * day, "image/jpeg"),
            TrashItem("ms:external:2", "Voice note 12.m4a", null, null, 640_000, false, now - 9 * day, now + 21 * day, "audio/mp4"),
        ).map { it.toRow(now) }
        return local to others
    }

    private fun set(content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    @Test fun bin() {
        val (local, others) = rows()
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackground(Modifier.fillMaxSize())
                    BinContent(
                        local = local, others = others, othersSupported = true,
                        selectMode = false, selected = emptySet(), listState = rememberLazyListState(),
                        contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp), is24h = true,
                        onRowClick = {}, onDragSelect = { _, _ -> },
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_bin.png").path)
    }

    @Test fun binSelect() {
        val (local, others) = rows()
        val sel = setOf(local[0].key, local[1].key, local[2].key)
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackground(Modifier.fillMaxSize())
                    BinContent(
                        local = local, others = others, othersSupported = true,
                        selectMode = true, selected = sel, listState = rememberLazyListState(),
                        contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp), is24h = true,
                        onRowClick = {}, onDragSelect = { _, _ -> }, titleText = "3 selected",
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_bin_select.png").path)
    }

    @Test fun binOtherApps() {
        val (local, others) = rows()
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackground(Modifier.fillMaxSize())
                    BinContent(
                        local = local, others = others, othersSupported = true,
                        selectMode = false, selected = emptySet(), listState = rememberLazyListState(initialFirstVisibleItemIndex = 3),
                        contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp), is24h = true,
                        onRowClick = {}, onDragSelect = { _, _ -> },
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_bin_others.png").path)
    }

    @Test fun binSheet() {
        val (local, _) = rows()
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 28.dp),
                    ) {
                        BinItemSheetContent(local[1], is24h = true, onRestore = {}, onDelete = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_bin_sheet.png").path)
    }

    @Test fun binDark() {
        val (local, others) = rows()
        set {
            FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackground(Modifier.fillMaxSize())
                    BinContent(
                        local = local, others = others, othersSupported = true,
                        selectMode = false, selected = emptySet(), listState = rememberLazyListState(),
                        contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp), is24h = true,
                        onRowClick = {}, onDragSelect = { _, _ -> },
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r3_dark_bin.png").path)
    }

    @Test fun binLightR3() {
        val (local, others) = rows()
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackground(Modifier.fillMaxSize())
                    BinContent(
                        local = local, others = others, othersSupported = true,
                        selectMode = false, selected = emptySet(), listState = rememberLazyListState(),
                        contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp), is24h = true,
                        onRowClick = {}, onDragSelect = { _, _ -> },
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r3_light_bin.png").path)
    }
}
