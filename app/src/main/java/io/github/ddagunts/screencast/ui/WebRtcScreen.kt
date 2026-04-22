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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.cast.CastDevice
import kotlinx.coroutines.delay

@Composable
fun WebRtcScreen(vm: WebRtcViewModel) {
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val appId by vm.appId.collectAsStateWithLifecycle()
    val casting = session != null
    val appIdMissing = appId.isBlank()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIdCard(appId = appId, onChange = { vm.setAppId(it) }, enabled = !casting)

        // WebRTC mode is single-device — if a session exists, show it; otherwise
        // show the picker gated by a non-empty App ID.
        val snap = session
        if (snap != null) {
            ActiveWebRtcCard(snap = snap, onStop = { vm.stop() })
        } else {
            IdleWebRtcHeader(appIdMissing)
        }

        val available = discovered.filterNot { d ->
            session?.device?.host == d.host
        }
        AvailableWebRtcSection(
            available = available,
            disabled = casting || appIdMissing,
            onPick = { vm.startCast(it) },
        )

        FooterHint(appIdMissing)
    }
}

@Composable
private fun AppIdCard(appId: String, onChange: (String) -> Unit, enabled: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Custom receiver App ID", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Defaults to the project's hosted receiver. Override only if you've registered " +
                    "your own receiver URL at cast.google.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = appId,
                onValueChange = onChange,
                label = { Text("App ID") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IdleWebRtcHeader(appIdMissing: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("WebRTC mode", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (appIdMissing) "Set a custom receiver App ID above to enable the picker."
                else "Low-latency screen mirroring via WebRTC. Sub-second latency on typical Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveWebRtcCard(
    snap: io.github.ddagunts.screencast.WebRtcForegroundService.SessionSnapshot,
    onStop: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(snap.device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        snap.errorMessage ?: snap.device.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WebRtcStatusChip(status = snap.status)
            }
            Spacer(Modifier.height(12.dp))
            FilledIconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop WebRTC cast")
            }
        }
    }
}

@Composable
private fun WebRtcStatusChip(status: String) {
    val (label, color) = when (status) {
        "ERROR" -> "Error" to MaterialTheme.colorScheme.error
        "CONNECTING" -> "Connecting" to MaterialTheme.colorScheme.tertiary
        "LAUNCHING" -> "Launching" to MaterialTheme.colorScheme.tertiary
        "SIGNALING" -> "Signaling" to MaterialTheme.colorScheme.tertiary
        "CASTING" -> "Live" to MaterialTheme.colorScheme.primary
        else -> "Idle" to MaterialTheme.colorScheme.outline
    }
    AssistChip(
        onClick = {},
        enabled = false,
        leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun AvailableWebRtcSection(
    available: List<CastDevice>,
    disabled: Boolean,
    onPick: (CastDevice) -> Unit,
) {
    Text(
        "Available Chromecasts",
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
        LazyColumn(Modifier.height((minOf(available.size, 6) * 72).dp)) {
            items(available) { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(d.host) },
                    leadingContent = {
                        Icon(
                            if (disabled) Icons.Filled.CastConnected else Icons.Filled.Cast,
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = if (disabled) Modifier else Modifier.clickable { onPick(d) },
                )
            }
        }
    }
}

@Composable
private fun FooterHint(appIdMissing: Boolean) {
    val msg = if (appIdMissing) {
        "WebRTC mode needs a custom Cast receiver App ID. The app ships with a default; " +
            "if you've cleared it, restore it or register your own at cast.google.com."
    } else {
        "Each cast re-prompts for screen capture consent (Android requirement). " +
            "Only one WebRTC session at a time."
    }
    Text(
        msg,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
