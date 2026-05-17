package io.github.ddagunts.screencast.webrtc

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

// PCM source feeding libwebrtc's audio pipeline via AudioBufferCallback.
// Captures device playback (USAGE_MEDIA + USAGE_GAME) through the same
// MediaProjection the video capturer uses — no second consent prompt, no
// separate FGS.
//
// The earlier version polled AudioRecord with READ_NON_BLOCKING directly from
// inside onBuffer(). That coupled libwebrtc's exact-10 ms pull cadence to
// AudioRecord's HAL-driven delivery cadence — any drift between the two
// clocks made the non-blocking read return short, which we tail-padded with
// silence. The user-visible result was severely choppy audio (intermittent
// 10 ms silence frames every few hundred ms) or apparent total silence
// during the startup ring-fill window.
//
// This version decouples the two clocks with a small ring:
//   • A dedicated reader thread does *blocking* AudioRecord.read in 10 ms
//     chunks and enqueues them. The thread iterates at the HAL clock rate.
//   • onBuffer() pops one chunk per call from the ring. It never blocks; if
//     the ring is empty (true underrun, should be rare past startup) it
//     ships silence and increments a counter.
//
// In steady state the ring holds ~1 chunk. The 5-deep capacity (~50 ms) is
// the slack we have for HAL/scheduler jitter before underruns or drops
// appear in the diagnostics log.
class WebRtcAudioCapture : JavaAudioDeviceModule.AudioBufferCallback {

    @Volatile private var audioRecord: AudioRecord? = null

    // 10 ms PCM chunk size = 1920 B at 48 kHz / stereo / 16-bit.
    private val chunkBytes = WEBRTC_AUDIO_SAMPLE_RATE / 100 * WEBRTC_AUDIO_CHANNELS * 2

    // Capacity 5 = ~50 ms of slack. Bigger than typical HAL jitter
    // (10–20 ms), small enough that any sustained drop/underrun pattern
    // shows up immediately in the diag counters instead of being absorbed
    // for minutes.
    private val ring = ArrayBlockingQueue<ByteArray>(5)

    @Volatile private var readerRunning = false
    private var readerThread: Thread? = null

    // Diagnostics: rate-limited to ~2 s. underruns = silence frames shipped
    // because the ring was empty. drops = AudioRecord producing faster than
    // libwebrtc is pulling. Both should be near zero at steady state; any
    // non-trivial rate indicates real-time scheduling pressure or a clock
    // mismatch worth investigating.
    @Volatile private var callbackCount = 0L
    @Volatile private var underrunCount = 0L
    @Volatile private var dropCount = 0L
    private var lastLogNs = 0L

