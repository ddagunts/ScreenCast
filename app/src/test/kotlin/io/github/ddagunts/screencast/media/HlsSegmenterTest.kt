package io.github.ddagunts.screencast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Full segmenter coverage would need MediaCodec-shaped NAL fixtures (SPS/PPS
// plus IDR-bounded samples) which is expensive to stand up. These tests pin
// the playlist-format contract and the accessor surface that HttpStreamServer
// and CastForegroundService both depend on.
class HlsSegmenterTest {

    @Test fun `empty segmenter emits a well-formed HLS header`() {
        val seg = HlsSegmenter(targetDurationSec = 2.0, windowSize = 6, liveEdgeFactor = 1.5)
        val pl = seg.playlist()
        assertTrue("playlist must start with #EXTM3U marker", pl.startsWith("#EXTM3U\n"))
        assertTrue(pl.contains("#EXT-X-VERSION:3"))
        assertTrue(pl.contains("#EXT-X-TARGETDURATION:"))
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:0"))
    }

    @Test fun `empty segmenter reports zero ready segments`() {
        val seg = HlsSegmenter()
        assertEquals(0, seg.readySegmentCount())
        assertNull(seg.firstReadySeq())
    }

    @Test fun `getSegment returns null for never-produced sequence`() {
        assertNull(HlsSegmenter().getSegment(0))
        assertNull(HlsSegmenter().getSegment(9999))
    }

    @Test fun `reset clears state from a fresh segmenter without crashing`() {
        val seg = HlsSegmenter()
        seg.reset()
        assertEquals(0, seg.readySegmentCount())
    }

    @Test fun `empty-playlist TARGETDURATION defaults to at least 1 second`() {
        val pl = HlsSegmenter(targetDurationSec = 0.5).playlist()
        val line = pl.lines().first { it.startsWith("#EXT-X-TARGETDURATION:") }
        val dur = line.substringAfter(":").trim().toInt()
        assertTrue("TARGETDURATION must be >= 1, was $dur", dur >= 1)
    }
}
