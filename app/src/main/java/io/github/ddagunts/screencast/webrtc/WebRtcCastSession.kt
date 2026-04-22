package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.Intent
import io.github.ddagunts.screencast.cast.CastCertPinStore
import io.github.ddagunts.screencast.cast.CastChannel
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastMessage
import io.github.ddagunts.screencast.cast.CastNs
import io.github.ddagunts.screencast.cast.DEFAULT_RECEIVER_ID
import io.github.ddagunts.screencast.cast.SENDER_ID
import io.github.ddagunts.screencast.cast.closeMsg
import io.github.ddagunts.screencast.cast.connectMsg
import io.github.ddagunts.screencast.cast.launchMsg
import io.github.ddagunts.screencast.cast.pingMsg
import io.github.ddagunts.screencast.cast.pongMsg
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.IceCandidate

// WebRTC-mode session lifecycle. Mirrors the shape of CastSession (cast/) but
// the media pipeline here is WebRTC rather than HLS, so there is no mediaSessionId,
// no SEEK/PAUSE/PLAY, no volume control (for now). The receiver HTML page
// handles rendering.
//
// State machine:
//   Idle → Connecting → Launching → Signaling → Casting → Idle|Error
sealed class WebRtcState {
    data object Idle : WebRtcState()
    data class Connecting(val device: CastDevice) : WebRtcState()
    data class Launching(val device: CastDevice) : WebRtcState()
    data class Signaling(val device: CastDevice) : WebRtcState()
    data class Casting(val device: CastDevice) : WebRtcState()
    data class Error(val message: String) : WebRtcState()
}

