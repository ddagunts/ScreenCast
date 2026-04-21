package io.github.ddagunts.screencast.media

import kotlin.math.ceil

enum class Resolution(val label: String, val width: Int, val height: Int, val bitrate: Int) {
    P480("480p", 854, 480, 1_500_000),
    P720("720p", 1280, 720, 3_000_000),
    P1080("1080p", 1920, 1080, 6_000_000),
}

data class StreamConfig(
    val segmentDurationSec: Double = 2.0,
    val windowSize: Int = 6,
    val liveEdgeFactor: Double = 1.5,
    val resolution: Resolution = Resolution.P720,
    val syncStart: Boolean = false,
    val syncIntervalSec: Int = 15,
    val syncDriftThresholdMs: Int = 15,
) {
    val keyframeIntervalSec: Int get() = ceil(segmentDurationSec).toInt().coerceAtLeast(1)
    val seedSegmentCount: Int get() = minOf(3, windowSize)
    val estimatedLatencySec: Double get() = segmentDurationSec * liveEdgeFactor

    companion object {
        const val MIN_SEGMENT_SEC = 1.0
        const val MAX_SEGMENT_SEC = 4.0
        const val MIN_WINDOW = 3
        const val MAX_WINDOW = 10
        const val MIN_LIVE_EDGE = 1.0
        const val MAX_LIVE_EDGE = 3.0
        const val MIN_SYNC_INTERVAL_SEC = 5
        const val MAX_SYNC_INTERVAL_SEC = 60
        const val MIN_SYNC_DRIFT_MS = 5
        const val MAX_SYNC_DRIFT_MS = 500
    }
}
