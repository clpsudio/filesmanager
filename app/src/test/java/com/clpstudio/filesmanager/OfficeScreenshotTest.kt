package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.office.DocxContent
import com.clpstudio.filesmanager.viewer.office.PptxContent
import com.clpstudio.filesmanager.viewer.office.XlsxContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the Office viewers with sample files: ./gradlew :app:testDebugUnitTest --tests '*OfficeScreenshotTest*' */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class OfficeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTextExists() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(): Boolean =
        runCatching { onAllNodes(androidx.compose.ui.test.hasText("Quarterly", substring = true) or androidx.compose.ui.test.hasText("Apple pie") or androidx.compose.ui.test.hasText("Files app")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    @Test fun docx_light() {
        val f = SampleOffice.docx(tmp.newFile("sample.docx"), image = SampleOffice.png(240, 120))
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { DocxContent(f, contentPadding = PaddingValues(top = 8.dp)) } }
        shot("files_r2_docx")
    }

    @Test fun docx_dark() {
        val f = SampleOffice.docx(tmp.newFile("sample.docx"), image = SampleOffice.png(240, 120))
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { DocxContent(f) } }
        shot("files_r2_docx_dark")
    }

    @Test fun xlsx_light() {
        val f = SampleOffice2.xlsx(tmp.newFile("s.xlsx"))
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { XlsxContent(f) } }
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(androidx.compose.ui.test.hasText("Apple pie")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        shot("files_r2_xlsx")
    }

    @Test fun pptx_light() {
        val f = SampleOffice2.pptx(tmp.newFile("s.pptx"))
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { PptxContent(f) } }
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(androidx.compose.ui.test.hasText("Files app")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        shot("files_r2_pptx")
    }
}
