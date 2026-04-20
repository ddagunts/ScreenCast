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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.CastForegroundService
import kotlinx.coroutines.delay

@Composable
fun CastControlScreen(vm: CastViewModel) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val idle = phase is CastForegroundService.Phase.Idle

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        StatusBlock(phase, onStop = { vm.stopCast() })
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
private fun StatusBlock(phase: CastForegroundService.Phase, onStop: () -> Unit) {
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
    val ctx = LocalContext.current
    Text(title, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(detail)
    Spacer(Modifier.height(8.dp))
    Row {
        Button(
            onClick = onStop,
            enabled = phase !is CastForegroundService.Phase.Idle,
        ) { Text("Stop") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { url?.let { copyUrlToClipboard(ctx, it) } },
            enabled = url != null,
        ) { Text("Copy URL") }
    }
}

private fun copyUrlToClipboard(ctx: Context, url: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ScreenCast stream URL", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
        Toast.makeText(ctx, "Stream URL copied", Toast.LENGTH_SHORT).show()
    }
}
