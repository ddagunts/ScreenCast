package io.github.ddagunts.screencast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastVolume
import kotlinx.coroutines.delay

@Composable
fun CastControlScreen(vm: CastViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val idle = phase is CastForegroundService.Phase.Idle

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            phase = phase,
            playerState = playerState,
            onStop = { vm.stopCast() },
            onPause = { vm.pause() },
            onPlay = { vm.play() },
        )
        if (!idle) VolumeCard(volume, onLevel = { vm.setVolume(it) }, onMute = { vm.setMute(it) })
        DeviceList(devices, idle, onPick = { vm.startCast(it) })
    }
}

@Composable
private fun StatusCard(
    phase: CastForegroundService.Phase,
    playerState: String,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onPlay: () -> Unit,
) {
    val casting = phase is CastForegroundService.Phase.Casting
    val starting = phase is CastForegroundService.Phase.Starting
    val errored = phase is CastForegroundService.Phase.Error
    val isPaused = playerState.equals("PAUSED", ignoreCase = true)
    val isBuffering = playerState.equals("BUFFERING", ignoreCase = true)

    val (title, subtitle) = when (val p = phase) {
        CastForegroundService.Phase.Idle -> "Not casting" to "Pick a device below."
        is CastForegroundService.Phase.Starting -> p.device.name to "Starting…"
        is CastForegroundService.Phase.Casting -> p.device.name to "Live"
        is CastForegroundService.Phase.Error -> "Error" to p.message
    }
    val url = when (val p = phase) {
        is CastForegroundService.Phase.Starting -> p.url
        is CastForegroundService.Phase.Casting -> p.url
        else -> null
    }
    val ctx = LocalContext.current

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(casting = casting, starting = starting, errored = errored, playerState = playerState)
            }

            if (!phase.isIdle) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Play/Pause is secondary (tonal) — Stop is the destructive
                    // primary action so it gets the filled treatment.
                    FilledTonalIconButton(
                        onClick = if (isPaused) onPlay else onPause,
                        enabled = casting && !isBuffering,
                    ) {
                        Icon(
                            if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (isPaused) "Play" else "Pause",
                        )
                    }
                    FilledIconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { url?.let { copyUrlToClipboard(ctx, it) } },
                        enabled = url != null,
                    ) { Text("Copy URL") }
                }
            }
        }
    }
}

private val CastForegroundService.Phase.isIdle: Boolean
    get() = this is CastForegroundService.Phase.Idle

@Composable
private fun StatusChip(
    casting: Boolean,
    starting: Boolean,
    errored: Boolean,
    playerState: String,
) {
    val (label, dotColor) = when {
        errored -> "Error" to MaterialTheme.colorScheme.error
        casting && playerState.equals("PAUSED", ignoreCase = true) ->
            "Paused" to MaterialTheme.colorScheme.tertiary
        casting && playerState.equals("BUFFERING", ignoreCase = true) ->
            "Buffering" to MaterialTheme.colorScheme.tertiary
        casting -> "Live" to MaterialTheme.colorScheme.primary
        starting -> "Connecting" to MaterialTheme.colorScheme.tertiary
        else -> "Idle" to MaterialTheme.colorScheme.outline
    }
    AssistChip(
        onClick = {},
        enabled = false,
        leadingIcon = { StatusDot(dotColor) },
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

// 5% per tap matches the default Chromecast step; the receiver clamps anyway
// via setVolumeMsg's coerceIn(0,1), so crossing the edges is safe.
private const val VOLUME_STEP = 0.05

@Composable
private fun VolumeCard(volume: CastVolume, onLevel: (Double) -> Unit, onMute: (Boolean) -> Unit) {
    val enabled = !volume.isFixed
    val levelPct = (volume.level * 100).toInt()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (volume.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalIconButton(
                    onClick = { onLevel(volume.level - VOLUME_STEP) },
                    enabled = enabled && !volume.muted && volume.level > 0.0,
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Volume down")
                }
                Text(
                    "$levelPct%",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { onLevel(volume.level + VOLUME_STEP) },
                    enabled = enabled && !volume.muted && volume.level < 1.0,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Volume up")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = volume.muted,
                    onCheckedChange = onMute,
                    enabled = enabled,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (volume.muted) "Muted" else "Mute",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (volume.isFixed) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Receiver volume is fixed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<CastDevice>, pickEnabled: Boolean, onPick: (CastDevice) -> Unit) {
    Text(
        if (pickEnabled) "Available Chromecasts" else "Stop to switch devices",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (devices.isEmpty()) {
        var timedOut by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(30_000); timedOut = true }
        Text(
            if (timedOut) "No devices found. Check Wi-Fi and that the Chromecast is powered on."
            else "Discovering on LAN…",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Card(Modifier.fillMaxWidth()) {
        LazyColumn {
            items(devices) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(d.host) },
                    leadingContent = {
                        Icon(
                            if (pickEnabled) Icons.Filled.Cast else Icons.Filled.CastConnected,
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                    modifier = if (pickEnabled) Modifier.clickable { onPick(d) } else Modifier,
                )
            }
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
