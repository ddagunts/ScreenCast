package io.github.ddagunts.screencast.media

import android.content.Context

class StreamConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): StreamConfig {
        val d = StreamConfig()
        return StreamConfig(
            segmentDurationSec = prefs.getFloat(KEY_SEGMENT, d.segmentDurationSec.toFloat()).toDouble(),
            windowSize = prefs.getInt(KEY_WINDOW, d.windowSize),
            liveEdgeFactor = prefs.getFloat(KEY_LIVE_EDGE, d.liveEdgeFactor.toFloat()).toDouble(),
            resolution = runCatching { Resolution.valueOf(prefs.getString(KEY_RESOLUTION, d.resolution.name) ?: "") }
                .getOrDefault(d.resolution),
            syncStart = prefs.getBoolean(KEY_SYNC_START, d.syncStart),
            syncIntervalSec = prefs.getInt(KEY_SYNC_INTERVAL, d.syncIntervalSec),
            syncDriftThresholdMs = prefs.getInt(KEY_SYNC_DRIFT_MS, d.syncDriftThresholdMs),
        )
    }

    fun save(cfg: StreamConfig) {
        // Also strips the legacy "fine_volume_step" key if it's present from a
        // prior install where fine was a global preference — no reason to
        // keep dead state in prefs.
        prefs.edit()
            .putFloat(KEY_SEGMENT, cfg.segmentDurationSec.toFloat())
            .putInt(KEY_WINDOW, cfg.windowSize)
            .putFloat(KEY_LIVE_EDGE, cfg.liveEdgeFactor.toFloat())
            .putString(KEY_RESOLUTION, cfg.resolution.name)
            .putBoolean(KEY_SYNC_START, cfg.syncStart)
            .putInt(KEY_SYNC_INTERVAL, cfg.syncIntervalSec)
            .putInt(KEY_SYNC_DRIFT_MS, cfg.syncDriftThresholdMs)
            .remove("fine_volume_step")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "stream_config"
        private const val KEY_SEGMENT = "segment_duration"
        private const val KEY_WINDOW = "window_size"
        private const val KEY_LIVE_EDGE = "live_edge_factor"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_SYNC_START = "sync_start"
        private const val KEY_SYNC_INTERVAL = "sync_interval_sec"
        private const val KEY_SYNC_DRIFT_MS = "sync_drift_ms"
    }
}
