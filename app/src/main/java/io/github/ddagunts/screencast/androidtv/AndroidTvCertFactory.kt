package io.github.ddagunts.screencast.androidtv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

// Generates a self-signed RSA-2048 X.509 v3 cert used as the client cert
// during ATV pairing and on the mTLS remote control channel that follows.
//
// We hand-roll the TBSCertificate DER rather than pull in BouncyCastle
// (~1.2 MB APK cost for one cert per app install). X500Principal and
// PublicKey.getEncoded() give us the two heaviest substructures (Name and
// SubjectPublicKeyInfo) already DER-encoded, so the TBS body is short.
//
// One cert is generated per app install and reused for every paired TV —
// matches what the Google Home app and tronikos' androidtvremote2 do. The
// TV stores the client cert's SubjectPublicKeyInfo in its paired-devices
// list, keyed by SPKI; reusing it across TVs is fine.
object AndroidTvCertFactory {

    data class Material(val cert: X509Certificate, val keyPair: KeyPair)

    // RFC 5280 §4.1.1.2: signatureAlgorithm = sha256WithRSAEncryption.
    // OID 1.2.840.113549.1.1.11 — encoded once, reused in TBS + outer cert.
    private val SHA256_WITH_RSA_OID = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
        0x0D, 0x01, 0x01, 0x0B,
    )

    fun generate(now: Long = System.currentTimeMillis()): Material {
        val kpg = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val keyPair = kpg.generateKeyPair()

        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        // 5-year validity matches Google Home / tronikos. The TV doesn't
        // check expiration but a reasonable notAfter avoids surprises on
        // any peer that does.
        val notAfter = Date(now + 5L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger(64, SecureRandom()).abs()
        val tag = SecureRandom().nextLong().toString(16).padStart(16, '0').take(8)
        val name = X500Principal("CN=ScreenCast-$tag,O=ScreenCast,OU=AndroidTvRemote")

        val tbs = buildTbs(serial, name, notBefore, notAfter, keyPair.public.encoded)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = derSequence(
            tbs,
            algorithmIdentifier(),
            derBitString(signature),
        )
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return Material(cert, keyPair)
    }

    // TBSCertificate ::= SEQUENCE { version, serial, sigAlg, issuer, validity,
    //   subject, subjectPublicKeyInfo, ...optional extensions }
    // We omit issuerUniqueID/subjectUniqueID/extensions — the TV never
    // inspects them. Self-signed v3 cert without extensions is well-formed
    // per RFC 5280; a more thorough peer (browsers etc.) would reject this,
    // but Android TV firmware accepts it.
    private fun buildTbs(
        serial: BigInteger,
        name: X500Principal,
        notBefore: Date,
        notAfter: Date,
        spki: ByteArray,
    ): ByteArray {
        val nameDer = name.encoded
        return derSequence(
            // [0] EXPLICIT version v3 (INTEGER 2)
            derTagged(0, derInteger(BigInteger.valueOf(2))),
            derInteger(serial),
            algorithmIdentifier(),
            nameDer,
            derSequence(
                derUtcOrGeneralizedTime(notBefore),
                derUtcOrGeneralizedTime(notAfter),
            ),
            nameDer,
            spki,
        )
    }

    // AlgorithmIdentifier ::= SEQUENCE { OID, parameters NULL }. For RSA
    // with SHA-256 the parameters slot is required to be NULL (RFC 4055).
    private fun algorithmIdentifier(): ByteArray =
        derSequence(SHA256_WITH_RSA_OID, byteArrayOf(0x05, 0x00))

    // ---- DER primitives -----------------------------------------------------

    private fun derSequence(vararg parts: ByteArray): ByteArray =
        derTlv(0x30, concat(*parts))

    private fun derInteger(v: BigInteger): ByteArray = derTlv(0x02, v.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray {
        // BIT STRING with no unused bits (signature is byte-aligned).
        val out = ByteArrayOutputStream(bytes.size + 1)
        out.write(0)
        out.write(bytes)
        return derTlv(0x03, out.toByteArray())
    }

    private fun derTagged(tag: Int, content: ByteArray): ByteArray =
        // [0] EXPLICIT context-specific constructed: 0xA0 | tagNumber.
        derTlv(0xA0 or tag, content)

    // RFC 5280 says use UTCTime for years 1950..2049, GeneralizedTime
    // otherwise. We always pick the right one based on the year so that
    // certs generated near the rollover (e.g. notAfter > 2049) parse on
    // strict peers.
    private fun derUtcOrGeneralizedTime(d: Date): ByteArray {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = d }
        val year = cal.get(java.util.Calendar.YEAR)
        return if (year in 1950..2049) {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            derTlv(0x17, fmt.format(d).toByteArray(Charsets.US_ASCII))
        } else {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            derTlv(0x18, fmt.format(d).toByteArray(Charsets.US_ASCII))
        }
    }

    private fun derTlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        // DER length encoding: < 128 bytes is a single byte; otherwise
        // 0x80 | numBytes followed by big-endian length bytes.
        if (content.size < 128) {
            out.write(content.size)
        } else {
            val lenBytes = encodeLength(content.size)
            out.write(0x80 or lenBytes.size)
            out.write(lenBytes)
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun encodeLength(n: Int): ByteArray {
        var v = n
        val bytes = mutableListOf<Byte>()
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v ushr 8 }
        return bytes.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) { System.arraycopy(p, 0, out, off, p.size); off += p.size }
        return out
    }
}
