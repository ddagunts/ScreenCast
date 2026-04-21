package io.github.ddagunts.screencast.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.CastForegroundService.DeviceCast
import io.github.ddagunts.screencast.cast.CastDevice
import kotlinx.coroutines.delay

// Coarse matches the default Chromecast step; fine is one percentage point
// at a time. setVolumeMsg already coerceIns to [0,1], so crossing the edges
// is safe.
private const val VOLUME_STEP_COARSE = 0.05
private const val VOLUME_STEP_FINE = 0.01

@Composable
fun CastControlScreen(vm: CastViewModel) {
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val activeCasts by vm.activeCasts.collectAsStateWithLifecycle()
    val atCap = activeCasts.size >= CastForegroundService.MAX_DEVICES
    // Per-host Fine toggle. Purely UI state — nothing crosses the wire, the
    // only effect is the step size used when +/- is tapped. Not persisted:
    // one user may want fine on Living Room TV right now and coarse next
    // session, and re-enabling is a single tap.
    val fineByHost = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (activeCasts.isEmpty()) {
            IdleHeader()
        } else {
            ActiveHeader(count = activeCasts.size, onStopAll = { vm.stopAll() })
            for ((host, cast) in activeCasts) {
                DeviceCastCard(
                    cast = cast,
                    fineStep = fineByHost[host] == true,
                    onStop = { vm.stopCast(host) },
                    onPause = { vm.pause(host) },
                    onPlay = { vm.play(host) },
                    onLevel = { vm.setVolume(host, it) },
                    onMute = { m -> vm.setMute(host, m) },
                    onFineStepChange = { fineByHost[host] = it },
                )
            }
        }

        val available = discovered.filterNot { activeCasts.containsKey(it.host) }
        AvailableSection(
            available = available,
            atCap = atCap,
            onPick = { vm.startCast(it) },
        )
    }
}

@Composable
private fun IdleHeader() {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Not casting", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick a device below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveHeader(count: Int, onStopAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 1) "1 device casting" else "$count devices casting",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStopAll) { Text("Stop all") }
    }
}

@Composable
private fun DeviceCastCard(
    cast: DeviceCast,
    fineStep: Boolean,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onPlay: () -> Unit,
    onLevel: (Double) -> Unit,
    onMute: (Boolean) -> Unit,
    onFineStepChange: (Boolean) -> Unit,
) {
    val isCasting = cast.castState == "CASTING"
    val isPaused = cast.playerState.equals("PAUSED", ignoreCase = true)
    val isBuffering = cast.playerState.equals("BUFFERING", ignoreCase = true)
    val step = if (fineStep) VOLUME_STEP_FINE else VOLUME_STEP_COARSE
    val levelPct = (cast.volume.level * 100).toInt()
    val volEnabled = !cast.volume.isFixed

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cast.device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        cast.errorMessage ?: cast.device.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(castState = cast.castState, playerState = cast.playerState)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(
                    onClick = if (isPaused) onPlay else onPause,
                    enabled = isCasting && !isBuffering,
                ) {
                    Icon(
                        if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Play" else "Pause",
                    )
                }
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop this device")
                }
            }

            if (cast.castState != "ERROR") {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (cast.volume.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalIconButton(
                        onClick = { onLevel(cast.volume.level - step) },
                        enabled = volEnabled && !cast.volume.muted && cast.volume.level > 0.0,
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Volume down")
                    }
                    Text(
                        "$levelPct%",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(
                        onClick = { onLevel(cast.volume.level + step) },
                        enabled = volEnabled && !cast.volume.muted && cast.volume.level < 1.0,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Volume up")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = cast.volume.muted,
                        onCheckedChange = onMute,
                        enabled = volEnabled,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (cast.volume.muted) "Muted" else "Mute",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = fineStep,
                        onCheckedChange = onFineStepChange,
                        enabled = volEnabled,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Fine", style = MaterialTheme.typography.bodyMedium)
                }
                if (cast.volume.isFixed) {
                    Spacer(Modifier.height(4.dp))
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
private fun StatusChip(castState: String, playerState: String) {
    val (label, dotColor) = when (castState) {
        "ERROR" -> "Error" to MaterialTheme.colorScheme.error
        "CONNECTING" -> "Connecting" to MaterialTheme.colorScheme.tertiary
        "CASTING" -> when {
            playerState.equals("PAUSED", ignoreCase = true) ->
                "Paused" to MaterialTheme.colorScheme.tertiary
            playerState.equals("BUFFERING", ignoreCase = true) ->
                "Buffering" to MaterialTheme.colorScheme.tertiary
            else -> "Live" to MaterialTheme.colorScheme.primary
        }
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

@Composable
private fun AvailableSection(
    available: List<CastDevice>,
    atCap: Boolean,
    onPick: (CastDevice) -> Unit,
) {
    Text(
        when {
            atCap -> "Cap reached (${CastForegroundService.MAX_DEVICES}) — stop a device to add another"
            available.isEmpty() -> "Available Chromecasts"
            else -> "Available Chromecasts"
        },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (available.isEmpty()) {
        var timedOut by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(30_000); timedOut = true }
        Text(
            if (timedOut) "No more devices found. Check Wi-Fi and that the Chromecast is powered on."
            else "Discovering on LAN…",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Card(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightForDeviceList(available.size)) {
            items(available) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(d.host) },
                    leadingContent = {
                        Icon(
                            if (atCap) Icons.Filled.CastConnected else Icons.Filled.Cast,
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = if (atCap) Modifier else Modifier.clickable { onPick(d) },
                )
            }
        }
    }
}

// LazyColumn inside a verticalScroll parent needs a fixed height or it
// claims infinite space. Each ListItem is ~72 dp; give a sane max.
private fun Modifier.heightForDeviceList(count: Int): Modifier =
    this.then(Modifier.height((minOf(count, 6) * 72).dp))
