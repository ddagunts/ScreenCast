package io.github.ddagunts.screencast.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * AAC-LC encoder. If a MediaProjection is supplied (API 29+) and RECORD_AUDIO is granted,
 * captures device playback audio via AudioPlaybackCaptureConfiguration. Otherwise feeds
 * silence — Chromecast's ExoPlayer requires an audio track in the HLS stream regardless.
 */
class AudioEncoder(
    val sampleRate: Int = 44100,
    val channelCount: Int = 2,
    private val bitrate: Int = 128_000,
) {
    interface Sink {
        fun onAacFrame(data: ByteArray, ptsUs: Long)
    }

    private lateinit var codec: MediaCodec
    @Volatile private var running = false
    private var sink: Sink? = null
    private var audioRecord: AudioRecord? = null
    private val samplesPerFrame = 1024   // AAC-LC canonical
    private val frameDurationUs: Long = 1_000_000L * samplesPerFrame / sampleRate
    private val frameBytes = samplesPerFrame * channelCount * 2
    private var ptsUs: Long = 0

    fun start(sink: Sink, projection: MediaProjection? = null) {
        this.sink = sink
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        setupPlaybackCapture(projection)
        audioRecord?.startRecording()
        running = true
        thread(name = "audio-encoder", isDaemon = true) { pump() }
        logI("audio encoder started ${sampleRate}Hz ch=${channelCount} capture=${audioRecord != null}")
    }

    fun stop() {
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    // Playback capture piggy-backs on the existing MediaProjection grant — the user
    // is not prompted twice. RECORD_AUDIO is still required because AudioRecord.Builder
    // checks it unconditionally, even though we never touch the microphone.
    @SuppressLint("MissingPermission")
    private fun setupPlaybackCapture(projection: MediaProjection?) {
        if (projection == null) { logI("audio: no projection — streaming silence"); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { logI("audio: API<29 — streaming silence"); return }
        try {
            // USAGE_UNKNOWN would scoop up any app that doesn't tag its stream —
            // including notification tones and voice-message previews — which is a
            // privacy surprise for a screen-casting app. Keep it to media + game.
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, frameBytes * 4)
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            logI("audio: playback capture ready (bufSize=$bufSize min=$minBuf)")
        } catch (e: SecurityException) {
            logW("audio: RECORD_AUDIO not granted — streaming silence")
        } catch (e: Throwable) {
            logE("audio: playback capture setup failed — streaming silence", e)
        }
    }

    private fun pump() {
        val silence = ByteArray(frameBytes)
        val info = MediaCodec.BufferInfo()
        val rec = audioRecord
        while (running) {
            val inIdx = try { codec.dequeueInputBuffer(10_000) } catch (e: Throwable) {
                logE("audio dequeueInput failed", e); return
            }
            if (inIdx >= 0) {
                val inBuf: ByteBuffer? = codec.getInputBuffer(inIdx)
                if (inBuf == null) { logW("audio input buffer null at idx=$inIdx"); continue }
                inBuf.clear()
                val queued = if (rec != null) readFull(rec, inBuf, frameBytes)
                else { inBuf.put(silence); frameBytes }
                codec.queueInputBuffer(inIdx, 0, queued, ptsUs, 0)
                ptsUs += frameDurationUs
            }
            // Drain output
            while (true) {
                val outIdx = try { codec.dequeueOutputBuffer(info, 0) } catch (e: Throwable) { return }
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIdx < 0) break
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx) ?: continue
                    val raw = ByteArray(info.size)
                    buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(raw)
                    sink?.onAacFrame(raw, info.presentationTimeUs)
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
        }
    }

    private fun readFull(rec: AudioRecord, inBuf: ByteBuffer, want: Int): Int {
        var total = 0
        while (total < want && running) {
            val n = rec.read(inBuf, want - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}
