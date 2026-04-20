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
        )
    }

    fun save(cfg: StreamConfig) {
        prefs.edit()
            .putFloat(KEY_SEGMENT, cfg.segmentDurationSec.toFloat())
            .putInt(KEY_WINDOW, cfg.windowSize)
            .putFloat(KEY_LIVE_EDGE, cfg.liveEdgeFactor.toFloat())
            .putString(KEY_RESOLUTION, cfg.resolution.name)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "stream_config"
        private const val KEY_SEGMENT = "segment_duration"
        private const val KEY_WINDOW = "window_size"
        private const val KEY_LIVE_EDGE = "live_edge_factor"
        private const val KEY_RESOLUTION = "resolution"
    }
}
