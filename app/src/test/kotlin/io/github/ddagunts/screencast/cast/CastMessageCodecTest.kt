package io.github.ddagunts.screencast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMessageCodecTest {

    @Test fun `encode then decode roundtrip preserves all fields`() {
        val m = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.tp.connection",
            payloadUtf8 = """{"type":"CONNECT"}""",
        )
        assertEquals(m, CastMessage.decode(m.encode()))
    }

    @Test fun `encoded frame starts with protocol_version tag and CASTV2_1_0 value`() {
        val enc = CastMessage("a", "b", "n", "p").encode()
        assertEquals((1 shl 3).toByte(), enc[0])
        assertEquals(0.toByte(), enc[1])
    }

    @Test fun `non-ASCII UTF-8 payload survives roundtrip`() {
        val m = CastMessage("src", "dst", "urn:x-cast:foo", "résumé 日本 \u0001")
        assertEquals(m.payloadUtf8, CastMessage.decode(m.encode()).payloadUtf8)
    }

    @Test fun `empty strings roundtrip cleanly`() {
        val m = CastMessage("", "", "", "")
        assertEquals(m, CastMessage.decode(m.encode()))
    }

    @Test fun `decoder skips unknown fields instead of failing`() {
        val base = CastMessage("s", "d", "urn:x-cast:n", "{}").encode()
        val withUnknown = prependVarintField(fieldNumber = 99, value = 12345L, suffix = base)
        val decoded = CastMessage.decode(withUnknown)
        assertEquals("s", decoded.sourceId)
        assertEquals("d", decoded.destinationId)
        assertEquals("urn:x-cast:n", decoded.namespace)
        assertEquals("{}", decoded.payloadUtf8)
    }

    @Test fun `decoder tolerates empty payload field`() {
        val m = CastMessage("s", "d", "ns", "")
        val enc = m.encode()
        assertTrue("frame should be non-empty", enc.isNotEmpty())
        assertEquals("", CastMessage.decode(enc).payloadUtf8)
    }

    private fun prependVarintField(fieldNumber: Int, value: Long, suffix: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeVarint(out, (fieldNumber shl 3).toLong())
        writeVarint(out, value)
        out.write(suffix)
        return out.toByteArray()
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }
}
