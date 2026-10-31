package com.clpstudio.filesmanager.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The small "2x ▶▶" pill shown while a long press plays the video at double speed. */
@Composable
fun SpeedPill(modifier: Modifier = Modifier, label: String = "2x") {
    Row(
        modifier.background(Color(0xCC000000), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ▶▶", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

class VideoOverlayState {
    var fast by androidx.compose.runtime.mutableStateOf(false)
    var controls by androidx.compose.runtime.mutableStateOf(true)
    var info by androidx.compose.runtime.mutableStateOf<InfoTarget?>(null)
    var orientationLocked by androidx.compose.runtime.mutableStateOf(false)
}

/** Drawn above the video: the 2x pill, the top-right buttons (with the player controls) and the info sheet. */
@Composable
fun VideoOverlay(
    state: VideoOverlayState,
    onInfo: () -> Unit,
    onRotate: () -> Unit,
    onAutoRotate: () -> Unit,
    sheetActions: List<SheetAction>,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.fast, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 28.dp)) {
            SpeedPill()
        }
        AnimatedVisibility(state.controls && !state.fast, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
            Row(Modifier.background(Color(0x66000000), RoundedCornerShape(24.dp))) {
                IconButton(onClick = onInfo) { Icon(Icons.Filled.Info, "File info", tint = Color.White) }
                IconButton(onClick = onRotate) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
                if (state.orientationLocked) IconButton(onClick = onAutoRotate) { Icon(Icons.Filled.ScreenLockRotation, "Auto-rotate", tint = Color.White) }
            }
        }
    }
    state.info?.let { t -> FileInfoSheet(t, sheetActions) { state.info = null } }
}

fun videoSheetActions(onShare: () -> Unit, onOpenWith: () -> Unit, onFolder: (() -> Unit)?): List<SheetAction> = listOfNotNull(
    SheetAction(Icons.Filled.Share, "Share", onShare),
    SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open with", onOpenWith),
    onFolder?.let { SheetAction(Icons.Filled.Folder, "Show in folder", it) },
)
