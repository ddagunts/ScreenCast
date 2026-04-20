package io.github.ddagunts.screencast.cast

import android.annotation.SuppressLint
import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class CastChannel(
    private val host: String,
    private val port: Int,
    private val pinStore: CastCertPinStore? = null,
) {
    private var socket: SSLSocket? = null
    private var out: DataOutputStream? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Mutex()

    private val _incoming = MutableSharedFlow<CastMessage>(extraBufferCapacity = 256)
    val incoming = _incoming.asSharedFlow()

    fun connect(onClose: (Throwable?) -> Unit): Job {
        val factory = trustAllFactory()
        val s = factory.createSocket(host, port) as SSLSocket
        s.startHandshake()
        verifyPin(s)
        socket = s
        out = DataOutputStream(s.outputStream)
        val scope = CoroutineScope(Dispatchers.IO)
        this.scope = scope
        logI("TLS connected to $host:$port")
        return scope.launch {
            val input = DataInputStream(s.inputStream)
            runCatching {
                while (true) {
                    val len = input.readInt()
                    if (len <= 0 || len > 65536) error("bad frame length $len")
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    val msg = CastMessage.decode(buf)
                    logD("<< ${msg.namespace} ${msg.payloadUtf8.take(500)}")
                    _incoming.emit(msg)
                }
            }.onFailure { e ->
                logE("read loop ended", e)
                onClose(e)
            }
        }
    }

    suspend fun send(msg: CastMessage) {
        val data = msg.encode()
        writeLock.withLock {
            val o = out ?: error("not connected")
            o.writeInt(data.size)
            o.write(data)
            o.flush()
        }
        logD(">> ${msg.namespace} ${msg.payloadUtf8.take(500)}")
    }

    fun close() {
        scope?.cancel()
        runCatching { socket?.close() }
        socket = null; out = null
    }

    // TOFU pin check against the store. On a mismatch we throw and the caller
    // surfaces CastState.Error; the user is expected to clear the pin (delete
    // app data / reinstall) only if they've actually swapped their Chromecast.
    private fun verifyPin(s: SSLSocket) {
        val store = pinStore ?: run {
            logW("no pin store wired — skipping cert pin verification")
            return
        }
        val peer = try { s.session.peerCertificates } catch (e: SSLPeerUnverifiedException) {
            s.close(); throw SecurityException("peer did not present a certificate", e)
        }
        val leaf = peer.firstOrNull() as? X509Certificate
            ?: run { s.close(); throw SecurityException("no X.509 leaf certificate") }
        val fp = sha256Hex(leaf.encoded)
        val known = store.get(host)
        if (known == null) {
            store.pin(host, fp)
            logI("TOFU: pinned $host to SHA-256 $fp")
        } else if (known != fp) {
            s.close()
            logE("cert pin mismatch for $host (expected $known, got $fp)")
            throw SecurityException("Chromecast certificate changed — possible MITM. " +
                "If you replaced the device, clear app data to re-pin.")
        }
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

    // Chromecasts present a self-signed device certificate whose root isn't in
    // the Android trust store, so the handshake itself accepts anything — real
    // authentication is done by verifyPin() against a TOFU fingerprint store.
    // The trust-all manager is only installed on this one SSLContext and handed
    // to the Cast socket; it is not set as the JVM default.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustAllFactory(): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return ctx.socketFactory
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
