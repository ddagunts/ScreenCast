package io.github.ddagunts.screencast.androidtv

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
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLSocket

// mTLS connection to port 6466 (the Android TV remote control channel),
// post-pairing. Mirror of cast/CastChannel: a single TLS socket, a write
// mutex, a SharedFlow of incoming RemoteMessage envelopes, and a read
// loop that surfaces decode failures by closing the channel.
//
// Auto-responds to RemotePingRequest with RemotePingResponse from inside
// the read loop — those would otherwise need to be plumbed through every
// observer and we'd race against the TV's ~10 s no-pong-disconnect timer.
// Other incoming messages (volume, error, app-start) flow out via
// `incoming` for higher-level state to consume.
class AndroidTvRemoteChannel(
    private val host: String,
    private val port: Int = 6466,
    private val clientMaterial: AndroidTvCertFactory.Material,
    private val expectedServerCertSha256: String,
) {
    private var socket: SSLSocket? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Mutex()

    private val _incoming = MutableSharedFlow<RemoteMessage>(extraBufferCapacity = 64)
    val incoming = _incoming.asSharedFlow()

    fun connect(onClose: (Throwable?) -> Unit): Job {
        val factory = AndroidTvSocketFactory(clientMaterial, expectedServerCertSha256)
        val s = factory.connect(host, port)
        socket = s
        val sc = CoroutineScope(Dispatchers.IO)
        scope = sc
        logI("ATV remote channel up to $host:$port")
        return sc.launch {
            runCatching {
                while (true) {
                    val frame = AndroidTvFraming.readFrame(s.inputStream)
                    // Schema-less ground-truth dump first — if our
                    // RemoteMessage decoder is wrong, this still shows
                    // exactly what the TV sent.
                    logI("<< remote frame structure:\n${AndroidTvProtoDump.dump(frame)}")
                    val msg = RemoteMessage.decode(frame)
                    when (msg) {
                        is RemoteMessage.PingRequest -> {
                            // Canonical schema: ping_request at field 8,
                            // ping_response at field 9. Reply directly
                            // from the read loop — the TV closes the
                            // socket after ~10 s without a response, so
                            // we can't risk the message getting stuck
                            // behind a slow downstream observer.
                            logD("<< ATV PingRequest val1=${msg.val1}")
                            runCatching { send(RemoteMessage.PingResponse(msg.val1)) }
                                .onFailure { logW("ping reply failed: ${it.message}") }
                        }
                        is RemoteMessage.Error -> {
                            // RemoteError at field 3 — the TV is telling
                            // us our last message was rejected. Inner
                            // `message` field carries the offending
                            // RemoteMessage. Surface this loudly so we
                            // can fix whatever the schema mismatch is.
                            logE("<< ATV RemoteError: ${msg.message}")
                            _incoming.emit(msg)
                        }
                        is RemoteMessage.Unknown -> {
                            logD("<< ATV unknown field=${msg.fieldNumber}")
                        }
                        else -> {
                            logD("<< ATV ${msg::class.simpleName}")
                            _incoming.emit(msg)
                        }
                    }
                }
            }.onFailure { e ->
                logE("ATV remote read loop ended", e)
                onClose(e)
            }
        }
    }

    // Every TLS write must run on Dispatchers.IO — Conscrypt's
    // SSLOutputStream calls blockGuardOnNetwork() which throws under
    // StrictMode if invoked from the Main thread. Without the
    // withContext switch this would crash on the user's first key tap.
    suspend fun send(msg: RemoteMessage) = withContext(Dispatchers.IO) {
        val data = msg.encode()
        logI(">> remote ${msg::class.simpleName} structure:\n${AndroidTvProtoDump.dump(data)}")
        writeLock.withLock {
            val s = socket ?: error("ATV channel not connected")
            AndroidTvFraming.writeFrame(s.outputStream, data)
        }
    }

    fun close() {
        scope?.cancel()
        runCatching { socket?.close() }
        socket = null
    }
}
