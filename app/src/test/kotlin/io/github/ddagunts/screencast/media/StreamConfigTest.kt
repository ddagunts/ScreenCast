package io.github.ddagunts.screencast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigTest {

    @Test fun `keyframe interval rounds up fractional segment durations`() {
        assertEquals(3, StreamConfig(segmentDurationSec = 2.5).keyframeIntervalSec)
        assertEquals(2, StreamConfig(segmentDurationSec = 2.0).keyframeIntervalSec)
        assertEquals(4, StreamConfig(segmentDurationSec = 3.1).keyframeIntervalSec)
    }

    @Test fun `keyframe interval is at least 1 second`() {
        assertEquals(1, StreamConfig(segmentDurationSec = 0.1).keyframeIntervalSec)
        assertEquals(1, StreamConfig(segmentDurationSec = 0.999).keyframeIntervalSec)
    }

    @Test fun `seed segment count never exceeds window size`() {
        assertEquals(3, StreamConfig(windowSize = 10).seedSegmentCount)
        assertEquals(3, StreamConfig(windowSize = 4).seedSegmentCount)
        assertEquals(2, StreamConfig(windowSize = 2).seedSegmentCount)
    }

    @Test fun `estimated latency is segment duration times live edge factor`() {
        val c = StreamConfig(segmentDurationSec = 2.0, liveEdgeFactor = 1.5)
        assertEquals(3.0, c.estimatedLatencySec, 0.001)
    }

    @Test fun `sync start defaults to on`() {
        assertEquals(true, StreamConfig().syncStart)
    }

    @Test fun `sync interval default is 10s and drift default is 30ms`() {
        assertEquals(10, StreamConfig().syncIntervalSec)
        assertEquals(30, StreamConfig().syncDriftThresholdMs)
    }

    @Test fun `sync interval bounds span from 5s to 60s`() {
        assertTrue(StreamConfig.MIN_SYNC_INTERVAL_SEC < StreamConfig.MAX_SYNC_INTERVAL_SEC)
        assertEquals(5, StreamConfig.MIN_SYNC_INTERVAL_SEC)
        assertEquals(60, StreamConfig.MAX_SYNC_INTERVAL_SEC)
    }

    @Test fun `sync drift bounds span from 5ms to 500ms`() {
        assertTrue(StreamConfig.MIN_SYNC_DRIFT_MS < StreamConfig.MAX_SYNC_DRIFT_MS)
        assertEquals(5, StreamConfig.MIN_SYNC_DRIFT_MS)
        assertEquals(500, StreamConfig.MAX_SYNC_DRIFT_MS)
    }

    @Test fun `default resolution is 720p`() {
        assertEquals(Resolution.P720, StreamConfig().resolution)
    }

    @Test fun `resolution bitrates are strictly increasing with size`() {
        val bitrates = Resolution.entries.map { it.bitrate }
        assertEquals(bitrates.sorted(), bitrates)
    }
}
