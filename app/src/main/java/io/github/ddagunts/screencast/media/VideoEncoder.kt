package io.github.ddagunts.screencast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrate: Int = 3_000_000,
    private val keyframeIntervalSec: Int = 2,
) {
    interface Sink {
        fun onFormat(sps: ByteArray, pps: ByteArray)
        fun onSample(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean)
    }

    private lateinit var codec: MediaCodec
    lateinit var inputSurface: Surface; private set
    private var running = false
    private var sink: Sink? = null
    private var drainThread: Thread? = null
    private var syncThread: Thread? = null

    fun start(sink: Sink) {
        this.sink = sink
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, when {
                width * height >= 1920 * 1080 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
                width * height >= 1280 * 720 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
            })
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        drainThread = thread(name = "video-encoder-drain", isDaemon = true) { drain() }
        syncThread = thread(name = "video-encoder-sync", isDaemon = true) { syncFrameLoop() }
        logI("encoder started ${width}x${height} @ ${fps}fps bitrate=$bitrate")
    }

    fun stop() {
        running = false
        runCatching { syncThread?.interrupt() }
        runCatching { drainThread?.join(500) }
        runCatching { syncThread?.join(500) }
        runCatching { codec.signalEndOfInputStream() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    // KEY_I_FRAME_INTERVAL is a *hint* and vendor encoders miss it by up to a
    // second either way. HlsSegmenter only flushes at IDR boundaries, so we force
    // a sync frame on a fixed wall-clock cadence to keep segment durations —
    // and therefore the playlist's TARGETDURATION — predictable.
    private fun syncFrameLoop() {
        val intervalMs = keyframeIntervalSec * 1000L
        while (running) {
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return }
            if (!running) return
            runCatching {
                codec.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var sps: ByteArray? = null; var pps: ByteArray? = null
        var framesOut = 0
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Throwable) {
                logE("dequeue failed", e); return
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = codec.outputFormat
                    sps = fmt.getByteBuffer("csd-0")?.let { it.toArray() }?.let { stripStartCode(it) }
                    pps = fmt.getByteBuffer("csd-1")?.let { it.toArray() }?.let { stripStartCode(it) }
                    if (sps != null && pps != null) sink?.onFormat(sps, pps)
                    logI("encoder format changed (sps=${sps?.size}, pps=${pps?.size})")
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                idx >= 0 -> {
                    val buffer = codec.getOutputBuffer(idx)
                    if (buffer == null) { logW("video output buffer null at frame=$framesOut"); continue }
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                        val nals = splitNals(bytes)
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        sink?.onSample(nals, info.presentationTimeUs, key)
                        framesOut++
                        if (framesOut == 1 || framesOut % 60 == 0) {
                            logI("encoder produced $framesOut frames (last key=$key pts=${info.presentationTimeUs})")
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun ByteBuffer.toArray(): ByteArray {
        val a = ByteArray(remaining()); get(a); return a
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        // Remove 00 00 00 01 or 00 00 01 prefix
        var start = 0
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) start = 4
        else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) start = 3
        return nal.copyOfRange(start, nal.size)
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val positions = ArrayList<Int>()
        var i = 0
        while (i < data.size - 3) {
            val sc3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val sc4 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()
            if (sc4) { positions.add(i); i += 4; continue }
            if (sc3) { positions.add(i); i += 3; continue }
            i++
        }
        for (j in positions.indices) {
            val startSc = positions[j]
            val scLen = if (startSc + 3 < data.size && data[startSc + 2] == 0.toByte()) 4 else 3
            val begin = startSc + scLen
            val end = if (j + 1 < positions.size) positions[j + 1] else data.size
            out.add(data.copyOfRange(begin, end))
        }
        return out
    }
}
