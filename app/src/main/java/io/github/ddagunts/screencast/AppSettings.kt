package io.github.ddagunts.screencast

import android.content.Context

// App-wide mode selector. The main Cast screen shows one device picker at a
// time; this decides whether it drives the HLS pipeline (Ktor stream server +
// the Default Media Receiver) or the WebRTC pipeline (custom receiver +
// RTCPeerConnection). Persisted so the app opens in whichever mode was last
// used.
enum class CastMode {
    HLS,
    WEBRTC;

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
