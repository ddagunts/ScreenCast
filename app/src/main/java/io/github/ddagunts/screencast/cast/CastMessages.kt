package io.github.ddagunts.screencast.cast

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object CastNs {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
}

const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
const val SENDER_ID = "sender-0"
const val DEFAULT_RECEIVER_ID = "receiver-0"

private val rid = AtomicInteger(1)
private fun nextRequestId() = rid.getAndIncrement()

fun connectMsg(to: String = DEFAULT_RECEIVER_ID) = CastMessage(
    SENDER_ID, to, CastNs.CONNECTION,
    """{"type":"CONNECT"}"""
)

fun closeMsg(to: String = DEFAULT_RECEIVER_ID) = CastMessage(
    SENDER_ID, to, CastNs.CONNECTION, """{"type":"CLOSE"}"""
)

fun pingMsg() = CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.HEARTBEAT, """{"type":"PING"}""")
fun pongMsg() = CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.HEARTBEAT, """{"type":"PONG"}""")

fun launchMsg(appId: String): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val payload = JSONObject()
        .put("type", "LAUNCH")
        .put("requestId", reqId)
        .put("appId", appId)
        .toString()
    return CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.RECEIVER, payload) to reqId
}

fun getReceiverStatus(): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    return CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.RECEIVER,
        """{"type":"GET_STATUS","requestId":$reqId}""") to reqId
}

fun mediaLoadMsg(
    transportId: String,
    sessionId: String,
    mediaUrl: String,
    contentType: String,
    autoplay: Boolean = true,
): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val metadata = JSONObject()
        .put("metadataType", 0)
        .put("title", "ScreenCast")
    val media = JSONObject()
        .put("contentId", mediaUrl)
        .put("contentType", contentType)
        .put("streamType", "LIVE")
        .put("metadata", metadata)
    val payload = JSONObject()
        .put("type", "LOAD")
        .put("requestId", reqId)
        .put("sessionId", sessionId)
        .put("autoplay", autoplay)
        .put("currentTime", 0)
        .put("media", media)
        .toString()
    return CastMessage(SENDER_ID, transportId, CastNs.MEDIA, payload) to reqId
}

fun mediaStopMsg(transportId: String, mediaSessionId: Int): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val payload = JSONObject()
        .put("type", "STOP")
        .put("requestId", reqId)
        .put("mediaSessionId", mediaSessionId)
        .toString()
    return CastMessage(SENDER_ID, transportId, CastNs.MEDIA, payload) to reqId
}

fun mediaPauseMsg(transportId: String, mediaSessionId: Int): Pair<CastMessage, Int> =
    simpleMediaCmd(transportId, mediaSessionId, "PAUSE")

fun mediaPlayMsg(transportId: String, mediaSessionId: Int): Pair<CastMessage, Int> =
    simpleMediaCmd(transportId, mediaSessionId, "PLAY")

// GET_STATUS on the media namespace prompts the receiver to echo a fresh
// MEDIA_STATUS with current currentTime and playerState. Cheap; used before
// sync-align to make sure our per-session currentTime readings are live.
fun mediaGetStatusMsg(transportId: String): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val payload = JSONObject()
        .put("type", "GET_STATUS")
        .put("requestId", reqId)
        .toString()
    return CastMessage(SENDER_ID, transportId, CastNs.MEDIA, payload) to reqId
}

// SEEK jumps the receiver to an absolute currentTime. resumeState controls
// post-seek playback: PLAYBACK_PAUSE (startup sync — hold until coordinated
// PLAY), PLAYBACK_START (maintenance — keep playing), or empty to preserve
// whatever state the receiver was in.
fun mediaSeekMsg(
    transportId: String,
    mediaSessionId: Int,
    seconds: Double,
    resumeState: String = "PLAYBACK_PAUSE",
): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val payload = JSONObject()
        .put("type", "SEEK")
        .put("requestId", reqId)
        .put("mediaSessionId", mediaSessionId)
        .put("currentTime", seconds)
        .apply { if (resumeState.isNotEmpty()) put("resumeState", resumeState) }
        .toString()
    return CastMessage(SENDER_ID, transportId, CastNs.MEDIA, payload) to reqId
}

private fun simpleMediaCmd(transportId: String, mediaSessionId: Int, type: String): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val payload = JSONObject()
        .put("type", type)
        .put("requestId", reqId)
        .put("mediaSessionId", mediaSessionId)
        .toString()
    return CastMessage(SENDER_ID, transportId, CastNs.MEDIA, payload) to reqId
}

// Volume is set on the receiver (top-level device), not a media session — it
// applies even if nothing is currently playing. `level` is clamped to [0,1].
fun setVolumeMsg(level: Double): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val volume = JSONObject().put("level", level.coerceIn(0.0, 1.0))
    val payload = JSONObject()
        .put("type", "SET_VOLUME")
        .put("requestId", reqId)
        .put("volume", volume)
        .toString()
    return CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.RECEIVER, payload) to reqId
}

fun setMuteMsg(muted: Boolean): Pair<CastMessage, Int> {
    val reqId = nextRequestId()
    val volume = JSONObject().put("muted", muted)
    val payload = JSONObject()
        .put("type", "SET_VOLUME")
        .put("requestId", reqId)
        .put("volume", volume)
        .toString()
    return CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.RECEIVER, payload) to reqId
}
