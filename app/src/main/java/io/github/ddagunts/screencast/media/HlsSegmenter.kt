package io.github.ddagunts.screencast.media

import io.github.ddagunts.screencast.util.logI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepts encoded H.264 NAL samples, segments at IDR boundaries, and exposes a rolling
 * HLS playlist + in-memory .ts segments.
 */
class HlsSegmenter(
    private val targetDurationSec: Double = 2.0,
    private val windowSize: Int = 30,
    private val liveEdgeFactor: Double = 1.5,
    audioSampleRate: Int = 44100,
    audioChannelCount: Int = 2,
) : VideoEncoder.Sink, AudioEncoder.Sink {

    private val muxer = TsMuxer(audioSampleRate, audioChannelCount)
    private val segments = ConcurrentHashMap<Int, ByteArray>()
    private val durations = ConcurrentHashMap<Int, Double>()
    private val order = ArrayDeque<Int>()
    private val seqGen = AtomicInteger(0)
    @Volatile private var currentStartUs: Long = -1
    @Volatile private var currentEndUs: Long = -1
    @Volatile private var ptsBaseUs: Long = -1
    @Volatile private var hasFormat = false
    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<Pair<ByteArray, Long>>()

    override fun onFormat(sps: ByteArray, pps: ByteArray) {
        muxer.setSpsPps(sps, pps)
        hasFormat = true
        logI("segmenter got SPS/PPS")
    }

    override fun onSample(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
        if (!hasFormat) return
        // MediaCodec emits PTS values in the surface's own timeline (typically
        // SystemClock.uptimeNanos/1000), not starting at 0. Rebase so EXTINF
        // durations are measured from the first sample, not from boot.
        if (ptsBaseUs < 0) ptsBaseUs = ptsUs
        val pts = ptsUs - ptsBaseUs

        if (isKeyFrame) {
            if (currentStartUs >= 0 &&
                (pts - currentStartUs) / 1_000_000.0 >= targetDurationSec) {
                flushCurrentSegment()
            }
            if (currentStartUs < 0) {
                currentStartUs = pts
                muxer.writeTablesAndKeyframe(nalUnits, pts)
            } else {
                muxer.writeFrame(nalUnits, pts, true)
            }
        } else {
            if (currentStartUs < 0) return   // still waiting for first IDR
            muxer.writeFrame(nalUnits, pts, false)
        }
        currentEndUs = pts
        drainAudioUpTo(pts)
    }

    override fun onAacFrame(data: ByteArray, ptsUs: Long) {
        synchronized(audioLock) { pendingAudio.addLast(data to ptsUs) }
    }

    private fun drainAudioUpTo(videoPts: Long) {
        synchronized(audioLock) {
            while (pendingAudio.isNotEmpty() && pendingAudio.first().second <= videoPts) {
                val (frame, aPts) = pendingAudio.removeFirst()
                muxer.writeAudioFrame(frame, aPts)
            }
        }
    }

    private fun flushCurrentSegment() {
        val bytes = muxer.drainTo()
        val durSec = (currentEndUs - currentStartUs) / 1_000_000.0
        currentStartUs = -1
        if (bytes.isEmpty()) return
        val seq = seqGen.getAndIncrement()
        segments[seq] = bytes
        durations[seq] = durSec
        synchronized(order) {
            order.addLast(seq)
            // Evict expired segments lazily: keep double the window in memory so a client
            // that just fetched the previous playlist can still retrieve the URLs it parsed.
            val keep = windowSize * 2
            while (order.size > keep) {
                val old = order.removeFirst()
                segments.remove(old); durations.remove(old)
            }
        }
        logI("segment $seq ready (${bytes.size / 1024} KB, ~${"%.2f".format(durSec)}s)")
    }

    fun getSegment(seq: Int): ByteArray? = segments[seq]

    fun playlist(): String {
        val snapshot = synchronized(order) { order.toList() }.takeLast(windowSize)
        val first = snapshot.firstOrNull() ?: 0
        val maxDur = snapshot.maxOfOrNull { durations[it] ?: targetDurationSec } ?: targetDurationSec
        val totalDur = snapshot.sumOf { durations[it] ?: targetDurationSec }
        // Pull the player close to the live edge. Without this tag, hls.js/shaka default to
        // ~3 × targetDuration from end, which is the main source of HLS latency.
        val liveOffset = (targetDurationSec * liveEdgeFactor)
            .coerceAtMost(totalDur - targetDurationSec / 2.0)
            .coerceAtLeast(0.0)
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:${Math.ceil(maxDur).toInt().coerceAtLeast(1)}\n")
            append("#EXT-X-MEDIA-SEQUENCE:$first\n")
            if (liveOffset > 0) append("#EXT-X-START:TIME-OFFSET=-${"%.3f".format(liveOffset)},PRECISE=YES\n")
            for (seq in snapshot) {
                val d = durations[seq] ?: targetDurationSec
                append("#EXTINF:${"%.3f".format(d)},\n")
                append("seg-$seq.ts\n")
            }
        }
    }

    fun firstReadySeq(): Int? = synchronized(order) { order.firstOrNull() }
    fun readySegmentCount(): Int = synchronized(order) { order.size }

    fun reset() {
        segments.clear(); order.clear()
        currentStartUs = -1; currentEndUs = -1
    }
}
