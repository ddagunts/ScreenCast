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
    // closeImePrompt(). TV pushes intentionally DO NOT mutate this —
    // auto-opening the sheet every time the TV reports a focused text
    // field was overwhelming, so the sheet is manual-only.
    private val _imePrompt = MutableStateFlow<AndroidTvImePrompt?>(null)
    val imePrompt: StateFlow<AndroidTvImePrompt?> = _imePrompt

    // The TV's last-known focused text field. Updated continuously from
    // ImeKeyInject / ImeShowRequest pushes. Used solely to seed _imePrompt
    // when the user manually opens the sheet, so they start editing what's
    // actually on the TV instead of a blank field. @Volatile because
    // writes happen from observeIncoming and reads happen from the UI
    // thread via openImePrompt().
    @Volatile private var lastTvField: AndroidTvImePrompt? = null

    // Diff base for outbound ImeBatchEdit: what we believe the TV's focused
    // field currently contains, given the edits we've already pushed.
    // Seeded by openImePrompt() from the TV's last-known field value,
    // advanced inside sendImeText() after each successful send, cleared on
    // close / disconnect. The session — not the UI sheet — owns this so
    // back-to-back sends from a fast typist can't race on the diff base.
    @Volatile private var lastSentText: String = ""

    // Counters carried on every outbound ImeBatchEdit. Per the canonical
    // androidtvremote2 (Python) library, BOTH counters are echo-driven:
    // they start at 0, are NEVER incremented by the sender, and are
    // updated only when the TV pushes a remote_ime_batch_edit message
    // back to us. The TV seeds them with its own values on the first
    // echo; subsequent sends play those back unchanged, the TV bumps and
    // echoes again, and the loop continues. We tried the opposite (bump
    // locally, ignore echoes) in an earlier iteration and the TV silently
    // dropped every edit — matching canonical Python is the highest-
    // confidence interpretation.
    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0

    // Serializes sendImeText() so the (read lastSentText → compute diff →
    // build message → write → advance lastSentText) sequence is atomic.
    // Without this, two viewModelScope.launch sends triggered by fast
    // typing could see the same lastSentText, both diff against it, and
    // emit overlapping span replacements that the TV applies as
    // duplicates.
    private val imeMutex = Mutex()

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

    // TV announced a text-field state. Cache it so a subsequent
    // openImePrompt() can pre-populate the sheet with what the TV
    // actually has focused.
    private fun handleImeKeyInject(msg: RemoteMessage.ImeKeyInject) {
        val status = msg.textFieldStatus
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

    // Adopt both counters from the TV's echo without ever regressing.
    // First echo seeds non-zero values; subsequent echoes confirm the TV
    // accepted our edit and advance the sequence. We never bump locally
    // (canonical Python pattern — see field declarations above).
    private fun handleImeBatchEditEcho(msg: RemoteMessage.ImeBatchEdit) {
        if (msg.imeCounter > imeCounter) imeCounter = msg.imeCounter
        if (msg.fieldCounter > fieldCounter) fieldCounter = msg.fieldCounter
        logD("ime echo: imeCtr=${msg.imeCounter} fieldCtr=${msg.fieldCounter} (local now ime=$imeCounter field=$fieldCounter)")
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
        // connect doesn't replay a stale prompt or stale counters
        // against a fresh session.
        _imePrompt.value = null
        lastTvField = null
        lastSentText = ""
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

    // Push the phone-side new text to the TV by diffing against the
    // last-sent text and emitting one RemoteImeBatchEdit with a single
    // span replacement. This is the canonical Android-TV-Remote-v2 text
    // entry mechanism — Bluetooth-keyboard-style RemoteKeyInject of
    // letter keycodes does NOT reach focused text fields on real TV
    // firmware (we tried; it silently does nothing).
    //
    // Counters: both ime_counter and field_counter ride on every frame.
    // Per canonical Python (tronikos/androidtvremote2), neither is ever
    // bumped by the sender — they ratchet up via TV echoes only.
    // Re-using the last echoed values on the next send is exactly what
    // the wild firmware expects.
    //
    // No-op if there's no active prompt — guards against the user
    // pasting before tapping the keyboard button, or a stale callback
    // firing after the sheet closed.
    //
    // Serialized by imeMutex so two viewModelScope.launch sends from
    // fast typing can't both diff against the same lastSentText.
    suspend fun sendImeText(newText: String) = imeMutex.withLock {
        val ch = channel ?: return@withLock run { logW("sendImeText: not connected") }
        if (_imePrompt.value == null) return@withLock run { logW("sendImeText: no active IME prompt") }
        if (newText == lastSentText) return@withLock
        val diff = computeImeDiff(lastSentText, newText) ?: return@withLock
        logI("sendImeText: \"$lastSentText\" → \"$newText\" diff=[${diff.start}..${diff.end})=\"${diff.value}\" imeCtr=$imeCounter fieldCtr=$fieldCounter")
        ch.send(RemoteMessage.ImeBatchEdit(
            imeCounter = imeCounter,
            fieldCounter = fieldCounter,
            editInfo = listOf(RemoteEditInfo(insert = 1, textFieldStatus = diff)),
        ))
        lastSentText = newText
    }

    // Submit / "Done" — sends KEYCODE_ENTER, which is what the on-screen
    // soft keyboard fires for IME_ACTION_DONE. Most TV search fields treat
    // this as "commit the query". Distinct from DPadCenter (which is the
    // generic "click" focus dispatcher).
    suspend fun sendImeEnter() = sendKey(AndroidTvKey.Enter)

    // Single-character backspace. Routed through the same ImeBatchEdit
    // path as sendImeText() so we keep the diff base in sync: an empty-
    // value span replacement of the trailing char. Exposed to the UI as
    // an explicit button because some phone IMEs swallow the soft
    // backspace at end-of-buffer instead of firing onValueChange.
    suspend fun sendImeBackspace() = imeMutex.withLock {
        val ch = channel ?: return@withLock run { logW("sendImeBackspace: not connected") }
        if (_imePrompt.value == null) return@withLock run { logW("sendImeBackspace: no active IME prompt") }
        if (lastSentText.isEmpty()) return@withLock
        val newText = lastSentText.dropLast(1)
        val diff = RemoteImeObject(start = newText.length, end = lastSentText.length, value = "")
        logI("sendImeBackspace: \"$lastSentText\" → \"$newText\" imeCtr=$imeCounter fieldCtr=$fieldCounter")
        ch.send(RemoteMessage.ImeBatchEdit(
            imeCounter = imeCounter,
            fieldCounter = fieldCounter,
            editInfo = listOf(RemoteEditInfo(insert = 1, textFieldStatus = diff)),
        ))
        lastSentText = newText
    }

    // UI hook: user tapped the manual "keyboard" button. Seeds the sheet
    // from the TV's last-known focused field if we have one cached (so the
    // user starts editing what's actually on the TV); otherwise opens an
    // empty prompt and the first character lands wherever the TV currently
    // has focus. lastSentText is seeded with the TV value so the first
    // diff is computed from the right base.
    fun openImePrompt() {
        if (_imePrompt.value != null) return
        val seed = lastTvField ?: AndroidTvImePrompt(
            appPackage = "",
            appLabel = "",
            label = "",
            value = "",
            selectionStart = 0,
            selectionEnd = 0,
        )
        lastSentText = seed.value
        _imePrompt.value = seed
    }

    fun closeImePrompt() {
        _imePrompt.value = null
        lastSentText = ""
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }
}
