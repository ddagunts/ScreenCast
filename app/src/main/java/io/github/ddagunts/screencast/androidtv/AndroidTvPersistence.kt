package io.github.ddagunts.screencast.androidtv

import android.content.Context

// Paired-device metadata: friendly name, model, last-seen BLE MAC, and
// last-known host. Cert + server-cert pin live in AndroidTvCertStore
// (EncryptedFile + the plain-pin SharedPreferences). This file is plain
// SharedPreferences because none of it is sensitive — leaking it to a
// rooted attacker tells them you have a TV named "Living Room", which
// they can also discover by listening for `_androidtvremote2._tcp` for
// 200 ms.
//
// Why SharedPreferences and not DataStore-Preferences: keeping the
// dependency graph thin. We already use SharedPreferences for the cert
// pin store; the paired-device metadata read pattern (one read on UI
// open, occasional writes on pair / connect) doesn't justify pulling
// the DataStore coroutine machinery into this flow.
class AndroidTvPersistence(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val key: String,           // bleMac if available, else friendly name
        val name: String,
        val host: String,
        val model: String?,
        val bleMac: String?,
        val lastSeenMs: Long,
    )

    fun upsert(device: AndroidTvDevice, lastSeenMs: Long = System.currentTimeMillis()) {
        val key = stableKey(device)
        prefs.edit()
            .putString("$key.$KEY_NAME", device.name)
            .putString("$key.$KEY_HOST", device.host)
            .putString("$key.$KEY_MODEL", device.modelName ?: "")
            .putString("$key.$KEY_MAC", device.bleMac ?: "")
            .putLong("$key.$KEY_LAST_SEEN", lastSeenMs)
            .putString(KEY_INDEX_PREFIX + key, key)
            .apply()
    }

    fun forget(device: AndroidTvDevice) = forget(stableKey(device))

    fun forget(key: String) {
        val ed = prefs.edit()
        listOf(KEY_NAME, KEY_HOST, KEY_MODEL, KEY_MAC, KEY_LAST_SEEN).forEach { sub ->
            ed.remove("$key.$sub")
        }
        ed.remove(KEY_INDEX_PREFIX + key)
        ed.apply()
    }

    fun list(): List<Entry> = prefs.all.keys
        .filter { it.startsWith(KEY_INDEX_PREFIX) }
        .map { it.removePrefix(KEY_INDEX_PREFIX) }
        .mapNotNull { key ->
            val name = prefs.getString("$key.$KEY_NAME", null) ?: return@mapNotNull null
            val host = prefs.getString("$key.$KEY_HOST", "") ?: ""
            val model = prefs.getString("$key.$KEY_MODEL", "")?.takeIf { it.isNotEmpty() }
            val mac = prefs.getString("$key.$KEY_MAC", "")?.takeIf { it.isNotEmpty() }
            val seen = prefs.getLong("$key.$KEY_LAST_SEEN", 0L)
            Entry(key, name, host, model, mac, seen)
        }
        .sortedByDescending { it.lastSeenMs }

    // Prefer BLE MAC when available — IPs change across DHCP renewals,
    // friendly names can be the user-set TV name and aren't guaranteed
    // unique across two TVs of the same model.
    private fun stableKey(device: AndroidTvDevice): String =
        device.bleMac?.takeIf { it.isNotBlank() } ?: device.name

    companion object {
        private const val PREFS_NAME = "atv_paired_devices"
        private const val KEY_INDEX_PREFIX = "_idx."
        private const val KEY_NAME = "name"
        private const val KEY_HOST = "host"
        private const val KEY_MODEL = "model"
        private const val KEY_MAC = "mac"
        private const val KEY_LAST_SEEN = "lastSeen"
    }
}
