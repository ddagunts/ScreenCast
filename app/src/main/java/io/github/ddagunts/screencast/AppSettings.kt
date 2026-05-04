package io.github.ddagunts.screencast

import android.content.Context

// App-wide mode selector. The main screen has a segmented control at the
// top with one device list visible at a time, governed by this enum:
//   * HLS — Ktor stream server + the Default Media Receiver. Casts the
//     phone screen to a Chromecast.
//   * WEBRTC — custom receiver + RTCPeerConnection. Same target device,
//     low-latency path.
//   * REMOTE — Android TV / Google TV remote control over the polo
//     pairing + remote-v2 protocols. Different target devices than HLS
//     and WEBRTC, but the user picks "what am I trying to do" via this
//     same segmented control rather than juggling navigation.
// Persisted so the app opens in whichever mode was last used.
enum class CastMode {
    HLS,
    WEBRTC,
    REMOTE;

    companion object {
        val DEFAULT = HLS
        fun fromName(name: String?): CastMode =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var castMode: CastMode
        get() = CastMode.fromName(prefs.getString(KEY_CAST_MODE, null))
        set(value) { prefs.edit().putString(KEY_CAST_MODE, value.name).apply() }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_CAST_MODE = "cast_mode"
    }
}