    @SuppressLint("MissingPermission")
    fun attachProjection(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logW("webrtc audio: API<29, playback capture unsupported — streaming silence")
            return
        }
        if (audioRecord != null) return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val channelMask =
                if (WEBRTC_AUDIO_CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO
                else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(WEBRTC_AUDIO_SAMPLE_RATE)
                .setChannelMask(channelMask)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                WEBRTC_AUDIO_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            )
            // AudioRecord internal buffer = max(HAL minimum, 80 ms). Wider than
            // the ring we drain into, so a libwebrtc-side stall (GC pause, etc.)
            // gets absorbed by AudioRecord itself before we have to start
            // dropping at the ring.
            val bufSize = maxOf(minBuf, chunkBytes * 8)
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            rec.startRecording()
            // startRecording() doesn't throw on failure; it just stays in the
            // STOPPED state. Check explicitly so we surface the failure in
            // the log instead of silently shipping zeros forever.
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                logW("webrtc audio: AudioRecord did not enter RECORDING state — streaming silence")
                runCatching { rec.release() }
                return
            }
            audioRecord = rec
            startReaderThread()
            logI("webrtc audio: playback capture ready (bufSize=$bufSize, ringCap=${ring.remainingCapacity()})")
        } catch (e: SecurityException) {
            logW("webrtc audio: RECORD_AUDIO not granted — streaming silence")
        } catch (e: Throwable) {
            logE("webrtc audio: setup failed — streaming silence", e)
        }
    }

    private fun startReaderThread() {
        if (readerThread != null) return
        readerRunning = true
        val t = Thread({
            val buf = ByteArray(chunkBytes)
            while (readerRunning) {
                val rec = audioRecord ?: break
                // READ_BLOCKING: the thread sleeps until AudioRecord has the
                // full 10 ms ready. This is exactly what we want — the
                // blocking happens here on our own thread, where stalling is
                // free, instead of on libwebrtc's audio thread where it
                // backs up the encode pipeline.
                val n = try {
                    rec.read(buf, 0, chunkBytes, AudioRecord.READ_BLOCKING)
                } catch (_: Throwable) { -1 }
                if (n <= 0) {
                    // Negative = persistent error (record stopped, hardware
                    // glitch, AudioRecord.ERROR_*). Short backoff so we don't
                    // spin a tight loop while still letting readerRunning=false
                    // tear us down promptly.
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    continue
                }
                // Android's READ_BLOCKING is documented to wait for the full
                // request, but defensively: if we got a short read, ship it
                // tail-padded with silence rather than skip — libwebrtc
                // expects a steady cadence of chunks.
                val chunk = ByteArray(chunkBytes)
                System.arraycopy(buf, 0, chunk, 0, n)
                // Ring full → AudioRecord HAL clock is running ahead of
                // libwebrtc's pull clock. Drop oldest so latency stays bounded;
                // a 10 ms loss is preferable to growing queue depth.
                if (!ring.offer(chunk)) {
                    ring.poll()
                    ring.offer(chunk)
                    dropCount++
                }
            }
        }, "WebRtcAudioCapture-reader")
        t.isDaemon = true
        // Highest sensible Java priority. Android won't let user threads
        // preempt the audio HAL, but this keeps the reader ahead of UI and
        // coroutine workers — important during heavy Compose recompositions.
        t.priority = Thread.MAX_PRIORITY
        readerThread = t
        t.start()
    }

    // Called on libwebrtc's audio recording thread every 10 ms. The buffer
    // arrives already filled with mic AudioRecord data from libwebrtc's
    // internal recorder; we overwrite it with our playback-capture chunk.
    // Never blocks: pops from the ring or ships silence on underrun.
    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimestampNs: Long,
    ): Long {
        callbackCount++
        val pos = buffer.position()
        val chunk = ring.poll()
        if (chunk == null) {
            // Ring empty. Two ways to hit this: startup (AudioRecord hasn't
            // delivered its first chunk yet) or sustained underrun (libwebrtc
            // pulling faster than HAL produces). Silence is the only safe
            // option without stalling libwebrtc's audio thread.
            zeroRange(buffer, pos, bytesRead)
            underrunCount++
            logRateMaybe()
            return 0L
        }
        val n = minOf(chunk.size, bytesRead, buffer.limit() - pos)
        for (i in 0 until n) buffer.put(pos + i, chunk[i])
        if (n < bytesRead) zeroRange(buffer, pos + n, bytesRead - n)
        // onBuffer contract: leave position where we found it. ByteBuffer.put
        // with an explicit index doesn't move position, so nothing to reset.
        logRateMaybe()
        return 0L
    }

    private fun logRateMaybe() {
        val now = System.nanoTime()
        if (now - lastLogNs > 2_000_000_000L) {
            lastLogNs = now
            logI(
                "onBuffer: calls=$callbackCount underruns=$underrunCount " +
                    "drops=$dropCount ringDepth=${ring.size}"
            )
        }
    }

    private fun zeroRange(buffer: ByteBuffer, from: Int, length: Int) {
        val lim = buffer.limit()
        var i = from
        val end = minOf(from + length, lim)
        while (i < end) { buffer.put(i, 0); i++ }
    }

    fun release() {
        readerRunning = false
        readerThread?.interrupt()
        readerThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        ring.clear()
    }
}
