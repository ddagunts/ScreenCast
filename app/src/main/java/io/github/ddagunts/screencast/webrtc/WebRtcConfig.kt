package io.github.ddagunts.screencast.webrtc

import android.content.Context

// WebRTC-mode cast protocol constants. The signaling namespace is ours; it
// just has to match what the receiver HTML subscribes to via
// cast.framework.CastReceiverContext.addCustomMessageListener(). Keep the value
// in sync with receiver/receiver.js.
const val WEBRTC_NAMESPACE = "urn:x-cast:io.github.ddagunts.screencast.webrtc"

// Video parameters sent to the encoder and negotiated in SDP. 1080p30 with an
// 8 Mbps ceiling looks sharp on a TV, fits well within a LAN's Wi-Fi budget,
// and Chromecast Gen 2+ / Ultra / Google TV all decode H.264 1080p in hardware.
// Gen 1 dongles cap at 720p — owners can drop these constants if they hit it.
const val WEBRTC_VIDEO_WIDTH = 1920
const val WEBRTC_VIDEO_HEIGHT = 1080
const val WEBRTC_VIDEO_FPS = 30

// libwebrtc's built-in bandwidth estimator is conservative without an explicit
// cap — it tends to settle in the 1–2 Mbps range regardless of available
// headroom. Setting an explicit ceiling on the RtpSender lets the encoder
// actually use the LAN. 8 Mbps is comfortable for 1080p30 H.264 and well
// under typical 2.4/5 GHz Wi-Fi capacity.
const val WEBRTC_MAX_BITRATE_BPS = 8_000_000

// Audio playback-capture parameters. 48 kHz stereo 16-bit is libwebrtc's
// native pipeline format, so samples flow through without resampling. Capture
// piggy-backs on the video MediaProjection — no extra consent prompt.
const val WEBRTC_AUDIO_SAMPLE_RATE = 48_000
const val WEBRTC_AUDIO_CHANNELS = 2

// App ID defaults to the project's published receiver. Users can override with
// their own registered App ID (cast.google.com) if they host their own receiver.
// An empty value blocks startCast. Persisted in SharedPreferences.
const val DEFAULT_WEBRTC_APP_ID = "9098830C"

class WebRtcConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var appId: String
        get() = prefs.getString(KEY_APP_ID, DEFAULT_WEBRTC_APP_ID)!!
        set(value) { prefs.edit().putString(KEY_APP_ID, value.trim()).apply() }

    companion object {
        private const val PREFS_NAME = "webrtc_config"
        private const val KEY_APP_ID = "app_id"
    }
}
