package io.github.ddagunts.screencast.androidtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTvCertStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // In-memory backend matches CertBlobBackend's contract — sufficient for
    // verifying the encode/decode + lifecycle. EncryptedFile itself is
    // covered on real devices; Robolectric ships no AndroidKeyStore JCE.
    private class MemoryBackend : AndroidTvCertStore.CertBlobBackend {
        var data: ByteArray? = null
        override fun read(): ByteArray? = data
        override fun write(data: ByteArray) { this.data = data }
        override fun delete() { data = null }
    }

    private fun store(backend: AndroidTvCertStore.CertBlobBackend = MemoryBackend()) =
        AndroidTvCertStore(ctx, backend)

    @Test fun `pinServer then getServerPin roundtrips`() {
        val s = store()
        s.pinServer("192.168.5.40", "deadbeef")
        assertEquals("deadbeef", s.getServerPin("192.168.5.40"))
    }

    @Test fun `getServerPin returns null for unseen host`() {
        assertNull(store().getServerPin("never-seen"))
    }

    @Test fun `forget removes only the target host pin`() {
        val s = store()
        s.pinServer("a", "fpA")
        s.pinServer("b", "fpB")
        s.forget("a")
        assertNull(s.getServerPin("a"))
        assertEquals("fpB", s.getServerPin("b"))
    }

    @Test fun `pinnedHosts lists every pinned host`() {
        val s = store()
        s.pinServer("a", "x")
        s.pinServer("b", "y")
        assertTrue(s.pinnedHosts().containsAll(setOf("a", "b")))
    }

    @Test fun `getOrCreateClient generates once and reuses across calls`() {
        val s = store()
        val first = s.getOrCreateClient()
        val second = s.getOrCreateClient()
        assertEquals(first.cert.serialNumber, second.cert.serialNumber)
        assertEquals(first.cert.subjectX500Principal, second.cert.subjectX500Principal)
    }

    @Test fun `getOrCreateClient persists across store instances sharing a backend`() {
        val backend = MemoryBackend()
        val sn = store(backend).getOrCreateClient().cert.serialNumber
        val again = store(backend).getOrCreateClient().cert.serialNumber
        assertEquals(sn, again)
    }

    @Test fun `rotateClient produces a different cert`() {
        val s = store()
        val before = s.getOrCreateClient()
        val after = s.rotateClient()
        assertNotEquals(before.cert.serialNumber, after.cert.serialNumber)
    }

    @Test fun `corrupt blob is dropped and regenerated`() {
        val backend = MemoryBackend().apply { data = byteArrayOf(0x00, 0x01, 0x02) }
        val s = store(backend)
        // Should not throw — corrupt data is wiped + a fresh cert issued.
        val m = s.getOrCreateClient()
        assertNotEquals(0, m.cert.serialNumber.signum().toLong())
    }
}
