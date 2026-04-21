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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.cast.CastCertPinStore
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastSession
import io.github.ddagunts.screencast.cast.CastState
import io.github.ddagunts.screencast.cast.CastVolume
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64

class CastForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Cancelled on teardown so a new ACTION_START can't produce overlapping phase
    // emissions from a half-stopped previous session.
    private var sessionJob: Job? = null
    private var session: CastSession? = null
    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var capture: ScreenCapture? = null
    private var server: HttpStreamServer? = null
    private var segmenter: HlsSegmenter? = null
    private var device: CastDevice? = null
    private var config: StreamConfig = StreamConfig()
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        _state.value = Phase.Idle
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { scope.launch { session?.pause() }; return START_NOT_STICKY }
            ACTION_PLAY  -> { scope.launch { session?.play() };  return START_NOT_STICKY }
            ACTION_SET_VOLUME -> {
                val level = intent.getDoubleExtra(EXTRA_VOLUME_LEVEL, -1.0)
                if (level >= 0.0) scope.launch { session?.setVolume(level) }
                return START_NOT_STICKY
            }
            ACTION_SET_MUTE -> {
                val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                scope.launch { session?.setMute(muted) }
                return START_NOT_STICKY
            }
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
                deviceName = name
                startForegroundNow(name)
                startPipeline(code, data, device!!)
            }
            ACTION_STOP -> {
                teardown()
                _state.value = Phase.Idle
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Must be called *before* MediaProjectionManager.getMediaProjection(). On API 35+
    // the mediaProjection FGS type requires the service to already be foreground; the
    // getMediaProjection call otherwise throws SecurityException.
    private fun startForegroundNow(deviceName: String) {
        val notif = buildNotification(deviceName, _playerState.value)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, type)
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, CastForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(deviceName: String, playerState: String): Notification {
        val isPaused = playerState.equals("PAUSED", ignoreCase = true)
        val toggleAction = if (isPaused) ACTION_PLAY else ACTION_PAUSE
        val toggleLabel = if (isPaused) "Play" else "Pause"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Casting to $deviceName")
            .setContentText(if (isPaused) "Paused — tap Play to resume" else "Tap Stop to end")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, toggleLabel, servicePending(toggleAction, 1))
            .addAction(0, "Stop", servicePending(ACTION_STOP, 0))
            .build()
    }

    private fun refreshNotification() {
        if (deviceName.isEmpty()) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(deviceName, _playerState.value))
    }

    // There is no non-deprecated replacement for "keep screen on from a Service"
    // — Window.FLAG_KEEP_SCREEN_ON needs a foreground Activity, and our user
    // drops the app to home/lock so the *casted* content keeps rendering.
    // SCREEN_DIM_WAKE_LOCK allows the user's brightness preference to be
    // respected (unlike SCREEN_BRIGHT). The lock is tagged so `dumpsys power`
    // reports it clearly during debugging.
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "screencast:cast-session",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { logW("release wakeLock: $it") }
        wakeLock = null
    }

    private fun startPipeline(resultCode: Int, resultData: Intent, dev: CastDevice) {
        val size = ScreenCapture.sizeFor(this, config.resolution)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Throwable) {
            logE("getMediaProjection failed (resultCode=$resultCode)", e)
            _state.value = Phase.Error("media projection failed: ${e.message}")
            stopSelf(); return
        } ?: run {
            logE("getMediaProjection returned null (resultCode=$resultCode)")
            _state.value = Phase.Error("media projection unavailable — consent likely denied")
            stopSelf(); return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logI("MediaProjection stopped")
                teardown()
                _state.value = Phase.Idle
                stopSelf()
            }
        }, null)
        projection = proj
        acquireWakeLock()

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

        val ip = NetworkUtils.getWifiIpAddress(this) ?: run {
            logE("no LAN IP — cannot tell Chromecast where to fetch")
            _state.value = Phase.Error("no LAN IP — is Wi-Fi connected?")
            teardown(); stopSelf(); return
        }
        // Ask the kernel for a free ephemeral port, then hand it to Ktor. Brief
        // TOCTOU window is acceptable on a single-user device.
        val httpPort = ServerSocket(0).use { it.localPort }

        val token = newStreamToken()
        val srv = HttpStreamServer(ip, httpPort, seg, token).also { server = it }
        srv.start()

        val url = "http://$ip:$httpPort/c/$token/stream.m3u8"
        _state.value = Phase.Starting(dev, url)

        sessionJob = scope.launch {
            val seedTarget = config.seedSegmentCount
            for (attempt in 0 until 150) {
                if (seg.readySegmentCount() >= seedTarget) break
                delay(100)
            }
            if (seg.readySegmentCount() < seedTarget) {
                logE("only ${seg.readySegmentCount()} segments after 15s (wanted $seedTarget)")
                _state.value = Phase.Error("encoder produced too few segments")
                teardown(); stopSelf(); return@launch
            }
            val s = CastSession(dev, CastCertPinStore(this@CastForegroundService)).also { session = it }
            // child of sessionJob: cancelled via sessionJob.cancel() in teardown()
            launch {
                s.state.collect { cs ->
                    _state.value = when (cs) {
                        is CastState.Idle -> Phase.Idle
                        is CastState.Connecting -> Phase.Starting(dev, url)
                        is CastState.Casting -> Phase.Casting(dev, url)
                        is CastState.Error -> Phase.Error(cs.message)
                    }
                }
            }
            launch {
                s.playerState.collect { ps ->
                    _playerState.value = ps
                    refreshNotification()
                }
            }
            launch { s.volume.collect { _volume.value = it } }
            s.startCast(DEFAULT_MEDIA_RECEIVER, url, "application/x-mpegURL")
            logI("cast pipeline live at $url")
        }
    }

    // Resource cleanup only. Callers are responsible for setting `state` — an
    // earlier Phase.Error must survive so the user sees what went wrong.
    private fun teardown() {
        sessionJob?.cancel(); sessionJob = null
        runCatching { session?.stop() }.onFailure { logW("teardown session: $it") }
        runCatching { capture?.stop() }.onFailure { logW("teardown capture: $it") }
        runCatching { encoder?.stop() }.onFailure { logW("teardown encoder: $it") }
        runCatching { audioEncoder?.stop() }.onFailure { logW("teardown audioEncoder: $it") }
        runCatching { server?.stop() }.onFailure { logW("teardown server: $it") }
        runCatching { projection?.stop() }.onFailure { logW("teardown projection: $it") }
        releaseWakeLock()
        session = null; capture = null; encoder = null; audioEncoder = null
        server = null; segmenter = null; projection = null
        deviceName = ""
        _playerState.value = "IDLE"
        _volume.value = CastVolume()
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun createChannel() {
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
        const val ACTION_PAUSE = "io.github.ddagunts.screencast.PAUSE"
        const val ACTION_PLAY = "io.github.ddagunts.screencast.PLAY"
        const val ACTION_SET_VOLUME = "io.github.ddagunts.screencast.SET_VOLUME"
        const val ACTION_SET_MUTE = "io.github.ddagunts.screencast.SET_MUTE"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_HOST = "device_host"
        const val EXTRA_DEVICE_PORT = "device_port"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SEGMENT_DURATION = "segment_duration"
        const val EXTRA_WINDOW_SIZE = "window_size"
        const val EXTRA_LIVE_EDGE = "live_edge_factor"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_VOLUME_LEVEL = "volume_level"
        const val EXTRA_MUTED = "muted"
        const val CHANNEL_ID = "cast"
        const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow<Phase>(Phase.Idle)
        val flow: StateFlow<Phase> = _state

        private val _playerState = MutableStateFlow("IDLE")
        val playerStateFlow: StateFlow<String> = _playerState

        private val _volume = MutableStateFlow(CastVolume())
        val volumeFlow: StateFlow<CastVolume> = _volume
    }
}
