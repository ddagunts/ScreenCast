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

// PCM source feeding libwebrtc's audio pipeline via AudioBufferCallback.
// Captures device playback (USAGE_MEDIA + USAGE_GAME) through the same
// MediaProjection the video capturer uses — no second consent prompt, no
// separate FGS. Streams zeros until attachProjection() is called, so
// libwebrtc's audio thread can start before MediaProjection is available
// (ADM must exist at PeerConnectionFactory build time, but MediaProjection
// isn't obtained until ScreenCapturerAndroid.startCapture runs).
//
// Matching USAGE filters to AudioEncoder.kt: USAGE_UNKNOWN would scoop up
// notification tones and system sounds, which is a privacy surprise for a
// screen-cast app. Media + game covers what users actually want to share.
class WebRtcAudioCapture : JavaAudioDeviceModule.AudioBufferCallback {

    @Volatile private var audioRecord: AudioRecord? = null

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
            // libwebrtc pulls 10 ms chunks (1920 B at 48k/stereo/16-bit). Give
            // AudioRecord a few frames of headroom so a capture hiccup doesn't
            // stall the WebRTC audio thread.
            val chunk10ms = WEBRTC_AUDIO_SAMPLE_RATE / 100 * WEBRTC_AUDIO_CHANNELS * 2
            val bufSize = maxOf(minBuf, chunk10ms * 8)
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            rec.startRecording()
            audioRecord = rec
            logI("webrtc audio: playback capture ready (bufSize=$bufSize)")
        } catch (e: SecurityException) {
            logW("webrtc audio: RECORD_AUDIO not granted — streaming silence")
        } catch (e: Throwable) {
            logE("webrtc audio: setup failed — streaming silence", e)
        }
    }

    // Called on libwebrtc's audio recording thread every 10 ms. The buffer's
    // position marks where libwebrtc expects us to start writing; on return,
    // position must be back where we found it and the bytes [pos, pos+bytesRead)
    // must be populated. Do NOT log here — this fires 100×/s.
    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimestampNs: Long,
    ): Long {
        val rec = audioRecord
        val pos = buffer.position()
        if (rec == null) {
            zeroRange(buffer, pos, bytesRead)
            return 0L
        }
        val n = try {
            rec.read(buffer, bytesRead, AudioRecord.READ_BLOCKING)
        } catch (_: Throwable) { -1 }
        if (n < bytesRead) zeroTail(buffer, pos, bytesRead, if (n > 0) n else 0)
        buffer.position(pos)
        return 0L
    }

    private fun zeroRange(buffer: ByteBuffer, from: Int, length: Int) {
        val lim = buffer.limit()
        var i = from
        val end = minOf(from + length, lim)
        while (i < end) { buffer.put(i, 0); i++ }
    }

    private fun zeroTail(buffer: ByteBuffer, from: Int, requested: Int, got: Int) {
        zeroRange(buffer, from + got, requested - got)
    }

    fun release() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}