class WebRtcCastSession(
    private val context: Context,
    val device: CastDevice,
    pinStore: CastCertPinStore? = null,
) {
    private val channel = CastChannel(device.host, device.port, pinStore)
    private val _state = MutableStateFlow<WebRtcState>(WebRtcState.Idle)
    val state: StateFlow<WebRtcState> = _state

    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private var transportId: String? = null
    private var sessionId: String? = null
    private var peer: WebRtcPeer? = null

    @Volatile private var lastHeartbeatMs: Long = 0L

    suspend fun startCast(appId: String, projectionData: Intent, onProjectionStopped: () -> Unit) {
        if (appId.isBlank()) {
            _state.value = WebRtcState.Error("custom App ID is not set — open Settings and paste it")
            return
        }
        _state.value = WebRtcState.Connecting(device)
        val s = CoroutineScope(Dispatchers.IO)
        scope = s
        try {
            readerJob = channel.connect { e ->
                _state.value = WebRtcState.Error(e?.message ?: "disconnected")
            }
        } catch (e: Throwable) {
            logE("webrtc connect failed", e)
            _state.value = WebRtcState.Error(e.message ?: "connect failed")
            return
        }
        channel.send(connectMsg())
        lastHeartbeatMs = System.currentTimeMillis()
        s.launch { heartbeatLoop() }
        s.launch { handleIncoming() }

        _state.value = WebRtcState.Launching(device)
        val (launch, reqId) = launchMsg(appId)
        channel.send(launch)
        logI("LAUNCH sent (requestId=$reqId, appId=$appId)")

        val ok = withTimeoutOrNull(10_000) {
            while (transportId == null || sessionId == null) delay(50)
            true
        }
        val tid = transportId
        if (ok == null || tid == null) {
            _state.value = WebRtcState.Error("launch timed out — is the custom receiver App ID correct?")
            return
        }
        // Virtual-connection to the receiver app so it'll route our custom
        // namespace messages. Without this, sends to transportId are dropped.
        channel.send(CastMessage(SENDER_ID, tid, CastNs.CONNECTION, """{"type":"CONNECT"}"""))

        _state.value = WebRtcState.Signaling(device)

        // Peer is only built now, after transportId is known — so every ICE
        // candidate the library emits has a valid destination from the start.
        val p = WebRtcPeer(context, onIceCandidate = { cand ->
            scope?.launch {
                runCatching {
                    channel.send(iceMsg(tid, cand.sdp, cand.sdpMid, cand.sdpMLineIndex))
                }.onFailure { logW("send ICE: $it") }
            }
        })
        peer = p
        p.build()
        p.addScreenSource(projectionData) { onProjectionStopped() }

        val offerSdp = p.createOffer()
        channel.send(offerMsg(tid, offerSdp))
        logI("OFFER sent (${offerSdp.length} bytes)")

        // State flips to Casting once the peer reports CONNECTED. If that
        // never happens within 15 s the ICE most likely failed; we leave the
        // session in Signaling so the UI surfaces "connecting" rather than a
        // misleading Casting state.
        s.launch {
            p.connectionState.collect { cs ->
                when (cs.name) {
                    "CONNECTED" -> {
                        if (_state.value is WebRtcState.Signaling) {
                            _state.value = WebRtcState.Casting(device)
                        }
                    }
                    "FAILED" -> _state.value = WebRtcState.Error("WebRTC ICE failed — are both devices on the same LAN?")
                    "CLOSED" -> {
                        if (_state.value !is WebRtcState.Idle && _state.value !is WebRtcState.Error) {
                            _state.value = WebRtcState.Idle
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stop() {
        val tid = transportId
        scope?.launch {
            runCatching {
                if (tid != null) channel.send(byeMsg(tid))
                channel.send(closeMsg())
            }
            close()
        }
    }

    fun close() {
        runCatching { peer?.close() }
        peer = null
        readerJob?.cancel()
        channel.close()
        scope?.cancel()
        _state.value = WebRtcState.Idle
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            val since = System.currentTimeMillis() - lastHeartbeatMs
            if (since > HEARTBEAT_TIMEOUT_MS) {
                logE("heartbeat: no PONG in ${since}ms, declaring receiver dead")
                _state.value = WebRtcState.Error("receiver heartbeat timeout")
                close()
                return
            }
            runCatching { channel.send(pingMsg()) }.onFailure { return }
        }
    }

    private suspend fun handleIncoming() {
        channel.incoming.collect { msg ->
            val obj = runCatching { JSONObject(msg.payloadUtf8) }.getOrNull() ?: return@collect
            val type = obj.optString("type")
            when {
                msg.namespace == CastNs.HEARTBEAT && type == "PING" -> {
                    lastHeartbeatMs = System.currentTimeMillis()
                    channel.send(pongMsg())
                }
                msg.namespace == CastNs.HEARTBEAT && type == "PONG" -> {
                    lastHeartbeatMs = System.currentTimeMillis()
                }
                msg.namespace == CastNs.RECEIVER && type == "RECEIVER_STATUS" -> parseReceiverStatus(obj)
                msg.namespace == WEBRTC_NAMESPACE -> handleSignaling(obj, type)
                msg.namespace == CastNs.CONNECTION && type == "CLOSE" -> {
                    logW("receiver sent CLOSE")
                    close()
                }
            }
        }
    }

    private suspend fun handleSignaling(obj: JSONObject, type: String) {
        when (type) {
            SignalingType.READY -> logI("receiver reports READY")
            SignalingType.ANSWER -> {
                val sdp = obj.optString("sdp")
                if (sdp.isEmpty()) { logE("ANSWER missing sdp"); return }
                runCatching { peer?.setRemoteAnswer(sdp) }
                    .onFailure {
                        logE("setRemoteAnswer failed", it)
                        _state.value = WebRtcState.Error("remote answer rejected: ${it.message}")
                    }
                logI("ANSWER applied (${sdp.length} bytes)")
            }
            SignalingType.ICE -> {
                val c = obj.optJSONObject("candidate") ?: return
                val cand = c.optString("candidate")
                if (cand.isEmpty()) return
                val mid = c.optString("sdpMid").ifEmpty { null }
                val mIdx = c.optInt("sdpMLineIndex", 0)
                peer?.addRemoteIceCandidate(IceCandidate(mid, mIdx, cand))
            }
            SignalingType.BYE -> {
                logI("receiver sent BYE")
                close()
            }
        }
    }

    private fun parseReceiverStatus(obj: JSONObject) {
        val status = obj.optJSONObject("status") ?: return
        val apps = status.optJSONArray("applications") ?: return
        if (apps.length() == 0) return
        val app = apps.getJSONObject(0)
        transportId = app.optString("transportId").ifEmpty { null }
        sessionId = app.optString("sessionId").ifEmpty { null }
        logI("""webrtc receiver status: {"transportId":"$transportId","sessionId":"$sessionId"}""")
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }
}
