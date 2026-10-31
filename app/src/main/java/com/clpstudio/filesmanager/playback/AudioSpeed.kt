package com.clpstudio.filesmanager.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Audio playback speed (item 52): 0.5x–2x, natural pitch, the same for every song in the queue and
 * remembered (Settings key, last used). Separate from the video speed.
 */
object AudioSpeed {
    val OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    @Volatile private var chosen: Float? = null

    fun clamp(s: Float): Float = OPTIONS.minBy { abs(it - s) }

    /** The remembered speed (what was last chosen, else the saved setting, else 1x). */
    val current: Float
        get() = chosen ?: runCatching { com.clpstudio.filesmanager.Graph.settings.value.audioSpeed }.getOrDefault(1f).let(::clamp)

    /** Natural pitch at any speed. */
    fun params(s: Float = current): PlaybackParameters = PlaybackParameters(clamp(s), 1f)

    /** Chosen in the player: remembered (in memory now, saved for next time). */
    fun set(s: Float) {
        val v = clamp(s)
        chosen = v
        runCatching { com.clpstudio.filesmanager.Graph.appScope.launch { com.clpstudio.filesmanager.Graph.settingsRepo.setAudioSpeed(v) } }
    }

    fun label(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

    /** Tests only. */
    internal fun resetForTest() { chosen = null }
}

/** The "1x" chip in the audio players: tap → 0.5x … 2x. */
@Composable
fun AudioSpeedChip(speed: Float, onSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        val on = abs(speed - 1f) > 0.01f
        AssistChip(
            onClick = { open = true },
            label = { Text("Speed " + AudioSpeed.label(speed)) },
            leadingIcon = { Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)) },
            colors = if (on) AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                labelColor = MaterialTheme.colorScheme.primary,
                leadingIconContentColor = MaterialTheme.colorScheme.primary,
            ) else AssistChipDefaults.assistChipColors(),
        )
        DropdownMenu(open, { open = false }) {
            for (s in AudioSpeed.OPTIONS) {
                DropdownMenuItem(
                    text = { Text(AudioSpeed.label(s) + if (s == 1f) " (normal)" else "") },
                    trailingIcon = { if (abs(s - speed) < 0.01f) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { open = false; onSpeed(s) },
                )
            }
        }
    }
}
