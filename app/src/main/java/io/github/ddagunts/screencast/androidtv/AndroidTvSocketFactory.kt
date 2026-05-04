package io.github.ddagunts.screencast.androidtv

import android.annotation.SuppressLint
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

// Builds the SSLSocketFactory used for both ATV ports:
//
//   * Port 6467 (pairing) — client cert presented during the handshake so
//     the TV records us in its paired-devices list. No server-cert pin yet
//     (we discover it during the handshake and store it on success).
//
//   * Port 6466 (remote control) — same client cert + a strict server-cert
//     pin against the SHA-256 stored at pairing time. If the TV's cert
//     changes, we fail closed and the user must re-pair.
//
// The TLS handshake itself accepts any server cert (Android's CA bundle
// doesn't include the TV's self-signed root); authentication is done by
// our pin check after the handshake. The trust-all manager is scoped to
// this one SSLContext — never set as a JVM default — matching the
// posture cast/CastChannel.kt already uses.
class AndroidTvSocketFactory(
    private val clientMaterial: AndroidTvCertFactory.Material,
    // null on the pairing path (we'll record the cert), a SHA-256 hex
    // fingerprint on the remote-control path (we'll match against it).
    private val expectedServerCertSha256: String? = null,
) {

    fun connect(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): SSLSocket {
        val factory = buildFactory()
        val socket = factory.createSocket() as SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        socket.startHandshake()
        verifyServerCert(socket, host)
        logI("ATV TLS connected to $host:$port")
        return socket
    }

    // After the handshake, look at the leaf cert the TV presented and
    // either pin (if we don't have an expected) or compare (if we do).
    // The "pin on first use" mode is only for the pairing port — the
    // pairing channel records the resulting fingerprint into the cert
    // store on a successful SECRET_ACK exchange.
    fun verifyServerCert(socket: SSLSocket, host: String): String {
        val peer = try { socket.session.peerCertificates } catch (e: SSLPeerUnverifiedException) {
            socket.close(); throw SecurityException("peer did not present a certificate", e)
        }
        val leaf = peer.firstOrNull() as? X509Certificate
            ?: run { socket.close(); throw SecurityException("no X.509 leaf certificate") }
        val fp = sha256Hex(leaf.encoded)
        val expected = expectedServerCertSha256
        if (expected == null) {
            // First-contact / pairing path — caller persists `fp` on success.
            logI("ATV server cert observed for $host: $fp")
        } else if (!expected.equals(fp, ignoreCase = true)) {
            socket.close()
            throw SecurityException(
                "Android TV cert changed for $host (expected $expected, got $fp). " +
                "If you reset the TV, re-pair it from the Settings screen."
            )
        }
        return fp
    }

    private fun buildFactory(): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(buildKeyManager()), arrayOf(trustAll()), SecureRandom())
        return ctx.socketFactory
    }

    // SunX509 vs PKIX: we don't need PKIX path-building; we have one cert
    // chain (length 1, our self-signed leaf) and an alias is irrelevant.
    // Implementing X509ExtendedKeyManager directly avoids loading the cert
    // into a transient KeyStore.
    private fun buildKeyManager(): KeyManager {
        val cert = clientMaterial.cert
        val key = clientMaterial.keyPair.private
        return ScreenCastKeyManager(cert, key)
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(d.size * 2) {
            for (b in d) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4]); append(HEX[v and 0x0F])
            }
        }
    }

    // Extending X509ExtendedKeyManager (vs the simpler X509KeyManager)
    // avoids a deprecation noise from the platform on Android 14+ where
    // the SSLEngine path expects the extended variant.
    private class ScreenCastKeyManager(
        private val cert: X509Certificate,
        private val key: PrivateKey,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String> = arrayOf(ALIAS)
        override fun chooseClientAlias(keyTypes: Array<String>, issuers: Array<Principal>?, socket: Socket?): String = ALIAS
        override fun chooseEngineClientAlias(keyTypes: Array<String>, issuers: Array<Principal>?, engine: javax.net.ssl.SSLEngine?): String = ALIAS
        override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String> = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String, issuers: Array<Principal>?, socket: Socket?): String = ALIAS
        override fun getCertificateChain(alias: String): Array<X509Certificate> = arrayOf(cert)
        override fun getPrivateKey(alias: String): PrivateKey = key

        companion object {
            private const val ALIAS = "screencast-atv"
        }
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
