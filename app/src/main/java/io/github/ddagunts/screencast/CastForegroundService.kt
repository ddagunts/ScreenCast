package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.cast.CastCertPinStore
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastSession
import io.github.ddagunts.screencast.cast.CastState
import io.github.ddagunts.screencast.cast.DEFAULT_MEDIA_RECEIVER
import io.github.ddagunts.screencast.media.AudioEncoder
import io.github.ddagunts.screencast.media.HlsSegmenter
import io.github.ddagunts.screencast.media.HttpStreamServer
import io.github.ddagunts.screencast.media.Resolution
import io.github.ddagunts.screencast.media.ScreenCapture
import io.github.ddagunts.screencast.media.StreamConfig
import io.github.ddagunts.screencast.media.VideoEncoder
import io.github.ddagunts.screencast.util.NetworkUtils
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import android.util.Base64

class CastForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var session: CastSession? = null
    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var capture: ScreenCapture? = null
    private var server: HttpStreamServer? = null
    private var segmenter: HlsSegmenter? = null
    private var device: CastDevice? = null
    private var config: StreamConfig = StreamConfig()
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        state.value = Phase.Idle
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "?"
                val host = intent.getStringExtra(EXTRA_DEVICE_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_DEVICE_PORT, 8009)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null) {
                    logE("ACTION_START missing projection result data")
                    stopSelf(); return START_NOT_STICKY
                }
                device = CastDevice(name, host, port)
                val resName = intent.getStringExtra(EXTRA_RESOLUTION)
                val resolution = runCatching { Resolution.valueOf(resName ?: "") }.getOrDefault(StreamConfig().resolution)
                config = StreamConfig(
                    segmentDurationSec = intent.getDoubleExtra(EXTRA_SEGMENT_DURATION, StreamConfig().segmentDurationSec),
                    windowSize = intent.getIntExtra(EXTRA_WINDOW_SIZE, StreamConfig().windowSize),
                    liveEdgeFactor = intent.getDoubleExtra(EXTRA_LIVE_EDGE, StreamConfig().liveEdgeFactor),
                    resolution = resolution,
                )
                logI("stream config: ${config.resolution.label} segment=${config.segmentDurationSec}s window=${config.windowSize} keyframe=${config.keyframeIntervalSec}s seed=${config.seedSegmentCount} liveEdge=${config.liveEdgeFactor}x")
                startForegroundNow(name)
                startPipeline(code, data, device!!)
            }
            ACTION_STOP -> {
                teardown()
                state.value = Phase.Idle
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow(deviceName: String) {
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, CastForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Casting to $deviceName")
            .setContentText("Tap Stop to end")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, type)
    }

    private fun startPipeline(resultCode: Int, resultData: Intent, dev: CastDevice) {
        val size = ScreenCapture.sizeFor(this, config.resolution)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Throwable) {
            logE("getMediaProjection failed (resultCode=$resultCode)", e)
            state.value = Phase.Error("media projection failed: ${e.message}")
            stopSelf(); return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logI("MediaProjection stopped")
                teardown()
                state.value = Phase.Idle
                stopSelf()
            }
        }, null)
        projection = proj

        val seg = HlsSegmenter(
            targetDurationSec = config.segmentDurationSec,
            windowSize = config.windowSize,
            liveEdgeFactor = config.liveEdgeFactor,
        ).also { segmenter = it }
        val aEnc = AudioEncoder().also { audioEncoder = it }
        aEnc.start(seg, proj)
        val enc = VideoEncoder(
            width = size.width,
            height = size.height,
            bitrate = config.resolution.bitrate,
            keyframeIntervalSec = config.keyframeIntervalSec,
        )
        encoder = enc
        enc.start(seg)
        val cap = ScreenCapture(this).also { capture = it }
        cap.start(proj, enc.inputSurface, size)

        val token = newStreamToken()
        val srv = HttpStreamServer(HTTP_PORT, seg, token).also { server = it }
        srv.start()

        val ip = NetworkUtils.getWifiIpAddress() ?: run {
            logE("no LAN IP — cannot tell Chromecast where to fetch")
            state.value = Phase.Error("no LAN IP — is Wi-Fi connected?")
            teardown(); stopSelf(); return
        }
        val url = "http://$ip:$HTTP_PORT/c/$token/stream.m3u8"
        state.value = Phase.Starting(dev, url)

        scope.launch {
            val seedTarget = config.seedSegmentCount
            for (attempt in 0 until 150) {
                if (seg.readySegmentCount() >= seedTarget) break
                delay(100)
            }
            if (seg.readySegmentCount() < seedTarget) {
                logE("only ${seg.readySegmentCount()} segments after 15s (wanted $seedTarget)")
                state.value = Phase.Error("encoder produced too few segments")
                teardown(); stopSelf(); return@launch
            }
            val s = CastSession(dev, CastCertPinStore(this@CastForegroundService)).also { session = it }
            scope.launch {
                s.state.collect { cs ->
                    state.value = when (cs) {
                        is CastState.Idle -> Phase.Idle
                        is CastState.Connecting -> Phase.Starting(dev, url)
                        is CastState.Casting -> Phase.Casting(dev, url)
                        is CastState.Error -> Phase.Error(cs.message)
                    }
                }
            }
            s.startCast(DEFAULT_MEDIA_RECEIVER, url, "application/x-mpegURL")
            logI("cast pipeline live at $url")
        }
    }

    // Resource cleanup only. Callers are responsible for setting `state` — an
    // earlier Phase.Error must survive so the user sees what went wrong.
    private fun teardown() {
        runCatching { session?.stop() }
        runCatching { capture?.stop() }
        runCatching { encoder?.stop() }
        runCatching { audioEncoder?.stop() }
        runCatching { server?.stop() }
        runCatching { projection?.stop() }
        session = null; capture = null; encoder = null; audioEncoder = null
        server = null; segmenter = null; projection = null
        logW("pipeline torn down")
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // 128 bits of entropy, url-safe. Rotated per cast session — the URL is only
    // valid while this foreground service is running.
    private fun newStreamToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    sealed class Phase {
        data object Idle : Phase()
        data class Starting(val device: CastDevice, val url: String) : Phase()
        data class Casting(val device: CastDevice, val url: String) : Phase()
        data class Error(val message: String) : Phase()
    }

    companion object {
        const val ACTION_START = "io.github.ddagunts.screencast.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_HOST = "device_host"
        const val EXTRA_DEVICE_PORT = "device_port"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SEGMENT_DURATION = "segment_duration"
        const val EXTRA_WINDOW_SIZE = "window_size"
        const val EXTRA_LIVE_EDGE = "live_edge_factor"
        const val EXTRA_RESOLUTION = "resolution"
        const val CHANNEL_ID = "cast"
        const val NOTIFICATION_ID = 1
        const val HTTP_PORT = 8080

        private val _state = MutableStateFlow<Phase>(Phase.Idle)
        val state: MutableStateFlow<Phase> = _state
        val flow: StateFlow<Phase> = _state
    }
}
