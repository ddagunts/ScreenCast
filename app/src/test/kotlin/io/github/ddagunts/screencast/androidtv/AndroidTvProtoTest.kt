package io.github.ddagunts.screencast.androidtv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AndroidTvProtoTest {

    @Test fun `OuterMessage with PairingRequest roundtrips through encode and decode`() {
        val msg = OuterMessage.pairingRequest(PairingRequest("androidtvremote2", "ScreenCast"))
        val back = OuterMessage.decode(msg.encode())
        // Canonical schema has no `type` discriminator; receivers
        // dispatch on which inner submessage is populated. Use
        // effectiveType which derives from the populated field.
        assertEquals(OuterType.PAIRING_REQUEST, back.effectiveType)
        assertEquals(OuterStatus.OK, back.status)
        assertEquals(msg.pairingRequest, back.pairingRequest)
    }

    @Test fun `OuterMessage encodes inner submessage at field number matching type`() {
        // Field 10 wire type 2 should appear in the encoded bytes for a
        // PAIRING_REQUEST envelope — the polo wire format dispatches on
        // the field number, not on a generic payload.
        val bytes = OuterMessage.pairingRequest(PairingRequest("svc", "client")).encode()
        // Tag (10 << 3) | 2 = 82 → single varint byte 0x52.
        assertTrue("encoded bytes must contain pairing_request tag 0x52", bytes.contains(0x52.toByte()))
    }

    @Test fun `OuterMessage decode of empty bytes yields defaults`() {
        val decoded = OuterMessage.decode(ByteArray(0))
        assertEquals(0, decoded.protocolVersion)
        assertEquals(OuterStatus.UNKNOWN, decoded.status)
        assertEquals(OuterType.UNKNOWN, decoded.type)
        assertEquals(null, decoded.pairingRequest)
        assertEquals(null, decoded.secretAck)
    }

    @Test fun `OuterMessage decoder skips unknown trailing field`() {
        val real = OuterMessage.secret(Secret(byteArrayOf(0x09)))
        val withGarbage = real.encode() + encodeUnknownVarintField(99, 1234L)
        val back = OuterMessage.decode(withGarbage)
        assertEquals(real.type, back.type)
        assertEquals(real.secret, back.secret)
    }

    @Test fun `PairingRequest roundtrip`() {
        val payload = PairingRequest("androidtvremote2", "ScreenCast")
        val decoded = PairingRequest.decode(payload.encode())
        assertEquals(payload, decoded)
    }

    @Test fun `Options encodes repeated encodings`() {
        val opt = Options(
            inputEncodings = listOf(Encoding(EncodingType.HEXADECIMAL, 6)),
            outputEncodings = listOf(Encoding(EncodingType.HEXADECIMAL, 6)),
            preferredRole = RoleType.INPUT,
        )
        val back = Options.decode(opt.encode())
        assertEquals(1, back.inputEncodings.size)
        assertEquals(EncodingType.HEXADECIMAL, back.inputEncodings[0].type)
        assertEquals(6, back.inputEncodings[0].symbolLength)
        assertEquals(RoleType.INPUT, back.preferredRole)
    }

    @Test fun `SecretAck decode of empty body yields empty secret`() {
        val ack = SecretAck.decode(ByteArray(0))
        assertEquals(0, ack.secret.size)
    }

    @Test fun `SecretAck decode preserves bytes`() {
        val raw = ByteArray(32) { (it * 7 + 11).toByte() }
        val s = SecretAck(raw)
        // Encode as proto3 bytes field 1 by hand and decode it back.
        val out = ByteArrayOutputStream()
        out.write(((1 shl 3) or 2))
        writeVarint(out, raw.size.toLong())
        out.write(raw)
        val decoded = SecretAck.decode(out.toByteArray())
        assertArrayEquals(raw, decoded.secret)
    }

    @Test fun `RemoteMessage KeyInject encode then decode roundtrip`() {
        val msg = RemoteMessage.KeyInject(RemoteKeyCode.DPAD_CENTER, RemoteDirection.SHORT)
        val back = RemoteMessage.decode(msg.encode())
        assertEquals(msg, back)
    }

    @Test fun `RemoteMessage PingRequest decode picks the right variant`() {
        val msg = RemoteMessage.PingRequest(42)
        val back = RemoteMessage.decode(msg.encode())
        assertTrue("expected PingRequest, got ${back::class.simpleName}", back is RemoteMessage.PingRequest)
        assertEquals(42, (back as RemoteMessage.PingRequest).val1)
    }

    @Test fun `RemoteMessage SetVolumeLevel roundtrip preserves all fields`() {
        val msg = RemoteMessage.SetVolumeLevel("hdmi:1", 12, 30, true)
        val back = RemoteMessage.decode(msg.encode())
        assertEquals(msg, back)
    }

    @Test fun `RemoteMessage Configure roundtrip preserves device info`() {
        val info = RemoteDeviceInfo("Pixel 8", "Google", 1, "1", "io.example.pkg", "1.0.0")
        val msg = RemoteMessage.Configure(622, info)
        val back = RemoteMessage.decode(msg.encode()) as RemoteMessage.Configure
        assertEquals(622, back.code1)
        assertEquals(info, back.deviceInfo)
    }

    @Test fun `RemoteMessage decode of unrecognised submessage field yields Unknown`() {
        // Field 99 is not in our envelope — should round-trip as Unknown(99).
        // Tag (field<<3 | wire=2) = 794, which is a multi-byte varint.
        val out = ByteArrayOutputStream()
        writeVarint(out, ((99 shl 3) or 2).toLong())
        writeVarint(out, 0L)
        val back = RemoteMessage.decode(out.toByteArray())
        assertTrue("expected Unknown, got ${back::class.simpleName}", back is RemoteMessage.Unknown)
        assertEquals(99, (back as RemoteMessage.Unknown).fieldNumber)
    }

    @Test fun `RemoteMessage decoder ignores unrelated submessages after the first`() {
        // A KeyInject followed by an unknown field — first wins.
        val first = RemoteMessage.KeyInject(RemoteKeyCode.HOME, RemoteDirection.SHORT).encode()
        val out = ByteArrayOutputStream()
        out.write(first)
        writeVarint(out, ((99 shl 3) or 2).toLong())
        writeVarint(out, 0L)
        val back = RemoteMessage.decode(out.toByteArray())
        assertNotNull(back)
        assertTrue(back is RemoteMessage.KeyInject)
    }

    private fun encodeUnknownVarintField(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((field shl 3).toLong()))
        writeVarint(out, value)
        return out.toByteArray()
    }

    private fun encodeUnknownBytesField(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((field shl 3) or 2).toLong())
        writeVarint(out, value.size.toLong())
        out.write(value)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }
}
