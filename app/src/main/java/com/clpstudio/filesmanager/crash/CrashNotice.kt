package com.clpstudio.filesmanager.crash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val CRASH_NOTICE_TEXT = "The app closed unexpectedly. Report saved in Settings → Crash reports."

/**
 * Shows the one-time snackbar on the first launch after a crash. Put it last in the root Box so it
 * draws on top. [onOpen] opens Settings → Crash reports.
 */
@Composable
fun CrashNoticeHost(onOpen: () -> Unit) {
    val context = LocalContext.current
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        val pending = withContext(Dispatchers.IO) { runCatching { CrashLog.consumePendingNotice(context) }.getOrDefault(false) }
        if (pending) {
            val r = host.showSnackbar(CRASH_NOTICE_TEXT, actionLabel = "View", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) onOpen()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host, Modifier.navigationBarsPadding().padding(bottom = 72.dp))
    }
}
