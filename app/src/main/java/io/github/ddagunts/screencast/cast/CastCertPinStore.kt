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

    companion object {
        private const val PREFS_NAME = "cast_cert_pins"
    }
}
