package io.github.ddagunts.screencast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.cast.CastVolume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

@Composable
fun CastControlScreen(vm: CastViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val idle = phase is CastForegroundService.Phase.Idle

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        StatusBlock(
            phase = phase,
            playerState = playerState,
            onStop = { vm.stopCast() },
            onPause = { vm.pause() },
            onPlay = { vm.play() },
        )
        if (!idle) {
            Spacer(Modifier.height(8.dp))
            VolumeBlock(
                volume = volume,
                onLevel = { vm.setVolume(it) },
                onMute = { vm.setMute(it) },
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            if (idle) "Available Chromecasts" else "Stop the current cast to switch devices",
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))

        if (devices.isEmpty()) {
            var timedOut by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(30_000); timedOut = true }
            Text(
                if (timedOut) "No devices found. Check Wi-Fi and that the Chromecast is powered on."
                else "Discovering Chromecasts on LAN…"
            )
            return
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(devices) { d ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .then(if (idle) Modifier.clickable { vm.startCast(d) } else Modifier)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name, fontWeight = FontWeight.Bold)
                        Text(d.host)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(
    phase: CastForegroundService.Phase,
    playerState: String,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onPlay: () -> Unit,
) {
    val (title, detail) = when (val p = phase) {
        CastForegroundService.Phase.Idle -> "Idle" to "Pick a device below to start casting."
        is CastForegroundService.Phase.Starting -> "Starting cast to ${p.device.name}" to p.url
        is CastForegroundService.Phase.Casting -> "Casting to ${p.device.name}" to p.url
        is CastForegroundService.Phase.Error -> "Error" to p.message
    }
    val url = when (val p = phase) {
        is CastForegroundService.Phase.Starting -> p.url
        is CastForegroundService.Phase.Casting -> p.url
        else -> null
    }
    // Transport controls only make sense when a media session is live; before
    // LOAD completes the receiver has no mediaSessionId to target.
    val casting = phase is CastForegroundService.Phase.Casting
    val isPaused = playerState.equals("PAUSED", ignoreCase = true)
    val isBuffering = playerState.equals("BUFFERING", ignoreCase = true)
    val ctx = LocalContext.current
    Text(title, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(detail)
    if (casting) {
        Spacer(Modifier.height(2.dp))
        Text("Player: $playerState")
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onStop,
            enabled = phase !is CastForegroundService.Phase.Idle,
        ) { Text("Stop") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = if (isPaused) onPlay else onPause,
            enabled = casting && !isBuffering,
        ) { Text(if (isPaused) "Play" else "Pause") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { url?.let { copyUrlToClipboard(ctx, it) } },
            enabled = url != null,
        ) { Text("Copy URL") }
    }
}

@Composable
private fun VolumeBlock(
    volume: CastVolume,
    onLevel: (Double) -> Unit,
    onMute: (Boolean) -> Unit,
) {
    // Mirror the receiver's level when the user isn't dragging. During a drag
    // we push updates live to the TV via a conflated channel throttled to
    // ~10 Hz — fast enough that the TV appears to track the finger, slow
    // enough that the TLS socket and receiver aren't flooded.
    var dragging by remember { mutableStateOf(false) }
    var draft by remember { mutableFloatStateOf(volume.level.toFloat()) }
    LaunchedEffect(volume.level, dragging) {
        if (!dragging) draft = volume.level.toFloat()
    }
    val dragChannel = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (v in dragChannel) {
            onLevel(v.toDouble())
            delay(100)
        }
    }
    val enabled = !volume.isFixed
    Text("Volume", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = draft,
            onValueChange = {
                dragging = true
                draft = it
                dragChannel.trySend(it)
            },
            onValueChangeFinished = {
                dragging = false
                // Guaranteed final send so the receiver lands on the exact
                // release position even if the last drag emit was throttled.
                onLevel(draft.toDouble())
            },
            valueRange = 0f..1f,
            enabled = enabled && !volume.muted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text("${(draft * 100).toInt()}%")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = volume.muted,
            onCheckedChange = onMute,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        Text(if (volume.muted) "Muted" else "Mute")
        if (volume.isFixed) {
            Spacer(Modifier.width(12.dp))
            Text("(receiver volume is fixed)")
        }
    }
}

private fun copyUrlToClipboard(ctx: Context, url: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ScreenCast stream URL", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
        Toast.makeText(ctx, "Stream URL copied", Toast.LENGTH_SHORT).show()
    }
}
