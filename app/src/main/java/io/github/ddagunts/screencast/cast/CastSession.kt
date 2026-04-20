package io.github.ddagunts.screencast.cast

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

sealed class CastState {
    data object Idle : CastState()
    data class Connecting(val device: CastDevice) : CastState()
    data class Casting(val device: CastDevice) : CastState()
    data class Error(val message: String) : CastState()
}

class CastSession(val device: CastDevice, pinStore: CastCertPinStore? = null) {
    private val channel = CastChannel(device.host, device.port, pinStore)
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state

    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private var transportId: String? = null
    private var sessionId: String? = null
    private var mediaSessionId: Int? = null

    suspend fun startCast(appId: String, mediaUrl: String, contentType: String) {
        _state.value = CastState.Connecting(device)
        val s = CoroutineScope(Dispatchers.IO)
        scope = s
        try {
            readerJob = channel.connect { e ->
                _state.value = CastState.Error(e?.message ?: "disconnected")
            }
        } catch (e: Throwable) {
            logE("connect failed", e)
            _state.value = CastState.Error(e.message ?: "connect failed")
            return
        }
        channel.send(connectMsg())
        s.launch { heartbeatLoop() }
        s.launch { handleIncoming() }

        val (launch, launchReqId) = launchMsg(appId)
        channel.send(launch)
        logI("LAUNCH sent (requestId=$launchReqId, appId=$appId)")

        // Wait for transportId from RECEIVER_STATUS (set by handleIncoming)
        withTimeoutOrNull(5000) {
            while (transportId == null || sessionId == null) delay(50)
        }
        val tid = transportId ?: run {
            _state.value = CastState.Error("launch timed out (no transport id)")
            return
        }
        val sid = sessionId ?: run {
            _state.value = CastState.Error("launch timed out (no session id)")
            return
        }

        channel.send(CastMessage(SENDER_ID, tid, CastNs.CONNECTION, """{"type":"CONNECT"}"""))
        val (load, loadReqId) = mediaLoadMsg(tid, sid, mediaUrl, contentType)
        channel.send(load)
        logI("LOAD sent (requestId=$loadReqId, url=$mediaUrl)")
        _state.value = CastState.Casting(device)
    }

    fun stop() {
        val tid = transportId
        val mid = mediaSessionId
        scope?.launch {
            runCatching {
                if (tid != null && mid != null) channel.send(mediaStopMsg(tid, mid).first)
                channel.send(closeMsg())
            }
            close()
        }
    }

    fun close() {
        readerJob?.cancel()
        channel.close()
        scope?.cancel()
        _state.value = CastState.Idle
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(5000)
            runCatching { channel.send(pingMsg()) }.onFailure { return }
        }
    }

    private suspend fun handleIncoming() {
        channel.incoming.collect { msg ->
            val obj = runCatching { JSONObject(msg.payloadUtf8) }.getOrNull() ?: return@collect
            val type = obj.optString("type")
            when {
                msg.namespace == CastNs.HEARTBEAT && type == "PING" -> channel.send(pongMsg())
                msg.namespace == CastNs.RECEIVER && type == "RECEIVER_STATUS" -> parseReceiverStatus(obj)
                msg.namespace == CastNs.MEDIA && type == "MEDIA_STATUS" -> parseMediaStatus(obj)
                msg.namespace == CastNs.MEDIA && type == "LOAD_FAILED" -> {
                    logE("LOAD_FAILED: $obj")
                    _state.value = CastState.Error("receiver rejected media")
                }
                msg.namespace == CastNs.CONNECTION && type == "CLOSE" -> {
                    logW("receiver sent CLOSE")
                    close()
                }
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
        logI("""receiver status: {"transportId":"$transportId","sessionId":"$sessionId"}""")
    }

    private fun parseMediaStatus(obj: JSONObject) {
        val arr = obj.optJSONArray("status") ?: return
        if (arr.length() == 0) return
        val s = arr.getJSONObject(0)
        val msid = s.optInt("mediaSessionId", -1)
        if (msid > 0) mediaSessionId = msid
        logI("media status: playerState=${s.optString("playerState")} mediaSessionId=$msid")
    }
}
