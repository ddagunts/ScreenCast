package io.github.ddagunts.screencast.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamConfigStoreTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val store get() = StreamConfigStore(ctx)

    @Test fun `load returns defaults before the first save`() {
        assertEquals(StreamConfig(), store.load())
    }

    @Test fun `save then load roundtrips every field`() {
        val cfg = StreamConfig(
            segmentDurationSec = 3.5,
            windowSize = 8,
            liveEdgeFactor = 2.0,
            resolution = Resolution.P1080,
            syncStart = true,
            syncIntervalSec = 25,
            syncDriftThresholdMs = 40,
        )
        store.save(cfg)
        assertEquals(cfg, store.load())
    }

    @Test fun `save strips the legacy fine_volume_step pref`() {
        ctx.getSharedPreferences("stream_config", 0).edit()
            .putBoolean("fine_volume_step", true)
            .apply()
        store.save(StreamConfig())
        assertFalse(
            "legacy fine_volume_step must be stripped",
            ctx.getSharedPreferences("stream_config", 0).contains("fine_volume_step"),
        )
    }

    @Test fun `load recovers from a bogus resolution string`() {
        ctx.getSharedPreferences("stream_config", 0).edit()
            .putString("resolution", "P4K_NOT_REAL")
            .apply()
        assertEquals(Resolution.P720, store.load().resolution)
    }

    @Test fun `load tolerates partial prior state`() {
        // Only segment duration persisted — the rest must come from defaults.
        ctx.getSharedPreferences("stream_config", 0).edit()
            .putFloat("segment_duration", 3.0f)
            .apply()
        val cfg = store.load()
        assertEquals(3.0, cfg.segmentDurationSec, 0.001)
        assertEquals(StreamConfig().windowSize, cfg.windowSize)
        assertEquals(StreamConfig().syncDriftThresholdMs, cfg.syncDriftThresholdMs)
    }
}
