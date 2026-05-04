package io.github.ddagunts.screencast.androidtv

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

// Persists the per-app ATV client certificate + private key plus per-host
// server-cert SHA-256 fingerprint pins.
//
// Storage layout:
//   1. EncryptedFile "atv_client.bin" — the cert (DER) and PKCS#8 private
//      key, length-prefixed and concatenated. AES-256-GCM master key is
//      derived from the Android Keystore (TEE/StrongBox where available),
//      so a non-rooted attacker reading raw filesystem bytes can't decrypt.
//   2. Plain SharedPreferences "atv_server_pins" — per-host pinned server
//      cert SHA-256 fingerprints. These are public information; encrypting
//      them buys nothing and matches the existing Cast pin store posture.
//
// The cert/key file is generated lazily on the first call to
// `getOrCreateClient()` and never rotated. Forgetting (e.g. user wipes app
// data) requires re-pairing every TV; that's a feature, not a bug — see
// AndroidTvPairingChannel.
class AndroidTvCertStore internal constructor(
    private val context: Context,
    // Test seam: production uses an EncryptedFile-backed store derived from
    // the Android Keystore master key, but Robolectric ships no
    // AndroidKeyStore JCE provider so unit tests inject a plain-file
    // backend. The interface is small (read/write/delete) — the
    // encryption-vs-plaintext concern is orthogonal to cert lifecycle.
    private val backend: CertBlobBackend = encryptedFileBackend(context),
) {

    constructor(context: Context) : this(context, encryptedFileBackend(context))

    interface CertBlobBackend {
        fun read(): ByteArray?
        fun write(data: ByteArray)
        fun delete()
    }

    private val pinPrefs = context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreateClient(): AndroidTvCertFactory.Material {
        readClient()?.let { return it }
        val fresh = AndroidTvCertFactory.generate()
        writeClient(fresh)
        logI("generated new ATV client cert (subject=${fresh.cert.subjectX500Principal})")
        return fresh
    }

    // Force a fresh client cert. Useful only after a complete TV reset —
    // every previously paired TV will reject us until re-paired.
    @Synchronized
    fun rotateClient(): AndroidTvCertFactory.Material {
        backend.delete()
        return getOrCreateClient()
    }

    private fun readClient(): AndroidTvCertFactory.Material? {
        val raw = backend.read() ?: return null
        return runCatching { decodeBlob(raw) }
            .onFailure {
                // Corrupt or unreadable (master key rotated? OS upgrade?).
                // Drop it; getOrCreateClient() will regenerate. Pairings
                // are toast either way — don't loop trying to read it.
                logW("could not decode ATV client cert blob, regenerating: ${it.message}")
                backend.delete()
            }.getOrNull()
    }

    private fun writeClient(material: AndroidTvCertFactory.Material) {
        backend.write(encodeBlob(material))
    }

    // Layout: [4-byte BE certLen][cert DER][4-byte BE keyLen][key PKCS#8].
    // No magic header — the EncryptedFile envelope already authenticates
    // everything it contains, so corruption shows up as a decrypt failure
    // before we ever see the layout.
    private fun encodeBlob(m: AndroidTvCertFactory.Material): ByteArray {
        val certDer = m.cert.encoded
        val keyDer = m.keyPair.private.encoded
        val out = java.io.ByteArrayOutputStream()
        writeIntBE(out, certDer.size); out.write(certDer)
        writeIntBE(out, keyDer.size); out.write(keyDer)
        return out.toByteArray()
    }

    private fun decodeBlob(raw: ByteArray): AndroidTvCertFactory.Material {
        var off = 0
        fun readSlice(): ByteArray {
            require(off + 4 <= raw.size) { "truncated header at $off" }
            val len = ((raw[off].toInt() and 0xFF) shl 24) or
                ((raw[off + 1].toInt() and 0xFF) shl 16) or
                ((raw[off + 2].toInt() and 0xFF) shl 8) or
                (raw[off + 3].toInt() and 0xFF)
            off += 4
            require(len in 0..raw.size && off + len <= raw.size) { "bad slice len $len" }
            val out = raw.copyOfRange(off, off + len)
            off += len
            return out
        }
        val certBytes = readSlice()
        val keyBytes = readSlice()
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val priv: PrivateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        return AndroidTvCertFactory.Material(cert, KeyPair(cert.publicKey, priv))
    }

    private fun writeIntBE(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    // ---- Server cert pin store (per-host, plain SharedPreferences) ----

    fun getServerPin(host: String): String? = pinPrefs.getString(host, null)

    fun pinServer(host: String, fingerprintHex: String) {
        pinPrefs.edit().putString(host, fingerprintHex).apply()
    }

    fun pinnedHosts(): Set<String> = pinPrefs.all.keys.toSet()

    fun forget(host: String) {
        pinPrefs.edit().remove(host).apply()
    }

    companion object {
        private const val CLIENT_FILE = "atv_client.bin"
        private const val PIN_PREFS = "atv_server_pins"

        // Production backend: AES-256-GCM-encrypted file under filesDir,
        // master key from the Android Keystore (TEE/StrongBox where
        // available). EncryptedFile refuses to overwrite, so write() does
        // a delete-then-create dance internally.
        private fun encryptedFileBackend(context: Context): CertBlobBackend {
            val file = File(context.filesDir, CLIENT_FILE)
            val masterKey = lazy {
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            }
            fun open() = EncryptedFile.Builder(
                context, file, masterKey.value, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            return object : CertBlobBackend {
                override fun read(): ByteArray? = if (!file.exists()) null
                    else runCatching { open().openFileInput().use { it.readBytes() } }.getOrNull()
                override fun write(data: ByteArray) {
                    runCatching { file.delete() }
                    open().openFileOutput().use { it.write(data) }
                }
                override fun delete() { runCatching { file.delete() } }
            }
        }
    }
}
