package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import io.github.ddagunts.screencast.cast.CastCertPinStore
import io.github.ddagunts.screencast.cast.CastChannel
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastMessage
import io.github.ddagunts.screencast.cast.CastNs
import io.github.ddagunts.screencast.cast.CastVolume
import io.github.ddagunts.screencast.cast.DEFAULT_RECEIVER_ID
import io.github.ddagunts.screencast.cast.SENDER_ID
import io.github.ddagunts.screencast.cast.closeMsg
import io.github.ddagunts.screencast.cast.connectMsg
import io.github.ddagunts.screencast.cast.launchMsg
import io.github.ddagunts.screencast.cast.pingMsg
import io.github.ddagunts.screencast.cast.pongMsg
import io.github.ddagunts.screencast.cast.setMuteMsg
import io.github.ddagunts.screencast.cast.setVolumeMsg
import io.github.ddagunts.screencast.util.NetworkUtils
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
    private val sessionConfig: WebRtcSessionConfig,
) {
    private val channel = CastChannel(device.host, device.port, pinStore)
    private val _state = MutableStateFlow<WebRtcState>(WebRtcState.Idle)
    val state: StateFlow<WebRtcState> = _state

    // Receiver-level volume: works exactly the same as in HLS mode — volume
    // lives on the device (Chromecast), not the media session, so we can send
    // SET_VOLUME without a transportId. UI hides the slider when isFixed.
    private val _volume = MutableStateFlow(CastVolume())
    val volume: StateFlow<CastVolume> = _volume

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
        // Pin the process to the real Wi-Fi Network so libwebrtc's UDP sockets
        // (ICE STUN + RTP) bypass any always-on VPN's default route. Without
        // this, devices with apps like Rethink/bravedns active see ICE time
        // out: packets leave via tun0 with source 10.x and the Chromecast
        // can't route back. networkIgnoreMask in PeerConnectionFactory only
        // hides VPN candidates from the OFFER; it doesn't control outbound
        // routing. bindProcessToNetwork is the nuclear option that forces all
        // sockets created thereafter onto the Wi-Fi Network. Undone in close().
        bindProcessToWifi()
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
        val p = WebRtcPeer(context, sessionConfig, onIceCandidate = { cand ->
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
        // Unpin the process from Wi-Fi — future non-WebRTC work should use
        // the default routing again (including any VPN the user has on).
        runCatching { unbindProcessFromNetwork() }
            .onFailure { logW("close: unbindProcessFromNetwork: $it") }
        _state.value = WebRtcState.Idle
    }

    private fun bindProcessToWifi() {
        val net = NetworkUtils.getWifiNetwork(context)
        if (net == null) {
            logW("bindProcessToWifi: no Wi-Fi Network with NOT_VPN — skipping bind")
            return
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            logW("bindProcessToWifi: ConnectivityManager unavailable")
            return
        }
        val ok = cm.bindProcessToNetwork(net)
        logI("bindProcessToWifi: ok=$ok network=$net")
    }

    private fun unbindProcessFromNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.bindProcessToNetwork(null)
        logI("unbindProcessFromNetwork")
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

    // Volume lives on the receiver, not the media session, so we can send
    // these without waiting for transportId. Coerce level into [0, 1] to
    // match the Cast V2 contract — receivers silently clamp anyway, but
    // clean bounds keep the slider behaving correctly.
    suspend fun setVolume(level: Double) {
        if (_volume.value.isFixed) {
            logW("setVolume: receiver reports fixed volume")
            return
        }
        channel.send(setVolumeMsg(level).first)
    }

    suspend fun setMute(muted: Boolean) {
        channel.send(setMuteMsg(muted).first)
    }

    private fun parseReceiverStatus(obj: JSONObject) {
        val status = obj.optJSONObject("status") ?: return
        // Volume arrives on both the unsolicited requestId=0 broadcast and
        // the LAUNCH response, so we pick it up immediately after connect
        // (before any applications are populated). Parse it unconditionally.
        status.optJSONObject("volume")?.let { v ->
            val current = _volume.value
            _volume.value = CastVolume(
                level = v.optDouble("level", current.level),
                muted = v.optBoolean("muted", current.muted),
                controlType = v.optString("controlType", current.controlType),
            )
        }
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
