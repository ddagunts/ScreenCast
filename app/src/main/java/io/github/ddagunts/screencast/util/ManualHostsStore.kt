package io.github.ddagunts.screencast.util

import android.content.Context

// Persists user-entered IPs/hostnames so they survive app restarts and the
// next mDNS scan doesn't have to re-find them. One category per file ("cast"
// for Chromecast targets shared by HLS+WebRTC, "atv" for Android TV remote
// targets) — separating keeps a typo in one mode from polluting the other.
//
// No validation: the input field accepts whatever the user types. If the
// string isn't reachable, the connect attempt fails the same way any other
// unreachable device would, and the entry is still in the list so they can
// fix the typo or remove it.
class ManualHostsStore(context: Context, category: String) {
    private val prefs = context.getSharedPreferences("manual_hosts_$category", Context.MODE_PRIVATE)

    fun list(): Set<String> =
        prefs.getStringSet(KEY_HOSTS, emptySet())?.toSet() ?: emptySet()

    fun add(host: String): Set<String> {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return list()
        val updated = list() + trimmed
        prefs.edit().putStringSet(KEY_HOSTS, updated).apply()
        return updated
    }

    fun remove(host: String): Set<String> {
        val updated = list() - host
        prefs.edit().putStringSet(KEY_HOSTS, updated).apply()
        return updated
    }

    companion object {
        private const val KEY_HOSTS = "hosts"
    }
}
