package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.TypeFilters
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.components.TypeChipsRow
import com.clpstudio.filesmanager.ui.components.ViewSortRow
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Markdown viewer renders (dark + light). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class MarkdownScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private val sample = """
        # Files app notes

        A **fully offline** file manager. Open any `.md` file and it reads like a *real* document, with [links](https://example.com) that open only when tapped.

        ## To do
        - [x] Dark and light themes
        - [x] Markdown preview
        - [ ] SD card on Home
        - Plain bullet
          - Nested item
        1. First step
        2. Second step

        > Tip: switch to Source in the top bar to see the raw text.

        | Type | Count | Size |
        |:-----|------:|-----:|
        | Photos | 1,240 | 3.2 GB |
        | Videos | 86 | 11.4 GB |
        | Docs | 312 | 450 MB |

        ```kotlin
        fun greet(name: String) = "Hello, ${'$'}name"
        ```

        ---
        ![Screenshot](https://example.com/shot.png)
    """.trimIndent()

    private fun shot(name: String, mode: ThemeMode) {
        out.mkdirs()
        compose.setContent {
            FilesTheme(mode, liquidGlass = true) {
                com.clpstudio.filesmanager.viewer.MarkdownDocument(sample, topPadding = 24.dp)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    @Test fun dark() = shot("files_r3_markdown_dark", ThemeMode.DARK)

    @Test fun light() = shot("files_r3_markdown_light", ThemeMode.LIGHT)
}
