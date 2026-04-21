package io.github.ddagunts.screencast.cast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastCertPinStoreTest {

    private val store: CastCertPinStore
        get() = CastCertPinStore(ApplicationProvider.getApplicationContext<Context>())

    @Test fun `get returns null for a never-seen host`() {
        assertNull(store.get("192.168.5.99"))
    }

    @Test fun `pin then get roundtrips the fingerprint`() {
        store.pin("192.168.5.131", "deadbeef")
        assertEquals("deadbeef", store.get("192.168.5.131"))
    }

    @Test fun `pin overwrites a previous value for the same host`() {
        store.pin("h", "a")
        store.pin("h", "b")
        assertEquals("b", store.get("h"))
    }

    @Test fun `pins are isolated per host`() {
        store.pin("a", "fp1")
        store.pin("b", "fp2")
        assertEquals("fp1", store.get("a"))
        assertEquals("fp2", store.get("b"))
    }

    @Test fun `pinnedHosts lists every pinned host`() {
        val s = store
        s.pin("a", "fp1")
        s.pin("b", "fp2")
        val hosts = s.pinnedHosts()
        assertTrue(hosts.contains("a"))
        assertTrue(hosts.contains("b"))
    }

    @Test fun `forget removes only the target host`() {
        val s = store
        s.pin("a", "fp1")
        s.pin("b", "fp2")
        s.forget("a")
        assertNull(s.get("a"))
        assertEquals("fp2", s.get("b"))
        assertTrue(s.pinnedHosts().containsAll(setOf("b")))
        assertTrue(!s.pinnedHosts().contains("a"))
    }

    @Test fun `forget is safe on never-pinned host`() {
        store.forget("never-seen")
    }
}
