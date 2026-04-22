package io.github.ddagunts.screencast.webrtc

import android.content.Context

// WebRTC-mode cast protocol constants. The signaling namespace is ours; it
// just has to match what the receiver HTML subscribes to via
// cast.framework.CastReceiverContext.addCustomMessageListener(). Keep the value
// in sync with receiver/receiver.js.
const val WEBRTC_NAMESPACE = "urn:x-cast:io.github.ddagunts.screencast.webrtc"

// Video parameters sent to the encoder and negotiated in SDP. 720p30 @ 3 Mbps
// is a safe default: Gen1/2 Chromecast dongles decode it comfortably, most home
// Wi-Fi has the headroom, and glass-to-glass latency stays under ~250 ms.
const val WEBRTC_VIDEO_WIDTH = 1280
const val WEBRTC_VIDEO_HEIGHT = 720
const val WEBRTC_VIDEO_FPS = 30

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
