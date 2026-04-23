package io.github.ddagunts.screencast.webrtc

import android.content.Context

// WebRTC-mode cast protocol constants. The signaling namespace is ours; it
// just has to match what the receiver HTML subscribes to via
// cast.framework.CastReceiverContext.addCustomMessageListener(). Keep the value
// in sync with receiver/receiver.js.
const val WEBRTC_NAMESPACE = "urn:x-cast:io.github.ddagunts.screencast.webrtc"

// Audio playback-capture parameters. 48 kHz stereo 16-bit is libwebrtc's
// native pipeline format, so samples flow through without resampling. Not
// user-facing — changing them requires a matching change in the ADM
// configuration; the knobs users care about (res, fps, bitrate, audio on/off)
// are in WebRtcSessionConfig.
const val WEBRTC_AUDIO_SAMPLE_RATE = 48_000
const val WEBRTC_AUDIO_CHANNELS = 2

// User-selectable capture presets. Width/height/fps are applied to the
// ScreenCapturerAndroid.startCapture call. Each preset is a sensible combo
// that most Chromecasts decode fine; exotic resolutions (1440p/4K) are
// omitted because Gen1–3 dongles can't decode them and the debug value is
// mostly in A/B-testing sane profiles, not torture-testing the receiver.
enum class VideoPreset(val label: String, val width: Int, val height: Int, val fps: Int) {
    HD_30("720p30", 1280, 720, 30),
    FHD_30("1080p30", 1920, 1080, 30),
    FHD_60("1080p60", 1920, 1080, 60);

    companion object {
        val DEFAULT = FHD_60
        fun fromName(name: String?): VideoPreset =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

// Per-cast snapshot of the user-tunable knobs. Read once at startCast time and
// held for the session's lifetime — changing store values mid-cast has no
// effect on the running peer (the UI disables controls while casting).
data class WebRtcSessionConfig(
    val videoPreset: VideoPreset,
    val maxBitrateBps: Int,
    val audioEnabled: Boolean,
)

// App ID defaults to the project's published receiver. Users can override with
// their own registered App ID (cast.google.com) if they host their own receiver.
// An empty value blocks startCast. Persisted in SharedPreferences.
const val DEFAULT_WEBRTC_APP_ID = "9098830C"

// Bitrate presets in Mbps — what the UI offers. Kept together so the settings
// screen and the store's default-resolution logic stay consistent.
val WEBRTC_BITRATE_MBPS_OPTIONS = listOf(1, 2, 4, 6, 8, 12)
const val DEFAULT_WEBRTC_BITRATE_MBPS = 12

class WebRtcConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var appId: String
        get() = prefs.getString(KEY_APP_ID, DEFAULT_WEBRTC_APP_ID)!!
        set(value) { prefs.edit().putString(KEY_APP_ID, value.trim()).apply() }

    var videoPreset: VideoPreset
        get() = VideoPreset.fromName(prefs.getString(KEY_VIDEO_PRESET, null))
        set(value) { prefs.edit().putString(KEY_VIDEO_PRESET, value.name).apply() }

    var maxBitrateMbps: Int
        get() = prefs.getInt(KEY_MAX_BITRATE_MBPS, DEFAULT_WEBRTC_BITRATE_MBPS)
            .takeIf { it in WEBRTC_BITRATE_MBPS_OPTIONS }
            ?: DEFAULT_WEBRTC_BITRATE_MBPS
        set(value) {
            val coerced = value.takeIf { it in WEBRTC_BITRATE_MBPS_OPTIONS }
                ?: DEFAULT_WEBRTC_BITRATE_MBPS
            prefs.edit().putInt(KEY_MAX_BITRATE_MBPS, coerced).apply()
        }

    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_AUDIO_ENABLED, value).apply() }

    // Snapshot for handoff into a cast session. Each call reads current prefs
    // so the next cast picks up any changes made after the previous one ended.
    fun snapshot(): WebRtcSessionConfig = WebRtcSessionConfig(
        videoPreset = videoPreset,
        maxBitrateBps = maxBitrateMbps * 1_000_000,
        audioEnabled = audioEnabled,
    )

    companion object {
        private const val PREFS_NAME = "webrtc_config"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_VIDEO_PRESET = "video_preset"
        private const val KEY_MAX_BITRATE_MBPS = "max_bitrate_mbps"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
    }
}
