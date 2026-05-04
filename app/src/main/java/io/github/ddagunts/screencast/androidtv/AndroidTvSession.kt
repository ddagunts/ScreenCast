package io.github.ddagunts.screencast.androidtv

import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class AndroidTvState {
    data object Idle : AndroidTvState()
    data object Connecting : AndroidTvState()
    data object Active : AndroidTvState()
    data class Reconnecting(val attempt: Int) : AndroidTvState()
    data class Error(val message: String) : AndroidTvState()
}

data class AndroidTvVolume(
    val level: Int = 0,
    val max: Int = 100,
    val muted: Boolean = false,
) {
    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max.toFloat()
}

// Lifecycle FSM around AndroidTvRemoteChannel. Owns:
//  * The connect/handshake sequence (Configure + SetActive — without those
//    many TVs silently drop subsequent input).
//  * Reconnect-with-backoff on transport drop.
//  * Volume StateFlow, kept in sync with both sides — TV pushes whenever
//    the system volume changes (including from its own remote), and we
//    push when the user moves our slider.
//  * App-launch + key-inject routing through the channel's write mutex.
class AndroidTvSession(
    val device: AndroidTvDevice,
    private val clientMaterial: AndroidTvCertFactory.Material,
    private val serverCertPin: String,
    private val deviceModel: String = "ScreenCast",
) {
    private var channel: AndroidTvRemoteChannel? = null
    private var readerJob: Job? = null
    private var scope: CoroutineScope? = null
    private val openLock = Mutex()
    @Volatile private var manuallyClosed = false

    private val _state = MutableStateFlow<AndroidTvState>(AndroidTvState.Idle)
    val state: StateFlow<AndroidTvState> = _state

    private val _volume = MutableStateFlow(AndroidTvVolume())
    val volume: StateFlow<AndroidTvVolume> = _volume

    // Connect runs the blocking TLS handshake + the polo handshake messages,
    // so we always switch to Dispatchers.IO. Without this, callers on the
    // Main dispatcher (the ViewModel coroutines) would either freeze or
    // throw NetworkOnMainThreadException — and the latter happens silently
    // inside attemptConnect's catch, sending the session into an unlogged
    // reconnect loop. The withContext here is the load-bearing fix.
    suspend fun connect() = withContext(Dispatchers.IO) {
        openLock.withLock {
            logI("session.connect() entry; current state = ${_state.value}")
            if (_state.value is AndroidTvState.Active) {
                logI("session.connect() already Active, no-op")
                return@withLock
            }
            manuallyClosed = false
            attemptConnect(attempt = 0)
        }
    }

    private suspend fun attemptConnect(attempt: Int) {
        logI("attemptConnect(attempt=$attempt) starting → ${device.host}:${device.port}")
        _state.value = if (attempt == 0) AndroidTvState.Connecting else AndroidTvState.Reconnecting(attempt)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        val ch = AndroidTvRemoteChannel(device.host, device.port, clientMaterial, serverCertPin)
        try {
            logD("attemptConnect: opening TLS to remote port")
            readerJob = ch.connect { e -> onTransportClosed(e) }
            channel = ch
            logD("attemptConnect: TLS up, sending RemoteConfigure (code1=622)")
            // Handshake: Configure carries device info; SetActive turns
            // the channel "on". The magic constant 622 matches every
            // open-source sender (tronikos, atvremote-py, Google Home);
            // its purpose is undocumented but TVs require it.
            ch.send(RemoteMessage.Configure(
                code1 = 622,
                deviceInfo = RemoteDeviceInfo(
                    model = deviceModel,
                    vendor = "ScreenCast",
                    unknown1 = 1,
                    unknown2 = "1",
                    packageName = "io.github.ddagunts.screencast",
                    appVersion = "1.0.0",
                ),
            ))
            logD("attemptConnect: sending RemoteSetActive (active=622)")
            ch.send(RemoteMessage.SetActive(active = 622))

            sc.launch { observeIncoming(ch) }
            _state.value = AndroidTvState.Active
            logI("ATV session active to ${device.host}")
        } catch (t: Throwable) {
            logE("attemptConnect failed for ${device.host}:${device.port}", t)
            ch.close()
            channel = null
            if (manuallyClosed) {
                _state.value = AndroidTvState.Idle
                return
            }
            scheduleReconnect(attempt + 1, t)
        }
    }

    private fun onTransportClosed(e: Throwable?) {
        if (manuallyClosed) {
            _state.value = AndroidTvState.Idle
            return
        }
        logW("ATV transport closed: ${e?.message}")
        // Schedule reconnect on a fresh scope; the current channel scope
        // is being torn down by the read-loop unwind.
        val sc = CoroutineScope(Dispatchers.IO)
        sc.launch { scheduleReconnect(1, e ?: Throwable("transport closed")) }
    }

    private suspend fun scheduleReconnect(attempt: Int, cause: Throwable) {
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            logE("ATV ${device.host}: gave up after $MAX_RECONNECT_ATTEMPTS reconnect attempts", cause)
            _state.value = AndroidTvState.Error(cause.message ?: "ATV connection lost")
            return
        }
        // Exponential backoff capped at 10 s. Matches Cast V2 reconnect
        // shape; lower than Wi-Fi roaming jitter would burn CPU for no
        // gain since the user's already aware (the screen banner shows
        // the attempt counter).
        val delayMs = (1_000L shl (attempt - 1).coerceAtMost(3)).coerceAtMost(10_000L)
        logW("scheduleReconnect attempt=$attempt delay=${delayMs}ms (cause: ${cause.message})")
        _state.value = AndroidTvState.Reconnecting(attempt)
        delay(delayMs)
        try { attemptConnect(attempt) } catch (e: CancellationException) { throw e }
        catch (t: Throwable) { scheduleReconnect(attempt + 1, t) }
    }

    private suspend fun observeIncoming(ch: AndroidTvRemoteChannel) {
        ch.incoming.collect { msg ->
            when (msg) {
                is RemoteMessage.SetVolumeLevel -> {
                    _volume.value = AndroidTvVolume(msg.volumeLevel, msg.volumeMax, msg.volumeMuted)
                }
                is RemoteMessage.StartedNotification -> {
                    logI("ATV ${device.host} app start notification: started=${msg.started}")
                }
                is RemoteMessage.Error -> {
                    logE("ATV remote error: ${msg.message}")
                }
                else -> Unit
            }
        }
    }

    fun disconnect() {
        manuallyClosed = true
        readerJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        scope?.cancel()
        scope = null
        _state.value = AndroidTvState.Idle
    }

    suspend fun sendKey(key: AndroidTvKey, direction: RemoteDirection = RemoteDirection.SHORT) {
        val ch = channel ?: return logW("sendKey: not connected")
        ch.send(RemoteMessage.KeyInject(key.wire, direction))
    }

    suspend fun keyDown(key: AndroidTvKey) = sendKey(key, RemoteDirection.START_LONG)
    suspend fun keyUp(key: AndroidTvKey) = sendKey(key, RemoteDirection.END_LONG)

    // Press-and-hold a key for `holdMs`. Used for "long-press Home"
    // (which on Sony BRAVIA opens the Action Menu where Settings lives)
    // and similar physical-remote gestures. Sends JUST down + up — no
    // SHORT in between — because mixing all three for the same keycode
    // makes the TV close the socket as malformed input.
    suspend fun longPress(key: AndroidTvKey, holdMs: Long = 800L) {
        sendKey(key, RemoteDirection.START_LONG)
        delay(holdMs)
        sendKey(key, RemoteDirection.END_LONG)
    }

    // Setting the absolute level with RemoteSetVolumeLevel; the TV mirrors
    // back via the SetVolumeLevel inbound push, so the slider settles to
    // the actual achieved level (not the requested one) as the StateFlow
    // updates. `levelFraction` is clamped to [0,1].
    suspend fun setVolume(levelFraction: Float) {
        val ch = channel ?: return logW("setVolume: not connected")
        val v = _volume.value
        val target = (levelFraction.coerceIn(0f, 1f) * v.max).toInt()
        ch.send(RemoteMessage.SetVolumeLevel(
            playerModel = "",
            volumeLevel = target,
            volumeMax = v.max,
            volumeMuted = v.muted,
        ))
    }

    suspend fun setMuted(muted: Boolean) {
        // No dedicated mute message — VOLUME_MUTE key works on every TV.
        sendKey(AndroidTvKey.Mute)
    }

    suspend fun launchApp(uri: String) {
        val ch = channel ?: return logW("launchApp: not connected")
        ch.send(RemoteMessage.AppLinkLaunchRequest(uri))
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }
}
