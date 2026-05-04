package io.github.ddagunts.screencast.androidtv

// Schema-less protobuf field dumper for diagnostic logging. Iterates every
// (field number, wire type, value) tuple in a serialized proto3 message
// and prints them, recursing into length-delimited fields that look like
// they might be valid sub-messages.
//
// Lets us see the actual on-the-wire shape of a TV's reply without
// trusting our (possibly wrong) AndroidTvProto.kt schema interpretation.
// Used in pairing-channel error paths and during initial bring-up to
// reverse-engineer the polo wire format empirically.
object AndroidTvProtoDump {

    fun dump(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(empty)"
        val sb = StringBuilder()
        dumpInto(sb, bytes, depth = 0)
        return sb.toString().trimEnd()
    }

    private fun dumpInto(sb: StringBuilder, bytes: ByteArray, depth: Int) {
        val indent = "  ".repeat(depth)
        var off = 0
        while (off < bytes.size) {
            val (tag, next) = readVarint(bytes, off) ?: run {
                sb.append(indent).append("[malformed tag at offset $off, remaining=${bytes.size - off}]\n")
                return
            }
            off = next
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when (wire) {
                0 -> {
                    val (v, n) = readVarint(bytes, off) ?: run {
                        sb.append(indent).append("field $field varint [malformed]\n"); return
                    }
                    sb.append(indent).append("field $field varint = $v\n")
                    off = n
                }
                1 -> {
                    if (off + 8 > bytes.size) { sb.append(indent).append("field $field fixed64 [truncated]\n"); return }
                    sb.append(indent).append("field $field fixed64 = ").append(hex(bytes, off, 8)).append('\n')
                    off += 8
                }
                5 -> {
                    if (off + 4 > bytes.size) { sb.append(indent).append("field $field fixed32 [truncated]\n"); return }
                    sb.append(indent).append("field $field fixed32 = ").append(hex(bytes, off, 4)).append('\n')
                    off += 4
                }
                2 -> {
                    val (len, n) = readVarint(bytes, off) ?: run {
                        sb.append(indent).append("field $field len-delim [malformed length]\n"); return
                    }
                    if (n + len > bytes.size || len < 0) {
                        sb.append(indent).append("field $field len-delim [length $len past end]\n"); return
                    }
                    val inner = bytes.copyOfRange(n.toInt(), (n + len).toInt())
                    val asStr = inner.tryUtf8()
                    val looksLikeProto = depth < 4 && len > 0 && looksLikeProto(inner)
                    when {
                        looksLikeProto -> {
                            sb.append(indent).append("field $field submessage (${inner.size}B):\n")
                            dumpInto(sb, inner, depth + 1)
                        }
                        asStr != null -> {
                            sb.append(indent).append("field $field string = \"$asStr\"\n")
                        }
                        else -> {
                            sb.append(indent).append("field $field bytes (${inner.size}B) = ").append(hex(bytes, n.toInt(), inner.size)).append('\n')
                        }
                    }
                    off = (n + len).toInt()
                }
                else -> {
                    sb.append(indent).append("field $field wire $wire [unsupported, stopping]\n"); return
                }
            }
        }
    }

    // Heuristic: could this byte buffer be a valid protobuf message? We
    // walk it tag-by-tag and bail on the first malformed structure. Used
    // to decide whether to recurse-pretty-print or to print as bytes.
    private fun looksLikeProto(bytes: ByteArray): Boolean {
        var off = 0
        while (off < bytes.size) {
            val (tag, next) = readVarint(bytes, off) ?: return false
            off = next
            val wire = (tag and 7).toInt()
            val field = (tag ushr 3).toInt()
            if (field <= 0 || field > 536_870_911) return false
            when (wire) {
                0 -> { val (_, n) = readVarint(bytes, off) ?: return false; off = n }
                1 -> { if (off + 8 > bytes.size) return false; off += 8 }
                5 -> { if (off + 4 > bytes.size) return false; off += 4 }
                2 -> {
                    val (len, n) = readVarint(bytes, off) ?: return false
                    if (len < 0 || n + len > bytes.size) return false
                    off = (n + len).toInt()
                }
                else -> return false
            }
        }
        return true
    }

    private fun ByteArray.tryUtf8(): String? {
        if (isEmpty()) return null
        // ASCII-only "looks like a string" — most polo strings (service_name,
        // client_name, server_name, model) are pure ASCII.
        if (any { (it.toInt() and 0xFF) < 0x20 || (it.toInt() and 0xFF) > 0x7E }) return null
        return String(this, Charsets.US_ASCII)
    }

    private fun hex(b: ByteArray, off: Int, len: Int): String = buildString(len * 3) {
        for (i in 0 until len) {
            val v = b[off + i].toInt() and 0xFF
            append(HEX[v ushr 4]); append(HEX[v and 0x0F]); append(' ')
        }
    }.trimEnd()

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L; var shift = 0; var i = start
        val limit = minOf(bytes.size, start + 10)
        while (i < limit) {
            val b = bytes[i].toInt() and 0xFF; i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
