package com.clpstudio.filesmanager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.ui.AppState
import kotlinx.coroutines.delay
import java.io.File

/** "Extracted to Photos   [Open folder]" at the bottom of the screen, gone after a few seconds. */
@Composable
fun DoneActionHost(onOpen: ((File) -> Unit)?) {
    val done = AppState.doneAction
    LaunchedEffect(done) {
        if (done != null) {
            delay(7000)
            if (AppState.doneAction === done) AppState.doneAction = null
        }
    }
    LaunchedEffect(AppState.highlight) {
        if (AppState.highlight != null) {
            delay(6000)
            AppState.highlight = null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(done != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            val d = done ?: AppState.DoneAction("", "", File("/"))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.message, modifier = Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    if (onOpen != null) {
                        TextButton(onClick = { AppState.doneAction = null; onOpen(d.target) }) {
                            Text(d.label, color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
