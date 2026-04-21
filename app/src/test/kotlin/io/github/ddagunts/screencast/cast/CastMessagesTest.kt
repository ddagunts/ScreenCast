package io.github.ddagunts.screencast.cast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMessagesTest {

    @Test fun `connectMsg targets the default receiver on the connection namespace`() {
        val m = connectMsg()
        assertEquals(SENDER_ID, m.sourceId)
        assertEquals(DEFAULT_RECEIVER_ID, m.destinationId)
        assertEquals(CastNs.CONNECTION, m.namespace)
        assertEquals("CONNECT", JSONObject(m.payloadUtf8).getString("type"))
    }

    @Test fun `connectMsg can be addressed to a custom destination`() {
        val m = connectMsg(to = "transport-xyz")
        assertEquals("transport-xyz", m.destinationId)
    }

    @Test fun `closeMsg matches connection shape`() {
        val m = closeMsg()
        assertEquals(CastNs.CONNECTION, m.namespace)
        assertEquals("CLOSE", JSONObject(m.payloadUtf8).getString("type"))
    }

    @Test fun `heartbeat messages carry PING and PONG types on the heartbeat namespace`() {
        assertEquals(CastNs.HEARTBEAT, pingMsg().namespace)
        assertEquals("PING", JSONObject(pingMsg().payloadUtf8).getString("type"))
        assertEquals("PONG", JSONObject(pongMsg().payloadUtf8).getString("type"))
    }

    @Test fun `launchMsg carries appId and a matching requestId`() {
        val (m, reqId) = launchMsg("CC1AD845")
        val p = JSONObject(m.payloadUtf8)
        assertEquals("LAUNCH", p.getString("type"))
        assertEquals("CC1AD845", p.getString("appId"))
        assertEquals(reqId, p.getInt("requestId"))
    }

    @Test fun `mediaLoadMsg encodes the playlist URL as LIVE HLS and honors autoplay`() {
        val (m, _) = mediaLoadMsg(
            transportId = "t-id",
            sessionId = "s-id",
            mediaUrl = "http://10.0.0.1/stream.m3u8",
            contentType = "application/x-mpegURL",
            autoplay = false,
        )
        assertEquals("t-id", m.destinationId)
        assertEquals(CastNs.MEDIA, m.namespace)
        val p = JSONObject(m.payloadUtf8)
        assertEquals("LOAD", p.getString("type"))
        assertEquals("s-id", p.getString("sessionId"))
        assertFalse(p.getBoolean("autoplay"))
        val media = p.getJSONObject("media")
        assertEquals("http://10.0.0.1/stream.m3u8", media.getString("contentId"))
        assertEquals("application/x-mpegURL", media.getString("contentType"))
        assertEquals("LIVE", media.getString("streamType"))
    }

    @Test fun `mediaLoadMsg defaults autoplay to true`() {
        val (m, _) = mediaLoadMsg("t", "s", "u", "application/x-mpegURL")
        assertTrue(JSONObject(m.payloadUtf8).getBoolean("autoplay"))
    }

    @Test fun `mediaSeekMsg defaults to PLAYBACK_PAUSE for startup align`() {
        val (m, _) = mediaSeekMsg("t", 7, 12.345)
        val p = JSONObject(m.payloadUtf8)
        assertEquals("SEEK", p.getString("type"))
        assertEquals(7, p.getInt("mediaSessionId"))
        assertEquals(12.345, p.getDouble("currentTime"), 0.0001)
        assertEquals("PLAYBACK_PAUSE", p.getString("resumeState"))
    }

    @Test fun `mediaSeekMsg accepts PLAYBACK_START for steady-state maintenance`() {
        val (m, _) = mediaSeekMsg("t", 7, 0.0, resumeState = "PLAYBACK_START")
        assertEquals("PLAYBACK_START", JSONObject(m.payloadUtf8).getString("resumeState"))
    }

    @Test fun `mediaSeekMsg with empty resumeState omits the field entirely`() {
        val (m, _) = mediaSeekMsg("t", 7, 0.0, resumeState = "")
        assertFalse(JSONObject(m.payloadUtf8).has("resumeState"))
    }

    @Test fun `setVolumeMsg clamps level into 0 to 1`() {
        val high = JSONObject(setVolumeMsg(5.0).first.payloadUtf8).getJSONObject("volume")
        val low = JSONObject(setVolumeMsg(-1.0).first.payloadUtf8).getJSONObject("volume")
        assertEquals(1.0, high.getDouble("level"), 0.001)
        assertEquals(0.0, low.getDouble("level"), 0.001)
    }

    @Test fun `setVolumeMsg passes in-range levels through unchanged`() {
        val mid = JSONObject(setVolumeMsg(0.42).first.payloadUtf8).getJSONObject("volume")
        assertEquals(0.42, mid.getDouble("level"), 0.001)
    }

    @Test fun `setVolumeMsg does not set muted`() {
        val vol = JSONObject(setVolumeMsg(0.5).first.payloadUtf8).getJSONObject("volume")
        assertFalse("level-only message should not carry muted", vol.has("muted"))
    }

    @Test fun `setMuteMsg carries only the muted flag`() {
        val vol = JSONObject(setMuteMsg(true).first.payloadUtf8).getJSONObject("volume")
        assertTrue(vol.getBoolean("muted"))
        assertFalse("mute-only message should not carry level", vol.has("level"))
    }

    @Test fun `mediaPauseMsg and mediaPlayMsg carry minimal payload`() {
        val (pause, _) = mediaPauseMsg("t", 42)
        val (play, _) = mediaPlayMsg("t", 42)
        assertEquals("PAUSE", JSONObject(pause.payloadUtf8).getString("type"))
        assertEquals("PLAY", JSONObject(play.payloadUtf8).getString("type"))
        assertEquals(42, JSONObject(pause.payloadUtf8).getInt("mediaSessionId"))
    }

    @Test fun `mediaStopMsg targets the media namespace with session id`() {
        val (m, _) = mediaStopMsg("t", 42)
        assertEquals(CastNs.MEDIA, m.namespace)
        val p = JSONObject(m.payloadUtf8)
        assertEquals("STOP", p.getString("type"))
        assertEquals(42, p.getInt("mediaSessionId"))
    }

    @Test fun `mediaGetStatusMsg needs only a transport id`() {
        val (m, _) = mediaGetStatusMsg("t")
        assertEquals("t", m.destinationId)
        assertEquals("GET_STATUS", JSONObject(m.payloadUtf8).getString("type"))
    }

    @Test fun `request ids are monotonically increasing across builders`() {
        val (_, a) = launchMsg("X")
        val (_, b) = launchMsg("X")
        val (_, c) = mediaGetStatusMsg("t")
        assertTrue(b > a)
        assertTrue(c > b)
    }
}
