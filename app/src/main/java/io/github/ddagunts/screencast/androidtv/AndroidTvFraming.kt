package io.github.ddagunts.screencast.androidtv

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// Polo wire framing for both Android TV Remote v2 ports (6467 pairing,
// 6466 remote control). Each frame is a varint length prefix followed by
// `length` bytes of protobuf payload.
//
// Empirically confirmed against a real TV (status=ERROR / bad-frame-length
// debugging round-trip): the TV reads the prefix as a varint, not as a
// 4-byte big-endian int the way Cast V2 does. Our send must match — a
// 4-byte BE prefix with low message length encodes as 0x00 0x00 0x00 N
// which the TV reads as varint=0 (zero-length frame), and then it
// rejects the next bytes as a malformed PoloMessage.
//
// Most polo messages fit in a single varint byte. Larger ones (IME batch
// edits during voice typing) extend it — implement the full varint reader
// rather than treating the prefix as a single byte.
//
// Frame size cap matches the Cast channel's 64 KB limit. ATV remote
// messages are tiny (< 1 KB in practice); a higher cap would just leave
// us open to pathological inputs from a misbehaving peer.
object AndroidTvFraming {
    private const val MAX_FRAME_SIZE = 65_536

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_SIZE) {
            "frame too large: ${payload.size} > $MAX_FRAME_SIZE"
        }
        writeVarint(out, payload.size.toLong())
        out.write(payload)
        out.flush()
    }

    @Throws(IOException::class)
    fun readFrame(input: InputStream): ByteArray {
        val len = readVarint(input)
        if (len < 0 || len > MAX_FRAME_SIZE) error("bad frame length $len")
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw IOException("EOF after $read of ${buf.size} frame bytes")
            read += n
        }
        return buf
    }

    fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    @Throws(IOException::class)
    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        // 64-bit varint: at most 10 bytes. Past that, the input is malformed —
        // bail rather than spin.
        for (i in 0 until 10) {
            val b = input.read()
            if (b < 0) throw IOException("EOF inside varint at byte $i")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        error("malformed varint (more than 10 bytes)")
    }
}
