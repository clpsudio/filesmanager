package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Long-press + drag across the grid and list selects the range under the finger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w384dp-h800dp-mdpi")
class DragSelectUiTest {
    @get:Rule val compose = createComposeRule()
    private val items = (0 until 40).map { FileItem("/x/f$it.bin", "f$it.bin", false, 1, 0) }

    private fun run(view: FileView, from: Offset, to: Offset): List<String> {
        val selection = SelectionState()
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                FileCollection(items, view, selection, onClick = { selection.toggle(it.path) }, contentPadding = PaddingValues(top = 40.dp))
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(700)
            moveTo(Offset((from.x + to.x) / 2, (from.y + to.y) / 2))
            moveTo(to)
            up()
        }
        compose.waitForIdle()
        return selection.paths.map { it.substringAfterLast('/').removeSuffix(".bin") }
    }

    @Test fun gridRange() {
        // 384dp wide -> 3 columns of 128dp; rows start at 40dp. Row 0 centre y = 40 + 64.
        val sel = run(FileView.GRID, Offset(64f, 104f), Offset(320f, 232f))
        assertEquals(listOf("f0", "f1", "f2", "f3", "f4", "f5"), sel.sortedBy { it.removePrefix("f").toInt() })
    }

    @Test fun listRange() {
        val sel = run(FileView.LIST, Offset(100f, 70f), Offset(100f, 330f)).map { it.removePrefix("f").toInt() }.sorted()
        // A contiguous range from the first row down to where the finger stopped (4+ rows).
        assertEquals((0 until sel.size).toList(), sel)
        org.junit.Assert.assertTrue("selected $sel", sel.size in 4..6)
    }

    @Test fun tapStillOpens() {
        val selection = SelectionState()
        var opened: String? = null
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                FileCollection(items, FileView.GRID, selection, onClick = { opened = it.name }, contentPadding = PaddingValues(top = 40.dp))
            }
        }
        compose.onRoot().performTouchInput { click(Offset(64f + 128f, 104f)) }
        compose.waitForIdle()
        assertEquals("f1.bin", opened)
        assertEquals(0, selection.paths.size)
    }
}
