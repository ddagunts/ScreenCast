package io.github.ddagunts.screencast.webrtc

import io.github.ddagunts.screencast.cast.CastMessage
import io.github.ddagunts.screencast.cast.SENDER_ID
import org.json.JSONObject

// JSON message envelope on WEBRTC_NAMESPACE. Mirrors the three WebRTC
// signaling verbs (OFFER/ANSWER/ICE) plus an out-of-band READY we use to
// gate sending the offer until the receiver's RTCPeerConnection is actually
// wired up. BYE is a courtesy teardown hint; the Cast CLOSE message tears
// the virtual connection regardless.
object SignalingType {
    const val READY = "READY"
    const val OFFER = "OFFER"
    const val ANSWER = "ANSWER"
    const val ICE = "ICE"
    const val BYE = "BYE"
}

fun offerMsg(transportId: String, sdp: String): CastMessage {
    val payload = JSONObject()
        .put("type", SignalingType.OFFER)
        .put("sdp", sdp)
        .toString()
    return CastMessage(SENDER_ID, transportId, WEBRTC_NAMESPACE, payload)
}

fun iceMsg(
    transportId: String,
    candidate: String,
    sdpMid: String?,
    sdpMLineIndex: Int,
): CastMessage {
    val cand = JSONObject()
        .put("candidate", candidate)
        .put("sdpMid", sdpMid ?: JSONObject.NULL)
        .put("sdpMLineIndex", sdpMLineIndex)
    val payload = JSONObject()
        .put("type", SignalingType.ICE)
        .put("candidate", cand)
        .toString()
    return CastMessage(SENDER_ID, transportId, WEBRTC_NAMESPACE, payload)
}

fun byeMsg(transportId: String): CastMessage {
    val payload = JSONObject().put("type", SignalingType.BYE).toString()
    return CastMessage(SENDER_ID, transportId, WEBRTC_NAMESPACE, payload)
}
