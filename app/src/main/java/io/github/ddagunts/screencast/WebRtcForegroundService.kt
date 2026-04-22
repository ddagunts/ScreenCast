package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.cast.CastCertPinStore
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import io.github.ddagunts.screencast.webrtc.WebRtcCastSession
import io.github.ddagunts.screencast.webrtc.WebRtcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Separate FGS from the HLS-mode CastForegroundService. Only ever runs one
// device at a time — unlike HLS mode, WebRTC is a single-peer connection and
// there's no cheap "fan out one encoder to N receivers" story, so we don't
// bother.
// Second-device casting would require a second PeerConnection + a second
// MediaProjection, which re-prompts the user and doubles CPU. Out of scope.
class WebRtcForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var session: WebRtcCastSession? = null
    private var sessionJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopCast()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (session != null) {
            logW("webrtc: already casting; ignoring duplicate start")
            return
        }
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "?"
        val host = intent.getStringExtra(EXTRA_DEVICE_HOST) ?: return
        val port = intent.getIntExtra(EXTRA_DEVICE_PORT, 8009)
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            logE("webrtc: ACTION_START missing projection result data (code=$code)")
            stopSelfIfIdle()
            return
        }
        if (appId.isBlank()) {
            logE("webrtc: ACTION_START with empty appId")
            _session.value = SessionSnapshot(
                device = CastDevice(name, host, port),
                status = "ERROR",
                errorMessage = "custom App ID is empty",
            )
            stopSelfIfIdle()
            return
        }

        startForegroundNow(name)
        acquireWakeLock()

        val device = CastDevice(name, host, port)
        _session.value = SessionSnapshot(device = device, status = "CONNECTING")

        val s = WebRtcCastSession(this, device, CastCertPinStore(this))
        session = s
        sessionJob = scope.launch {
            // Observe state and mirror to the global flow so the UI can see it.
            launch {
                s.state.collect { st ->
                    _session.value = when (st) {
                        is WebRtcState.Idle -> null
                        is WebRtcState.Connecting -> SessionSnapshot(device, "CONNECTING")
                        is WebRtcState.Launching -> SessionSnapshot(device, "LAUNCHING")
                        is WebRtcState.Signaling -> SessionSnapshot(device, "SIGNALING")
                        is WebRtcState.Casting -> SessionSnapshot(device, "CASTING")
                        is WebRtcState.Error -> SessionSnapshot(device, "ERROR", st.message)
                    }
                    refreshNotification()
                    if (st is WebRtcState.Idle || st is WebRtcState.Error) {
                        // Error state is terminal here — the session can't
                        // recover (no retry path for launch/ICE failures yet).
                        // Hold the ERROR in the flow so the UI shows "why" and
                        // let the user tap Stop to dismiss.
                    }
                }
            }
            runCatching {
                s.startCast(appId, data) {
                    // MediaProjection revoked from the notification — treat
                    // as an explicit stop.
                    logW("webrtc: projection onStop → stopping service")
                    stopCast()
                }
            }.onFailure { e ->
                logE("webrtc: startCast threw", e)
                _session.value = SessionSnapshot(device, "ERROR", e.message ?: "start failed")
                refreshNotification()
            }
        }
    }

    private fun stopCast() {
        sessionJob?.cancel()
        sessionJob = null
        runCatching { session?.stop() }.onFailure { logW("stopCast: $it") }
        session = null
        _session.value = null
        releaseWakeLock()
        stopSelfIfIdle()
    }

    private fun stopSelfIfIdle() {
        if (session == null && _session.value == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundNow(deviceName: String) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(deviceName), type)
    }

    private fun refreshNotification() {
        val snap = _session.value
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val name = snap?.device?.name ?: "WebRTC"
        nm.notify(NOTIFICATION_ID, buildNotification(name))
    }

    private fun buildNotification(deviceName: String): Notification {
        val snap = _session.value
        val title = when (snap?.status) {
            "CASTING" -> "WebRTC → $deviceName"
            "SIGNALING" -> "Connecting WebRTC to $deviceName"
            "LAUNCHING" -> "Launching receiver on $deviceName"
            "ERROR" -> "WebRTC error"
            else -> "WebRTC → $deviceName"
        }
        val body = snap?.errorMessage ?: "Tap Stop to end"
        val stopIntent = Intent(this, WebRtcForegroundService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "screencast:webrtc-session",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { logW("release webrtc wakeLock: $it") }
        wakeLock = null
    }

    override fun onDestroy() {
        stopCast()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WebRTC cast", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // UI-visible snapshot. Error and terminal states persist until the user
    // explicitly stops — same pattern as HLS mode's DeviceCast.
    data class SessionSnapshot(
        val device: CastDevice,
        // "CONNECTING" | "LAUNCHING" | "SIGNALING" | "CASTING" | "ERROR"
        val status: String,
        val errorMessage: String? = null,
    )

    companion object {
        const val ACTION_START = "io.github.ddagunts.screencast.webrtc.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.webrtc.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_HOST = "device_host"
        const val EXTRA_DEVICE_PORT = "device_port"
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "webrtc_cast"
        const val NOTIFICATION_ID = 2

        private val _session = MutableStateFlow<SessionSnapshot?>(null)
        val sessionFlow: StateFlow<SessionSnapshot?> = _session
    }
}
