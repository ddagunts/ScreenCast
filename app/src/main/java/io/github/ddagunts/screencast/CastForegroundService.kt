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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64

class CastForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Shared encoder pipeline — started on first device, torn down when the
    // session map empties. `pipelineUrl` is the authoritative signal that the
    // pipeline is live; all other fields are null before and after.
    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var capture: ScreenCapture? = null
    private var server: HttpStreamServer? = null
    private var segmenter: HlsSegmenter? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pipelineUrl: String? = null
    private var config: StreamConfig = StreamConfig()

    // Per-device sessions keyed by host. Each session has its own coroutine
    // children collecting from its state/playerState/volume flows; those
    // children live on `scope` and are cancelled via sessionJobs.remove(host).
    private val sessions: MutableMap<String, CastSession> = mutableMapOf()
    private val sessionJobs: MutableMap<String, Job> = mutableMapOf()

    // Background loop that nudges drifting receivers back together by pausing
    // the one(s) ahead for roughly the drift amount. Null while pipeline is
    // not live; set up in startPipelineAndFirstSession, cancelled in teardown.
    private var maintainJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val host = intent.getStringExtra(EXTRA_DEVICE_HOST)
        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP_DEVICE -> if (host != null) stopDevice(host)
            ACTION_STOP_ALL -> stopAll()
            ACTION_PAUSE -> if (host != null) scope.launch { sessions[host]?.pause() }
            ACTION_PLAY -> if (host != null) scope.launch { sessions[host]?.play() }
            ACTION_SET_VOLUME -> if (host != null) {
                val level = intent.getDoubleExtra(EXTRA_VOLUME_LEVEL, -1.0)
                if (level >= 0.0) scope.launch { sessions[host]?.setVolume(level) }
            }
            ACTION_SET_MUTE -> if (host != null) {
                val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                scope.launch { sessions[host]?.setMute(muted) }
            }
            ACTION_UPDATE_SYNC_CONFIG -> applySyncExtras(intent)
        }
        return START_NOT_STICKY
    }

    // Pulled out so handleStart and ACTION_UPDATE_SYNC_CONFIG share one path.
    // Missing extras leave the current config untouched — callers can push
    // only the field that changed without zeroing the other.
    private fun applySyncExtras(intent: Intent) {
        if (intent.hasExtra(EXTRA_SYNC_START)) {
            config = config.copy(syncStart = intent.getBooleanExtra(EXTRA_SYNC_START, false))
        }
        if (intent.hasExtra(EXTRA_SYNC_INTERVAL_SEC)) {
            config = config.copy(syncIntervalSec = intent.getIntExtra(EXTRA_SYNC_INTERVAL_SEC, config.syncIntervalSec))
        }
        if (intent.hasExtra(EXTRA_SYNC_DRIFT_MS)) {
            config = config.copy(syncDriftThresholdMs = intent.getIntExtra(EXTRA_SYNC_DRIFT_MS, config.syncDriftThresholdMs))
        }
    }

    private fun handleStart(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "?"
        val host = intent.getStringExtra(EXTRA_DEVICE_HOST) ?: return
        val port = intent.getIntExtra(EXTRA_DEVICE_PORT, 8009)
        val dev = CastDevice(name, host, port)

        // syncStart / syncInterval / syncDrift are mid-cast tunables. Every
        // ACTION_START carries the user's current preferences so a Settings
        // change between devices applies immediately.
        applySyncExtras(intent)

        if (sessions.containsKey(host)) {
            logW("already casting to $host; ignoring duplicate start")
            return
        }
        if (sessions.size >= MAX_DEVICES) {
            logW("max $MAX_DEVICES devices reached; refusing $host")
            _devices.value = _devices.value + (host to DeviceCast(
                device = dev,
                castState = "ERROR",
                playerState = "IDLE",
                volume = CastVolume(),
                errorMessage = "Max $MAX_DEVICES devices reached",
            ))
            return
        }

        if (pipelineUrl != null) {
            // Pipeline already running — skip MediaProjection, just open a
            // session pointing at the existing URL.
            logI("adding device $name ($host) to live pipeline")
            launchSession(dev, pipelineUrl!!)
            refreshNotification()
            return
        }

        // First device — MediaProjection result is required.
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            logE("ACTION_START missing projection result data")
            stopSelfIfEmpty()
            return
        }
        val resName = intent.getStringExtra(EXTRA_RESOLUTION)
        val resolution = runCatching { Resolution.valueOf(resName ?: "") }.getOrDefault(StreamConfig().resolution)
        config = StreamConfig(
            segmentDurationSec = intent.getDoubleExtra(EXTRA_SEGMENT_DURATION, StreamConfig().segmentDurationSec),
            windowSize = intent.getIntExtra(EXTRA_WINDOW_SIZE, StreamConfig().windowSize),
            liveEdgeFactor = intent.getDoubleExtra(EXTRA_LIVE_EDGE, StreamConfig().liveEdgeFactor),
            resolution = resolution,
        )
        logI("stream config: ${config.resolution.label} segment=${config.segmentDurationSec}s window=${config.windowSize} keyframe=${config.keyframeIntervalSec}s seed=${config.seedSegmentCount} liveEdge=${config.liveEdgeFactor}x")
        startForegroundNow()
        startPipelineAndFirstSession(code, data, dev)
    }

    // Must be called *before* MediaProjectionManager.getMediaProjection(). On API 35+
    // the mediaProjection FGS type requires the service to already be foreground; the
    // getMediaProjection call otherwise throws SecurityException.
    private fun startForegroundNow() {
        val notif = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, type)
    }

    private fun servicePending(action: String, requestCode: Int, host: String? = null): PendingIntent {
        val intent = Intent(this, CastForegroundService::class.java).setAction(action)
        if (host != null) intent.putExtra(EXTRA_DEVICE_HOST, host)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildNotification(): Notification {
        val all = _devices.value.values
        val active = all.filter { it.castState != "ERROR" }
        val hasErrors = all.any { it.castState == "ERROR" }
        val title = when {
            active.size == 1 -> "Casting to ${active.first().device.name}"
            active.size > 1 -> "Casting to ${active.size} devices"
            hasErrors -> "ScreenCast — error"
            else -> "ScreenCast"
        }
        val body = when {
            active.size == 1 -> "Tap Stop to end"
            active.size > 1 -> active.joinToString(", ") { it.device.name }
            hasErrors -> "Tap Stop to dismiss"
            else -> "Starting…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop all", servicePending(ACTION_STOP_ALL, 0))
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
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

    private fun startPipelineAndFirstSession(resultCode: Int, resultData: Intent, dev: CastDevice) {
        val size = ScreenCapture.sizeFor(this, config.resolution)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Throwable) {
            logE("getMediaProjection failed (resultCode=$resultCode)", e)
            emitDeviceError(dev, "media projection failed: ${e.message}")
            stopSelfIfEmpty(); return
        } ?: run {
            logE("getMediaProjection returned null (resultCode=$resultCode)")
            emitDeviceError(dev, "media projection unavailable — consent likely denied")
            stopSelfIfEmpty(); return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logI("MediaProjection stopped — tearing all sessions down")
                stopAll()
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
            emitDeviceError(dev, "no LAN IP — is Wi-Fi connected?")
            teardownPipeline(); stopSelfIfEmpty(); return
        }
        // Ask the kernel for a free ephemeral port, then hand it to Ktor. Brief
        // TOCTOU window is acceptable on a single-user device.
        val httpPort = ServerSocket(0).use { it.localPort }

        val token = newStreamToken()
        val srv = HttpStreamServer(ip, httpPort, seg, token).also { server = it }
        srv.start()

        val url = "http://$ip:$httpPort/c/$token/stream.m3u8"
        pipelineUrl = url
        launchSession(dev, url)
        startSyncMaintenance()
        refreshNotification()
    }

    // Periodic drift correction across live sessions. HLS LIVE without
    // EXT-X-PROGRAM-DATE-TIME means each receiver's currentTime is its own
    // local offset from its first manifest fetch, so these numbers are
    // comparable across *this app's* sessions only while they remain
    // connected — receiver-local clocks advance monotonically.
    //
    // Flow mirrors the startup sync-align:
    //   1. GET_STATUS → find laggard offset
    //   2. if max drift > threshold, PAUSE all in parallel
    //   3. SEEK all to the laggard's offset with PLAYBACK_PAUSE
    //   4. PLAY all in parallel
    // Pausing first is the key — seeks issued while PLAYING are sometimes
    // honored by only one receiver, which is what we saw when the prior
    // version SEEKed in place. Gated behind config.syncStart.
    private fun startSyncMaintenance() {
        if (maintainJob?.isActive == true) return
        maintainJob = scope.launch {
            while (true) {
                delay(config.syncIntervalSec * 1000L)
                if (!config.syncStart) continue
                runCatching { maintainSync() }
                    .onFailure { logW("syncMaintain: $it") }
            }
        }
    }

    private suspend fun maintainSync() {
        val playing = sessions.filter { (_, s) -> s.playerState.value == "PLAYING" }
        if (playing.size < 2) return

        coroutineScope {
            playing.keys.forEach { h ->
                launch { runCatching { sessions[h]?.requestStatus() } }
            }
        }
        delay(400)

        val snapshot = playing.keys.mapNotNull { h ->
            val s = sessions[h] ?: return@mapNotNull null
            h to s.currentTime.value
        }
        if (snapshot.size < 2) return
        val target = snapshot.minOf { it.second }
        val maxDrift = snapshot.maxOf { it.second } - target
        val thresholdSec = config.syncDriftThresholdMs / 1000.0
        if (maxDrift < thresholdSec) return

        val hosts = snapshot.map { it.first }
        val pretty = snapshot.joinToString(", ") { (h, t) -> "$h=${"%.3f".format(t)}" }
        logI("syncMaintain: drift=${"%.3f".format(maxDrift)}s currentTimes { $pretty } target=${"%.3f".format(target)}")

        coroutineScope {
            hosts.forEach { h ->
                launch { runCatching { sessions[h]?.pause() } }
            }
        }
        delay(200)

        coroutineScope {
            hosts.forEach { h ->
                launch { runCatching { sessions[h]?.seek(target, "PLAYBACK_PAUSE") } }
            }
        }
        delay(600)

        logI("syncMaintain: PLAY-all across ${hosts.size} session(s)")
        coroutineScope {
            hosts.forEach { h ->
                launch { runCatching { sessions[h]?.play() } }
            }
        }
    }

    private fun launchSession(dev: CastDevice, url: String) {
        val host = dev.host
        // Seed gating — wait for the segmenter to have enough segments before
        // telling the receiver to LOAD. Only needed on the first device; on
        // subsequent devices the segmenter is already past that point and the
        // wait resolves immediately.
        val seg = segmenter ?: run {
            logE("launchSession: segmenter is null")
            emitDeviceError(dev, "pipeline not ready")
            return
        }
        // Decide sync mode BEFORE adding the new session to the map, so
        // `priorHosts` only holds devices that were already live.
        val priorHosts = sessions.keys.toList()
        val sync = config.syncStart && priorHosts.isNotEmpty()
        _devices.value = _devices.value + (host to DeviceCast(
            device = dev,
            castState = "CONNECTING",
            playerState = "IDLE",
            volume = CastVolume(),
        ))
        val job = scope.launch {
            val seedTarget = config.seedSegmentCount
            for (attempt in 0 until 150) {
                if (seg.readySegmentCount() >= seedTarget) break
                delay(100)
            }
            if (seg.readySegmentCount() < seedTarget) {
                logE("only ${seg.readySegmentCount()} segments after 15s (wanted $seedTarget)")
                emitDeviceError(dev, "encoder produced too few segments")
                removeSession(host)
                return@launch
            }

            // Sync-start phase 1: PAUSE the already-live sessions so they hold
            // their current frame while the new one finishes LOAD + buffer.
            // Parallel so the existing devices land on PAUSED within one RTT
            // of each other.
            if (sync) {
                logI("syncStart: pausing ${priorHosts.size} existing session(s) for $host")
                coroutineScope {
                    priorHosts.forEach { h ->
                        launch { runCatching { sessions[h]?.pause() } }
                    }
                }
            }

            val s = CastSession(dev, CastCertPinStore(this@CastForegroundService))
            sessions[host] = s
            // child jobs — cancelled when the parent (sessionJobs[host]) is cancelled
            launch {
                s.state.collect { cs ->
                    val current = _devices.value[host] ?: return@collect
                    _devices.value = _devices.value + (host to when (cs) {
                        is CastState.Idle -> current.copy(castState = "IDLE")
                        is CastState.Connecting -> current.copy(castState = "CONNECTING", errorMessage = null)
                        is CastState.Casting -> current.copy(castState = "CASTING", errorMessage = null)
                        is CastState.Error -> current.copy(castState = "ERROR", errorMessage = cs.message)
                    })
                    refreshNotification()
                }
            }
            launch {
                s.playerState.collect { ps ->
                    val current = _devices.value[host] ?: return@collect
                    _devices.value = _devices.value + (host to current.copy(playerState = ps))
                }
            }
            launch {
                s.volume.collect { v ->
                    val current = _devices.value[host] ?: return@collect
                    _devices.value = _devices.value + (host to current.copy(volume = v))
                }
            }
            // LOAD with autoplay=false in sync mode — receiver buffers but does
            // not start playback until we send PLAY below.
            s.startCast(
                DEFAULT_MEDIA_RECEIVER, url, "application/x-mpegURL",
                autoplay = !sync,
            )
            logI("session live for $host at $url (sync=$sync)")

            // Sync-start phase 2: wait for the new session's LOAD to complete
            // (playerState leaves IDLE/BUFFERING), then SEEK-align all paused
            // sessions to the same logical stream position, then fire PLAY to
            // everyone in parallel. Wrapped in try/finally so a timeout or
            // error still unpauses the prior sessions.
            if (sync) {
                try {
                    val reached = withTimeoutOrNull(10_000) {
                        s.playerState.first { it == "PLAYING" || it == "PAUSED" }
                    }
                    if (reached == null) {
                        logW("syncStart: $host didn't reach ready state; will PLAY anyway")
                    }
                    runCatching { alignAllSessions() }
                        .onFailure { logW("syncAlign failed: $it") }
                    delay(150)
                } finally {
                    logI("syncStart: PLAY-all across ${sessions.size} session(s)")
                    coroutineScope {
                        sessions.values.forEach { existing ->
                            launch { runCatching { existing.play() } }
                        }
                    }
                }
            }
        }
        sessionJobs[host] = job
    }

    // Coordinated SEEK across every session in the map. Steps:
    //   1. Send GET_STATUS in parallel so every receiver pushes a fresh
    //      MEDIA_STATUS — without this we'd be reading currentTime values
    //      that may be seconds stale.
    //   2. Read the min currentTime (most-behind receiver).
    //   3. SEEK every session that's ahead of the min to the min value with
    //      resumeState=PLAYBACK_PAUSE, so they stay paused at the new position.
    //   4. Let the seeks settle.
    // Caveat: Default Media Receiver reports currentTime relative to *its own*
    // first manifest fetch for HLS LIVE without PDT. If the receivers' refs
    // differ, SEEK targets will land on different stream content and this
    // won't help. Logs before/after so you can see what numbers arrive.
    private suspend fun alignAllSessions() {
        val hosts = sessions.keys.toList()
        if (hosts.size < 2) return

        coroutineScope {
            hosts.forEach { h ->
                launch { runCatching { sessions[h]?.requestStatus() } }
            }
        }
        delay(500)

        val snapshot = hosts.mapNotNull { h ->
            val s = sessions[h] ?: return@mapNotNull null
            h to s.currentTime.value
        }
        if (snapshot.isEmpty()) return
        val target = snapshot.minOf { it.second }
        val pretty = snapshot.joinToString(", ") { (h, t) -> "$h=${"%.3f".format(t)}" }
        logI("syncAlign: pre-seek currentTimes { $pretty } target=${"%.3f".format(target)}")

        val needsSeek = snapshot.filter { it.second > target + 0.05 }
        if (needsSeek.isEmpty()) {
            logI("syncAlign: already within 50 ms tolerance, no seek needed")
            return
        }
        coroutineScope {
            needsSeek.forEach { (h, _) ->
                launch { runCatching { sessions[h]?.seek(target) } }
            }
        }
        // Give receivers time to buffer at the new position. Slow HLS
        // receivers take 500-1000 ms; 800 is a reasonable middle.
        delay(800)
        val after = hosts.joinToString(", ") { h ->
            "$h=${"%.3f".format(sessions[h]?.currentTime?.value ?: -1.0)}"
        }
        logI("syncAlign: post-seek currentTimes { $after }")
    }

    private fun emitDeviceError(dev: CastDevice, message: String) {
        _devices.value = _devices.value + (dev.host to DeviceCast(
            device = dev,
            castState = "ERROR",
            playerState = "IDLE",
            volume = CastVolume(),
            errorMessage = message,
        ))
    }

    private fun stopDevice(host: String) {
        val dev = _devices.value[host]
        if (dev == null) {
            logW("stopDevice: no entry for $host")
            return
        }
        val session = sessions.remove(host)
        sessionJobs.remove(host)?.cancel()
        runCatching { session?.stop() }.onFailure { logW("stopDevice($host): $it") }
        _devices.value = _devices.value - host
        if (sessions.isEmpty()) {
            teardownPipeline()
        } else {
            refreshNotification()
        }
        stopSelfIfEmpty()
    }

    private fun stopAll() {
        val hosts = sessions.keys.toList()
        for (h in hosts) {
            sessionJobs.remove(h)?.cancel()
            runCatching { sessions.remove(h)?.stop() }.onFailure { logW("stopAll($h): $it") }
        }
        _devices.value = emptyMap()
        teardownPipeline()
        stopSelfIfEmpty()
    }

    private fun teardownPipeline() {
        maintainJob?.cancel()
        maintainJob = null
        runCatching { capture?.stop() }.onFailure { logW("teardown capture: $it") }
        runCatching { encoder?.stop() }.onFailure { logW("teardown encoder: $it") }
        runCatching { audioEncoder?.stop() }.onFailure { logW("teardown audioEncoder: $it") }
        runCatching { server?.stop() }.onFailure { logW("teardown server: $it") }
        runCatching { projection?.stop() }.onFailure { logW("teardown projection: $it") }
        releaseWakeLock()
        capture = null; encoder = null; audioEncoder = null
        server = null; segmenter = null; projection = null
        pipelineUrl = null
        logW("pipeline torn down")
    }

    // Don't tear down the service while the UI still shows cast entries —
    // otherwise a ViewModel stopDevice() call would try to startService on a
    // dead service and Android 8+ throws. Only exit when the map is fully
    // drained, which happens once the user dismisses all ERROR cards too.
    private fun stopSelfIfEmpty() {
        if (_devices.value.isEmpty() && sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun removeSession(host: String) {
        sessions.remove(host)
        sessionJobs.remove(host)?.cancel()
        _devices.value = _devices.value - host
        if (sessions.isEmpty()) teardownPipeline()
        stopSelfIfEmpty()
    }

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    // 128 bits of entropy, url-safe. Rotated per pipeline lifetime — valid only
    // while the foreground service is running.
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

    // Per-device cast state, observed by the UI. errorMessage is populated
    // only while castState == "ERROR"; otherwise null. An ERROR entry stays
    // in the map until the user dismisses the device explicitly (or adds it
    // again and it reconnects), so users see *why* a cast failed.
    data class DeviceCast(
        val device: CastDevice,
        val castState: String, // "IDLE" | "CONNECTING" | "CASTING" | "ERROR"
        val playerState: String,
        val volume: CastVolume,
        val errorMessage: String? = null,
    )

    companion object {
        const val MAX_DEVICES = 4

        const val ACTION_START = "io.github.ddagunts.screencast.START"
        const val ACTION_STOP_DEVICE = "io.github.ddagunts.screencast.STOP_DEVICE"
        const val ACTION_STOP_ALL = "io.github.ddagunts.screencast.STOP_ALL"
        const val ACTION_PAUSE = "io.github.ddagunts.screencast.PAUSE"
        const val ACTION_PLAY = "io.github.ddagunts.screencast.PLAY"
        const val ACTION_SET_VOLUME = "io.github.ddagunts.screencast.SET_VOLUME"
        const val ACTION_SET_MUTE = "io.github.ddagunts.screencast.SET_MUTE"
        const val ACTION_UPDATE_SYNC_CONFIG = "io.github.ddagunts.screencast.UPDATE_SYNC_CONFIG"
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
        const val EXTRA_SYNC_START = "sync_start"
        const val EXTRA_SYNC_INTERVAL_SEC = "sync_interval_sec"
        const val EXTRA_SYNC_DRIFT_MS = "sync_drift_ms"
        const val CHANNEL_ID = "cast"
        const val NOTIFICATION_ID = 1

        // Map keyed by device.host. Empty map == no active or errored casts.
        // The UI should render every value in this map.
        private val _devices = MutableStateFlow<Map<String, DeviceCast>>(emptyMap())
        val devicesFlow: StateFlow<Map<String, DeviceCast>> = _devices

        // Convenience — true whenever any pipeline resources are in use.
        // Derived by observers; service doesn't write it directly.
        val isPipelineActive: Boolean
            get() = _devices.value.isNotEmpty()
    }
}
