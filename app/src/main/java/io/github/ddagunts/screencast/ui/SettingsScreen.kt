package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.media.Resolution
import io.github.ddagunts.screencast.media.StreamConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: CastViewModel) {
    val cfg by vm.streamConfig.collectAsStateWithLifecycle()
    val activeCasts by vm.activeCasts.collectAsStateWithLifecycle()
    // Stream config is shared across every active session, so we can only
    // edit it when *no* casts are live. A change mid-cast would invalidate
    // the running encoder's segmenter settings for all receivers at once.
    val live = activeCasts.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingCard("Resolution") {
            val resolutions = Resolution.entries
            val resIdx = resolutions.indexOf(cfg.resolution)
            Text(
                "${cfg.resolution.label} · ${cfg.resolution.width}×${cfg.resolution.height} · ${cfg.resolution.bitrate / 1_000_000} Mbps",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = resIdx.toFloat(),
                onValueChange = { vm.setResolution(resolutions[it.roundToInt().coerceIn(0, resolutions.lastIndex)]) },
                valueRange = 0f..resolutions.lastIndex.toFloat(),
                steps = (resolutions.size - 2).coerceAtLeast(0),
                enabled = !live,
            )
        }

        SettingCard("Segment duration") {
            Text(
                "${"%.1f".format(cfg.segmentDurationSec)} s",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = cfg.segmentDurationSec.toFloat(),
                onValueChange = { vm.setSegmentDuration(((it * 2).roundToInt() / 2.0).coerceIn(StreamConfig.MIN_SEGMENT_SEC, StreamConfig.MAX_SEGMENT_SEC)) },
                valueRange = StreamConfig.MIN_SEGMENT_SEC.toFloat()..StreamConfig.MAX_SEGMENT_SEC.toFloat(),
                steps = 5,
                enabled = !live,
            )
        }

        SettingCard("Playlist window") {
            Text(
                "${cfg.windowSize} segments",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = cfg.windowSize.toFloat(),
                onValueChange = { vm.setWindowSize(it.roundToInt().coerceIn(StreamConfig.MIN_WINDOW, StreamConfig.MAX_WINDOW)) },
                valueRange = StreamConfig.MIN_WINDOW.toFloat()..StreamConfig.MAX_WINDOW.toFloat(),
                steps = StreamConfig.MAX_WINDOW - StreamConfig.MIN_WINDOW - 1,
                enabled = !live,
            )
        }

        SettingCard("Live-edge target") {
            Text(
                "${"%.1f".format(cfg.liveEdgeFactor)}× segments from end",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = cfg.liveEdgeFactor.toFloat(),
                onValueChange = { vm.setLiveEdgeFactor(((it * 2).roundToInt() / 2.0).coerceIn(StreamConfig.MIN_LIVE_EDGE, StreamConfig.MAX_LIVE_EDGE)) },
                valueRange = StreamConfig.MIN_LIVE_EDGE.toFloat()..StreamConfig.MAX_LIVE_EDGE.toFloat(),
                steps = 3,
                enabled = !live,
            )
        }

        SettingCard("Sync start") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Safe to toggle mid-cast — applies to the *next* device added.
                // Live devices aren't rebalanced retroactively.
                Switch(
                    checked = cfg.syncStart,
                    onCheckedChange = { vm.setSyncStart(it) },
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (cfg.syncStart) "On" else "Off",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Pause running casts while a new one loads, then start them together. Trims the worst of the startup skew.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Sync interval/drift are live-editable — they affect the already-
        // running maintenance loop, so leaving these enabled during a cast
        // is intentional.
        SettingCard("Sync check interval") {
            Text(
                "${cfg.syncIntervalSec} s",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "How often to check receivers for drift.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = cfg.syncIntervalSec.toFloat(),
                onValueChange = { vm.setSyncIntervalSec(it.roundToInt().coerceIn(StreamConfig.MIN_SYNC_INTERVAL_SEC, StreamConfig.MAX_SYNC_INTERVAL_SEC)) },
                valueRange = StreamConfig.MIN_SYNC_INTERVAL_SEC.toFloat()..StreamConfig.MAX_SYNC_INTERVAL_SEC.toFloat(),
            )
        }

        SettingCard("Sync drift threshold") {
            Text(
                "${cfg.syncDriftThresholdMs} ms",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Re-align receivers when any drifts above this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = cfg.syncDriftThresholdMs.toFloat(),
                onValueChange = { vm.setSyncDriftMs(it.roundToInt().coerceIn(StreamConfig.MIN_SYNC_DRIFT_MS, StreamConfig.MAX_SYNC_DRIFT_MS)) },
                valueRange = StreamConfig.MIN_SYNC_DRIFT_MS.toFloat()..StreamConfig.MAX_SYNC_DRIFT_MS.toFloat(),
            )
        }

        SettingCard("Stream summary") {
            val startupSec = cfg.segmentDurationSec * cfg.seedSegmentCount
            Text("Startup buffering: ~${"%.1f".format(startupSec)} s", style = MaterialTheme.typography.bodyMedium)
            Text("Live-edge lag: ~${"%.1f".format(cfg.estimatedLatencySec)} s", style = MaterialTheme.typography.bodyMedium)
            Text("Keyframe interval: ${cfg.keyframeIntervalSec} s", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (live) "Stop the current cast to change settings."
                else "Lower live-edge target = less delay, but more likely to stall on jitter. Try 1.0× if your Wi-Fi is rock solid.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
