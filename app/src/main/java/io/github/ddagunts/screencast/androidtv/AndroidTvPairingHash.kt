package io.github.ddagunts.screencast.androidtv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

// Computes the SECRET payload for the polo pairing handshake.
//
// Algorithm (matches tronikos/androidtvremote2 and Google Home — the only
// published clients that pair successfully against Android TV firmware):
//
//   alpha = SHA-256(
//       clientCert.modulus    (unsigned big-endian, no leading-zero pad)
//    || clientCert.publicExponent
//    || serverCert.modulus
//    || serverCert.publicExponent
//    || nonce                 (the last 4 hex digits of the 6-digit code)
//   )
//
// Two subtleties the algorithm hinges on:
//
// 1. **Unsigned byte encoding.** Java's BigInteger.toByteArray() prepends a
//    sign byte (0x00) when the high bit of the magnitude is set — RSA-2048
//    moduli always trigger this. The reference implementations expect the
//    *minimal unsigned* representation, so we strip that one leading zero.
//    We don't pad up to a fixed width either; tronikos converts via `hex()`
//    which produces a minimal-length string.
//
// 2. **Code parsing.** The 6-digit code shown on the TV consists of a
//    2-digit checksum prefix (which we ignore) and a 4-digit hex nonce.
//    Hex strings of odd length get a leading "0" pad before decoding —
//    mirrors `bytes.fromhex(("0" + s) if len(s) % 2 else s)` in the
//    reference. For a well-formed 4-digit code the pad is a no-op.
object AndroidTvPairingHash {

    fun compute(client: X509Certificate, server: X509Certificate, code: String): ByteArray =
        compute(
            clientPublic = client.publicKey as RSAPublicKey,
            serverPublic = server.publicKey as RSAPublicKey,
            code = code,
        )

    fun compute(clientPublic: RSAPublicKey, serverPublic: RSAPublicKey, code: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(clientPublic.modulus.toUnsignedBytes())
        md.update(clientPublic.publicExponent.toUnsignedBytes())
        md.update(serverPublic.modulus.toUnsignedBytes())
        md.update(serverPublic.publicExponent.toUnsignedBytes())
        md.update(extractNonce(code))
        return md.digest()
    }

    // Strip leading 2 hex digits (checksum) and decode the rest as the
    // nonce bytes the SHA-256 input expects. Throws on non-hex input so
    // typos surface as a paired exception rather than a wrong hash that
    // silently fails later in the SECRET_ACK round-trip.
    internal fun extractNonce(code: String): ByteArray {
        require(code.length >= 2) { "pairing code too short: '$code'" }
        val hex = code.substring(2)
        return hexToBytes(hex)
    }

    internal fun hexToBytes(s: String): ByteArray {
        val padded = if (s.length % 2 == 1) "0$s" else s
        require(padded.all { it.isHexDigit() }) { "non-hex character in '$s'" }
        val out = ByteArray(padded.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(padded[2 * i], 16)
            val lo = Character.digit(padded[2 * i + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // Java BigInteger.toByteArray() returns two's complement big-endian; for
    // RSA moduli (always positive, top bit set) that means a leading 0x00
    // sign byte. The reference implementation expects minimal unsigned
    // bytes — drop that one leading zero.
    internal fun BigInteger.toUnsignedBytes(): ByteArray {
        val raw = toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
