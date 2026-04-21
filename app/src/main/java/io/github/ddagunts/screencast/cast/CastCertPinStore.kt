package io.github.ddagunts.screencast.cast

import android.content.Context

// Trust-on-first-use fingerprint store for Chromecast device certs. Chromecasts
// present a self-signed leaf signed by Google's Cast root CA; we don't bundle the
// CA, so first contact is unauthenticated. On every subsequent connection to the
// same host the SHA-256 of the leaf must match what we saw the first time —
// defeats LAN-local MITMs (ARP/mDNS spoofing) after initial pairing.
class CastCertPinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(host: String): String? = prefs.getString(host, null)

    fun pin(host: String, fingerprintHex: String) {
        prefs.edit().putString(host, fingerprintHex).apply()
    }

    // Every key in this prefs bundle is a pinned host. Keys is a defensive
    // copy from SharedPreferences, safe to read without locking.
    fun pinnedHosts(): Set<String> = prefs.all.keys.toSet()

    // Clear the TOFU fingerprint so the next successful handshake to this
    // host re-pins whatever cert it presents. Use after a legitimate swap
    // (replaced device, factory reset) — mismatches during an adversarial
    // swap are the reason the pin exists in the first place.
    fun forget(host: String) {
        prefs.edit().remove(host).apply()
    }

    companion object {
        private const val PREFS_NAME = "cast_cert_pins"
    }
}
