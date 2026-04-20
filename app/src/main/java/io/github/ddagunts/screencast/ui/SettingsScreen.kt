package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.media.Resolution
import io.github.ddagunts.screencast.media.StreamConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: CastViewModel) {
    val cfg by vm.streamConfig.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val live = phase !is CastForegroundService.Phase.Idle

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Stream Tuning", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        val resolutions = Resolution.entries
        val resIdx = resolutions.indexOf(cfg.resolution)
        Text("Resolution: ${cfg.resolution.label} (${cfg.resolution.width}×${cfg.resolution.height}, ${cfg.resolution.bitrate / 1_000_000} Mbps)")
        Slider(
            value = resIdx.toFloat(),
            onValueChange = { vm.setResolution(resolutions[it.roundToInt().coerceIn(0, resolutions.lastIndex)]) },
            valueRange = 0f..resolutions.lastIndex.toFloat(),
            steps = (resolutions.size - 2).coerceAtLeast(0),
            enabled = !live,
        )
        Spacer(Modifier.height(8.dp))

        Text("Segment duration: ${"%.1f".format(cfg.segmentDurationSec)} s")
        Slider(
            value = cfg.segmentDurationSec.toFloat(),
            onValueChange = { vm.setSegmentDuration(((it * 2).roundToInt() / 2.0).coerceIn(StreamConfig.MIN_SEGMENT_SEC, StreamConfig.MAX_SEGMENT_SEC)) },
            valueRange = StreamConfig.MIN_SEGMENT_SEC.toFloat()..StreamConfig.MAX_SEGMENT_SEC.toFloat(),
            steps = 5,
            enabled = !live,
        )
        Spacer(Modifier.height(8.dp))

        Text("Playlist window: ${cfg.windowSize} segments")
        Slider(
            value = cfg.windowSize.toFloat(),
            onValueChange = { vm.setWindowSize(it.roundToInt().coerceIn(StreamConfig.MIN_WINDOW, StreamConfig.MAX_WINDOW)) },
            valueRange = StreamConfig.MIN_WINDOW.toFloat()..StreamConfig.MAX_WINDOW.toFloat(),
            steps = StreamConfig.MAX_WINDOW - StreamConfig.MIN_WINDOW - 1,
            enabled = !live,
        )
        Spacer(Modifier.height(8.dp))

        Text("Live-edge target: ${"%.1f".format(cfg.liveEdgeFactor)}× segments from end")
        Slider(
            value = cfg.liveEdgeFactor.toFloat(),
            onValueChange = { vm.setLiveEdgeFactor(((it * 2).roundToInt() / 2.0).coerceIn(StreamConfig.MIN_LIVE_EDGE, StreamConfig.MAX_LIVE_EDGE)) },
            valueRange = StreamConfig.MIN_LIVE_EDGE.toFloat()..StreamConfig.MAX_LIVE_EDGE.toFloat(),
            steps = 3,
            enabled = !live,
        )
        Spacer(Modifier.height(12.dp))

        val startupSec = cfg.segmentDurationSec * cfg.seedSegmentCount
        Text("Startup buffering: ~${"%.1f".format(startupSec)} s (until first frame)")
        Text("Live-edge lag: ~${"%.1f".format(cfg.estimatedLatencySec)} s (player position vs. live)")
        Text("Keyframe interval: ${cfg.keyframeIntervalSec} s")
        Spacer(Modifier.height(8.dp))
        Text(
            if (live) "Stop the current cast to change settings."
            else "Lower live-edge target = less delay, but more likely to stall on jitter. Try 1.0× if your Wi-Fi is rock solid.",
        )
    }
}
