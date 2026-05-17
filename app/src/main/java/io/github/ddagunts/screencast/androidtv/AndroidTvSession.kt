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

// Snapshot of an active text field on the TV. Surfaced as a StateFlow so
// the UI can auto-open its IME bottom sheet when this turns non-null and
// dismiss when it goes null. `value`/`selectionStart`/`selectionEnd`
// reflect the TV's last push — the phone sheet should pre-populate from
// these so the user is editing what's actually on the TV. `label` is the
// field's hint text (e.g. "Search YouTube") when the TV provides one;
// `appPackage` is the foreground app that owns the field.
data class AndroidTvImePrompt(
    val appPackage: String,
    val appLabel: String,
    val label: String,
    val value: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

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

    // What the phone's IME bottom sheet is currently editing. Non-null
    // iff the sheet should be visible. Only ever set by openImePrompt() /
    // closeImePrompt() / sendImeText() (the last optimistically tracks
    // what we just sent so the next diff is computed against it). TV
    // pushes intentionally DO NOT mutate this — auto-opening the sheet
    // every time the TV reports a focused text field was overwhelming,
    // so the sheet is now manual-only.
    private val _imePrompt = MutableStateFlow<AndroidTvImePrompt?>(null)
    val imePrompt: StateFlow<AndroidTvImePrompt?> = _imePrompt

    // The TV's last-known focused text field (value, selection, app, label
    // counters). Updated continuously from ImeKeyInject / ImeShowRequest
    // pushes. Used to seed _imePrompt when the user manually opens the
    // sheet, so they start editing what's actually on the TV instead of a
    // blank field. @Volatile because writes happen from observeIncoming
    // and reads happen from the UI thread via openImePrompt().
    @Volatile private var lastTvField: AndroidTvImePrompt? = null

    // Sequence counters echoed in every outbound ImeBatchEdit. `imeCounter`
    // increments per phone-originated edit, monotonically across the
    // session; `fieldCounter` mirrors the TV's per-field counter from the
    // last push. We bump the TV's echoed `imeCounter` on each receive so
    // we always send the latest value back. Volatile because reads happen
    // in sendImeText (caller's coroutine) and writes happen in
    // observeIncoming (session scope) — different threads.
    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0

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
                is RemoteMessage.ImeKeyInject -> handleImeKeyInject(msg)
                is RemoteMessage.ImeShowRequest -> handleImeShowRequest(msg)
                is RemoteMessage.ImeBatchEdit -> handleImeBatchEditEcho(msg)
                else -> Unit
            }
        }
    }

    // TV announced a text-field state. Update the per-field counter and
    // cache the field so a subsequent openImePrompt() can pre-populate.
    // Does NOT touch _imePrompt — the sheet is manual-only.
    private fun handleImeKeyInject(msg: RemoteMessage.ImeKeyInject) {
        val status = msg.textFieldStatus
        if (status.counterField != 0) fieldCounter = status.counterField
        lastTvField = AndroidTvImePrompt(
            appPackage = msg.appInfo.appPackage,
            appLabel = msg.appInfo.label,
            label = status.label,
            value = status.value,
            selectionStart = status.start,
            selectionEnd = status.end,
        )
    }

    private fun handleImeShowRequest(msg: RemoteMessage.ImeShowRequest) {
        val status = msg.textFieldStatus
        if (status.counterField != 0) fieldCounter = status.counterField
        // ImeShowRequest arrives without app_info — preserve whatever
        // ImeKeyInject most recently cached.
        val existing = lastTvField
        lastTvField = AndroidTvImePrompt(
            appPackage = existing?.appPackage ?: "",
            appLabel = existing?.appLabel ?: "",
            label = status.label.ifEmpty { existing?.label ?: "" },
            value = status.value,
            selectionStart = status.start,
            selectionEnd = status.end,
        )
    }

    // TV echoes our edit back with updated counters. We adopt them so the
    // next outbound edit carries the latest sequence numbers. We do NOT
    // re-mirror the value into _imePrompt — that would clobber whatever
    // the user has typed since this echo was in flight.
    private fun handleImeBatchEditEcho(msg: RemoteMessage.ImeBatchEdit) {
        if (msg.imeCounter != 0) imeCounter = msg.imeCounter
        if (msg.fieldCounter != 0) fieldCounter = msg.fieldCounter
    }

    fun disconnect() {
        manuallyClosed = true
        readerJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        scope?.cancel()
        scope = null
        _state.value = AndroidTvState.Idle
        // IME state belongs to the connection — drop it so the next
        // connect doesn't replay a stale prompt against a fresh session.
        _imePrompt.value = null
        lastTvField = null
        imeCounter = 0
        fieldCounter = 0
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

    // Push the phone-side new text to the TV by diffing against the prompt's
    // last-known TV value and sending one RemoteImeBatchEdit. Optimistically
    // updates _imePrompt.value to `newText` so the bottom sheet stays in
    // sync with what we just sent — the TV's echo will resync counters but
    // not the value (which the user may have typed further by then).
    //
    // No-op if no prompt is active (caller shouldn't call this in that
    // state, but the guard keeps us safe against races with the TV pushing
    // a "field unfocused" right as the user submits).
    suspend fun sendImeText(newText: String) {
        val ch = channel ?: return logW("sendImeText: not connected")
        val prompt = _imePrompt.value ?: return logW("sendImeText: no active IME prompt")
        val diff = computeImeDiff(prompt.value, newText)
        if (diff == null) {
            logI("sendImeText: no diff (prompt.value=${prompt.value.length}ch, newText=${newText.length}ch)")
            return
        }
        imeCounter += 1
        logI("sendImeText: prompt.value=\"${prompt.value}\" newText=\"$newText\" → diff [${diff.start}..${diff.end})=\"${diff.value}\" imeCtr=$imeCounter fieldCtr=$fieldCounter")
        ch.send(RemoteMessage.ImeBatchEdit(
            imeCounter = imeCounter,
            fieldCounter = fieldCounter,
            editInfo = listOf(RemoteEditInfo(insert = 1, textFieldStatus = diff)),
        ))
        // Optimistic local update so subsequent diffs are computed against
        // the text we just sent — without this, every keystroke would diff
        // against the original TV value and re-send the entire edit.
        _imePrompt.value = prompt.copy(
            value = newText,
            selectionStart = newText.length,
            selectionEnd = newText.length,
        )
    }

    // Submit / "Done" — sends KEYCODE_ENTER, which is what the on-screen
    // soft keyboard fires for IME_ACTION_DONE. Most TV search fields treat
    // this as "commit the query". Distinct from DPadCenter (which is the
    // generic "click" focus dispatcher).
    suspend fun sendImeEnter() = sendKey(AndroidTvKey.Enter)

    // UI hook: user tapped the manual "keyboard" button. Seeds the sheet
    // from the TV's last-known focused field if we have one cached (so the
    // user starts editing what's actually on the TV); otherwise opens an
    // empty prompt and the first character will insert at position 0 —
    // correct iff the TV has an empty text field focused.
    fun openImePrompt() {
        if (_imePrompt.value != null) return
        _imePrompt.value = lastTvField ?: AndroidTvImePrompt(
            appPackage = "",
            appLabel = "",
            label = "",
            value = "",
            selectionStart = 0,
            selectionEnd = 0,
        )
    }

    fun closeImePrompt() {
        _imePrompt.value = null
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }
}
