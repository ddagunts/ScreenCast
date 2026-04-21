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

// controlType: "master" (device owns volume), "attenuation" (relative only), or
// "fixed" (cannot be changed — surface amp / soundbar). UI must disable the
// slider when fixed; sending SET_VOLUME will be silently ignored by the receiver.
data class CastVolume(
    val level: Double = 1.0,
    val muted: Boolean = false,
    val controlType: String = "master",
) {
    val isFixed: Boolean get() = controlType.equals("fixed", ignoreCase = true)
}

class CastSession(val device: CastDevice, pinStore: CastCertPinStore? = null) {
    private val channel = CastChannel(device.host, device.port, pinStore)
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state

    // Player state authority is the receiver — every transition comes from a
    // MEDIA_STATUS push. We never optimistically flip this on a local command;
    // doing so masks LOAD_FAILED and PAUSE rejections.
    private val _playerState = MutableStateFlow("IDLE")
    val playerState: StateFlow<String> = _playerState

    private val _volume = MutableStateFlow(CastVolume())
    val volume: StateFlow<CastVolume> = _volume

    // Current playback position as reported by the receiver's latest
    // MEDIA_STATUS. Units are seconds; for HLS LIVE without PDT these are
    // receiver-local offsets from the first manifest fetch — comparable
    // across status updates for the same receiver, *not necessarily* across
    // different receivers. Used by the sync-align flow with a GET_STATUS
    // refresh immediately before reading.
    private val _currentTime = MutableStateFlow(0.0)
    val currentTime: StateFlow<Double> = _currentTime

    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private var transportId: String? = null
    private var sessionId: String? = null
    private var mediaSessionId: Int? = null

    suspend fun startCast(appId: String, mediaUrl: String, contentType: String, autoplay: Boolean = true) {
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
        val (load, loadReqId) = mediaLoadMsg(tid, sid, mediaUrl, contentType, autoplay)
        channel.send(load)
        logI("LOAD sent (requestId=$loadReqId, url=$mediaUrl, autoplay=$autoplay)")
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

    // Pause/play are no-ops until LAUNCH completes and the receiver has sent
    // back a MEDIA_STATUS with a mediaSessionId. The UI is expected to keep the
    // buttons disabled until `state` becomes Casting; this is belt-and-suspenders.
    suspend fun pause() {
        val tid = transportId ?: return logW("pause: no transportId yet")
        val mid = mediaSessionId ?: return logW("pause: no mediaSessionId yet")
        channel.send(mediaPauseMsg(tid, mid).first)
    }

    suspend fun play() {
        val tid = transportId ?: return logW("play: no transportId yet")
        val mid = mediaSessionId ?: return logW("play: no mediaSessionId yet")
        channel.send(mediaPlayMsg(tid, mid).first)
    }

    // Volume lives on the receiver, not the media session, so it works before
    // LOAD completes. No transport/session id needed.
    suspend fun setVolume(level: Double) {
        if (_volume.value.isFixed) return logW("setVolume: receiver reports fixed volume")
        channel.send(setVolumeMsg(level).first)
    }

    suspend fun setMute(muted: Boolean) {
        channel.send(setMuteMsg(muted).first)
    }

    // Seek the receiver to `seconds`. resumeState is forwarded to the Cast
    // media namespace: PLAYBACK_PAUSE (default) lands paused at the new
    // offset, PLAYBACK_START keeps playback rolling — the latter is what
    // steady-state drift correction needs. Returns immediately after the
    // write; the receiver echoes MEDIA_STATUS when the seek completes.
    suspend fun seek(seconds: Double, resumeState: String = "PLAYBACK_PAUSE") {
        val tid = transportId ?: return logW("seek: no transportId yet")
        val mid = mediaSessionId ?: return logW("seek: no mediaSessionId yet")
        channel.send(mediaSeekMsg(tid, mid, seconds, resumeState).first)
    }

    // Prompt the receiver to emit a fresh MEDIA_STATUS so `_currentTime` is
    // up-to-date. Receivers push MEDIA_STATUS on state changes but not
    // continuously; without this nudge the last-seen currentTime can be
    // several seconds stale.
    suspend fun requestStatus() {
        val tid = transportId ?: return logW("requestStatus: no transportId yet")
        channel.send(mediaGetStatusMsg(tid).first)
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
        // Volume is reported at the receiver level independent of any active app,
        // so parse it before bailing on the empty-applications case.
        status.optJSONObject("volume")?.let { v ->
            val current = _volume.value
            _volume.value = CastVolume(
                level = v.optDouble("level", current.level),
                muted = v.optBoolean("muted", current.muted),
                controlType = v.optString("controlType").ifEmpty { current.controlType },
            )
        }
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
        val ps = s.optString("playerState")
        if (ps.isNotEmpty()) _playerState.value = ps
        if (s.has("currentTime")) _currentTime.value = s.optDouble("currentTime", _currentTime.value)
        logI("media status: playerState=$ps mediaSessionId=$msid currentTime=${"%.3f".format(_currentTime.value)}")
    }
}
