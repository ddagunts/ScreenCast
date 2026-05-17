package io.github.ddagunts.screencast.cast

import android.annotation.SuppressLint
import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class CastChannel(
    private val host: String,
    private val port: Int,
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

    // Chromecasts present a self-signed device certificate that rotates on
    // every reboot, so we cannot pin it and we don't bundle the Cast root CA.
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
}
