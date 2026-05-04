package io.github.ddagunts.screencast.androidtv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

class AndroidTvPairingHashTest {

    @Test fun `extractNonce drops the 2-digit prefix`() {
        // "F01234" → drop "F0", decode "1234"
        assertArrayEquals(
            byteArrayOf(0x12, 0x34),
            AndroidTvPairingHash.extractNonce("F01234"),
        )
    }

    @Test fun `extractNonce odd length nonce is zero-padded`() {
        // "F0" + "ABC" — odd length 3, pad to "0ABC"
        assertArrayEquals(
            byteArrayOf(0x0A, 0xBC.toByte()),
            AndroidTvPairingHash.extractNonce("F0ABC"),
        )
    }

    @Test fun `extractNonce rejects non-hex input`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvPairingHash.extractNonce("F0ZZZZ")
        }
    }

    @Test fun `extractNonce rejects too-short code`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvPairingHash.extractNonce("F")
        }
    }

    @Test fun `unsigned bytes strips leading sign zero from RSA-style modulus`() {
        // Top bit set — Java adds a leading 0x00 sign byte; we expect it
        // gone in the unsigned representation.
        val v = BigInteger("9876543210ABCDEF", 16)
        with(AndroidTvPairingHash) {
            val unsigned = v.toUnsignedBytes()
            // Reference: hex of v → 9876543210ABCDEF → 8 bytes, no leading zero.
            assertEquals(8, unsigned.size)
            assertEquals(0x98.toByte(), unsigned[0])
        }
    }

    @Test fun `unsigned bytes preserves multi-byte value with clear top bit`() {
        // Top bit clear — Java does not add a sign byte; we should keep it.
        val v = BigInteger("0123456789", 16)
        with(AndroidTvPairingHash) {
            val unsigned = v.toUnsignedBytes()
            assertEquals(5, unsigned.size)
            assertEquals(0x01.toByte(), unsigned[0])
        }
    }

    @Test fun `unsigned bytes for zero is single 0x00`() {
        with(AndroidTvPairingHash) {
            assertArrayEquals(byteArrayOf(0x00), BigInteger.ZERO.toUnsignedBytes())
        }
    }

    @Test fun `compute matches an explicit reference computation`() {
        // Build a deterministic-ish hash by computing the same SHA-256 by
        // hand from known integer inputs, then asserting AndroidTvPairingHash
        // produces the same digest. This is the load-bearing correctness
        // test — gets us coverage without needing a real TV trace.
        val clientN = BigInteger("9F" + "00".repeat(255), 16)  // RSA-2048-ish
        val clientE = BigInteger("010001", 16)
        val serverN = BigInteger("AB" + "11".repeat(255), 16)
        val serverE = BigInteger("010001", 16)
        val code = "F01234"

        val expected = MessageDigest.getInstance("SHA-256").run {
            update(unsignedHexToBytes("9F" + "00".repeat(255)))
            update(unsignedHexToBytes("010001"))
            update(unsignedHexToBytes("AB" + "11".repeat(255)))
            update(unsignedHexToBytes("010001"))
            update(byteArrayOf(0x12, 0x34))
            digest()
        }

        val client = mockRsaPublic(clientN, clientE)
        val server = mockRsaPublic(serverN, serverE)
        val actual = AndroidTvPairingHash.compute(client, server, code)
        assertArrayEquals(expected, actual)
    }

    @Test fun `compute is sensitive to the code nonce`() {
        val (client, server) = realRsaKeysForTesting()
        val a = AndroidTvPairingHash.compute(client, server, "F01234")
        val b = AndroidTvPairingHash.compute(client, server, "F01235")
        // Different nonce must produce a different digest.
        assertEquals(false, a.contentEquals(b))
    }

    @Test fun `compute treats client and server asymmetrically`() {
        val (client, server) = realRsaKeysForTesting()
        val ab = AndroidTvPairingHash.compute(client, server, "F01234")
        val ba = AndroidTvPairingHash.compute(server, client, "F01234")
        // Swapping client/server must change the digest — the hash is
        // ordered, not commutative.
        assertEquals(false, ab.contentEquals(ba))
    }

    private fun realRsaKeysForTesting(): Pair<RSAPublicKey, RSAPublicKey> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val a = kpg.generateKeyPair().public as RSAPublicKey
        val b = kpg.generateKeyPair().public as RSAPublicKey
        return a to b
    }

    private fun mockRsaPublic(n: BigInteger, e: BigInteger): RSAPublicKey =
        object : RSAPublicKey {
            override fun getModulus(): BigInteger = n
            override fun getPublicExponent(): BigInteger = e
            override fun getAlgorithm(): String = "RSA"
            override fun getFormat(): String = "X.509"
            override fun getEncoded(): ByteArray = ByteArray(0)
        }

    private fun unsignedHexToBytes(s: String): ByteArray {
        val padded = if (s.length % 2 == 1) "0$s" else s
        val out = ByteArray(padded.length / 2)
        for (i in out.indices) {
            out[i] = (Character.digit(padded[2 * i], 16) shl 4 or
                Character.digit(padded[2 * i + 1], 16)).toByte()
        }
        return out
    }
}
