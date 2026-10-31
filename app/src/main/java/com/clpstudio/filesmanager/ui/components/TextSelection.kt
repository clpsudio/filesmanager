package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.clpstudio.filesmanager.Graph

/** Tests / previews: force text selection on or off (null = follow Settings). */
val LocalTextSelectionOverride = staticCompositionLocalOf<Boolean?> { null }

/** Settings → "Allow text selection in documents" (item 51, default on), live. */
@Composable
fun textSelectionOn(): Boolean {
    LocalTextSelectionOverride.current?.let { return it }
    val flow = remember { runCatching { Graph.settings }.getOrNull() }
    return flow?.collectAsState()?.value?.allowTextSelection ?: true
}

/**
 * Wraps a document's text: with the setting on, long-press selects text with handles and the
 * system Copy / Share / Select all bar; off, the same content with no selection.
 */
@Composable
fun DocumentSelection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (textSelectionOn()) SelectionContainer(modifier.testTag(DOC_SELECTION_TAG)) { content() } else content()
}

const val DOC_SELECTION_TAG = "docSelection"
