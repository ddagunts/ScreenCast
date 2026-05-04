package io.github.ddagunts.screencast.androidtv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class AndroidTvFramingTest {

    @Test fun `frame under 128 bytes uses single-byte varint prefix`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val out = ByteArrayOutputStream()
        AndroidTvFraming.writeFrame(out, payload)
        // varint(5) = single byte 0x05.
        assertEquals(5.toByte(), out.toByteArray()[0])
        assertEquals(payload.size + 1, out.toByteArray().size)
    }

    @Test fun `frame over 127 bytes uses multi-byte varint prefix`() {
        val payload = ByteArray(200) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()
        AndroidTvFraming.writeFrame(out, payload)
        // varint(200) = 0xC8 0x01 (low 7 bits 0x48 with continuation = 0xC8, high = 0x01).
        val bytes = out.toByteArray()
        assertEquals(0xC8.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertEquals(payload.size + 2, bytes.size)
    }

    @Test fun `roundtrip preserves payload bytes`() {
        val payload = ByteArray(1024) { ((it * 31) % 256).toByte() }
        val out = ByteArrayOutputStream()
        AndroidTvFraming.writeFrame(out, payload)
        val read = AndroidTvFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(payload, read)
    }

    @Test fun `roundtrip handles empty payload`() {
        val out = ByteArrayOutputStream()
        AndroidTvFraming.writeFrame(out, ByteArray(0))
        val read = AndroidTvFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0, read.size)
    }

    @Test fun `varint roundtrip across byte boundaries`() {
        for (value in listOf(0L, 1L, 127L, 128L, 16_383L, 16_384L, 65_536L)) {
            val out = ByteArrayOutputStream()
            AndroidTvFraming.writeVarint(out, value)
            val back = AndroidTvFraming.readVarint(ByteArrayInputStream(out.toByteArray()))
            assertEquals("varint roundtrip for $value", value, back)
        }
    }

    @Test fun `readFrame throws IOException on truncated payload`() {
        val truncated = byteArrayOf(0x05, 0x01, 0x02) // claims 5 bytes, only 2 follow
        assertThrows(IOException::class.java) {
            AndroidTvFraming.readFrame(ByteArrayInputStream(truncated))
        }
    }

    @Test fun `readFrame rejects oversized frame length`() {
        // varint(100_000) > the 65_536 cap.
        val out = ByteArrayOutputStream()
        AndroidTvFraming.writeVarint(out, 100_000L)
        assertThrows(IllegalStateException::class.java) {
            AndroidTvFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        }
    }
}
