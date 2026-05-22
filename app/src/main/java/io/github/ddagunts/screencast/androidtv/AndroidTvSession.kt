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

    // Cumulative reconnect attempts since the last steady-state connection.
    // The reason this is a session field rather than a parameter threaded
    // through attemptConnect: the prior design's `onTransportClosed` always
    // restarted at attempt=1, so backoff never grew and MAX_RECONNECT_ATTEMPTS
    // was never reached. Now we own the counter here, bump it on every
    // unexpected close, and reset only after stability — see [stabilityJob].
    // Guarded by [openLock].
    private var consecutiveFailures = 0

    // Single-flight guard for the reconnect chain. If a chain is already
    // pending/in-flight, additional transport-close events are ignored — they
    // can pile up when our own teardown of a stale channel triggers its read
    // loop's onClose callback. Without the guard, each such fire spawned an
    // independent reconnect chain and the connect rate to the TV doubled
    // (then tripled, etc.) every cycle.
    @Volatile private var reconnectJob: Job? = null

    // Reset [consecutiveFailures] back to 0 if we stay Active for this long.
    // Cancelled on any close so a brief flap doesn't reset mid-storm.
    private var stabilityJob: Job? = null

    // Long-lived scope for reconnect + stability coroutines. Outlives any
    // single attempt's [scope] because we need to spawn the next reconnect
    // from a callback fired during the previous attempt's teardown.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Counters carried on every outbound ImeBatchEdit. Per the canonical
    // androidtvremote2 (Python) library, BOTH counters are echo-driven:
    // they start at 0, are NEVER incremented by the sender, and are
    // updated only when the TV pushes a remote_ime_batch_edit message
    // back to us. We tried the opposite (bump locally) and the TV
    // silently dropped every edit.
    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0

    // Connect runs the blocking TLS handshake + the polo handshake messages,
    // so we always switch to Dispatchers.IO. Without this, callers on the
    // Main dispatcher (the ViewModel coroutines) would either freeze or
    // throw NetworkOnMainThreadException — and the latter happens silently
    // inside attemptConnect's catch, sending the session into an unlogged
    // reconnect loop. The withContext here is the load-bearing fix.
    suspend fun connect() = withContext(Dispatchers.IO) {
        // Cancel any in-flight reconnect chain BEFORE acquiring the lock —
        // the chain holds openLock during its own attemptConnect calls, so
        // taking the lock first would deadlock waiting for ourselves.
        reconnectJob?.cancel()
        stabilityJob?.cancel()
        openLock.withLock {
            logI("session.connect() entry; current state = ${_state.value}")
            if (_state.value is AndroidTvState.Active) {
                logI("session.connect() already Active, no-op")
                return@withLock
            }
            manuallyClosed = false
            consecutiveFailures = 0
            try {
                attemptConnect(attempt = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Initial connect failed (TLS / handshake). Kick off the
                // reconnect chain inline so the UI sees Reconnecting state
                // grow through the same backoff curve as a transport drop.
                consecutiveFailures = 1
                scheduleReconnect(consecutiveFailures, t)
            }
        }
    }

    private suspend fun attemptConnect(attempt: Int) {
        logI("attemptConnect(attempt=$attempt) starting → ${device.host}:${device.port}")
        _state.value = if (attempt == 0) AndroidTvState.Connecting else AndroidTvState.Reconnecting(attempt)

        // Tear down any prior channel/scope before opening a new one. Without
        // this, the previous attempt's socket stays half-open and its read
        // loop eventually fires onTransportClosed — competing with the new
        // channel for reconnect scheduling.
        readerJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        scope?.cancel()

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        val ch = AndroidTvRemoteChannel(device.host, device.port, clientMaterial, serverCertPin)
        // Assign `channel = ch` before ch.connect so the onTransportClosed
        // identity check (`closingChannel !== channel`) treats THIS channel
        // as current even if its read loop fires immediately after launch.
        channel = ch
        try {
            logD("attemptConnect: opening TLS to remote port")
            // Pass `ch` through the closure so onTransportClosed can identify
            // which channel reported the close — needed because our explicit
            // teardown of a stale channel above also triggers its onClose,
            // and we must ignore those stale fires.
            readerJob = ch.connect { e -> onTransportClosed(ch, e) }
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

            // Reset the failure counter once we've been Active for
            // STABILITY_GRACE_MS — a quick drop right after Active (e.g. the
            // Basement TV rejection pattern: handshake → RemoteError → close)
            // should keep growing the backoff so we eventually surface Error,
            // not silently loop forever at 1s intervals.
            stabilityJob?.cancel()
            stabilityJob = backgroundScope.launch {
                delay(STABILITY_GRACE_MS)
                openLock.withLock {
                    if (_state.value is AndroidTvState.Active && consecutiveFailures > 0) {
                        logI("connection stable for ${STABILITY_GRACE_MS}ms; reset failure counter")
                        consecutiveFailures = 0
                    }
                }
            }
        } catch (t: Throwable) {
            logE("attemptConnect failed for ${device.host}:${device.port}", t)
            runCatching { ch.close() }
            if (channel === ch) channel = null
            if (manuallyClosed) {
                _state.value = AndroidTvState.Idle
                return
            }
            // attemptConnect-thrown failures (TLS handshake, send errors)
            // count against the same budget as transport closes. Bump and
            // retry from the catch in scheduleReconnect.
            throw t
        }
    }

    private fun onTransportClosed(closingChannel: AndroidTvRemoteChannel, e: Throwable?) {
        // Stale fire: our own teardown of a prior channel in attemptConnect
        // triggers that channel's read loop to unwind and call onClose. Those
        // calls used to spawn their own reconnect chains, racing the chain
        // we actually wanted. Filter by identity.
        if (closingChannel !== channel) {
            logD("transport closed for stale channel; ignoring")
            return
        }
        if (manuallyClosed) {
            _state.value = AndroidTvState.Idle
            return
        }
        logW("ATV transport closed: ${e?.message}")
        // Single-flight: if a chain is already running (or queued behind
        // openLock), don't start another. The earlier design launched a
        // fresh CoroutineScope per close and each one independently scheduled
        // a reconnect — every successful-then-immediately-failed attempt
        // doubled the number of parallel chains, hammering the TV.
        if (reconnectJob?.isActive == true) {
            logD("reconnect already in flight; not scheduling another")
            return
        }
        reconnectJob = backgroundScope.launch {
            openLock.withLock {
                if (manuallyClosed) return@withLock
                consecutiveFailures += 1
                scheduleReconnect(consecutiveFailures, e ?: Throwable("transport closed"))
            }
        }
    }

    private suspend fun scheduleReconnect(attempt: Int, cause: Throwable) {
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            logE("ATV ${device.host}: gave up after $MAX_RECONNECT_ATTEMPTS reconnect attempts", cause)
            _state.value = AndroidTvState.Error(cause.message ?: "ATV connection lost")
            // Tear down so a half-open socket doesn't keep firing onClose.
            readerJob?.cancel()
            runCatching { channel?.close() }
            channel = null
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
        if (manuallyClosed) return
        try {
            attemptConnect(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Bump the cumulative counter (not the local `attempt`) so that
            // any in-flight transport-close events that also incremented it
            // are reflected. Without this we'd retry the same backoff slot
            // twice.
            consecutiveFailures += 1
            scheduleReconnect(consecutiveFailures, t)
        }
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
        reconnectJob?.cancel(); reconnectJob = null
        stabilityJob?.cancel(); stabilityJob = null
        readerJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        scope?.cancel()
        scope = null
        consecutiveFailures = 0
        _state.value = AndroidTvState.Idle
        // IME state belongs to the connection — drop it so the next
        // connect doesn't replay a stale prompt or stale counters
        // against a fresh session.
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

    // Push the phone-side full text buffer to the TV as one
    // RemoteImeBatchEdit. Matches canonical androidtvremote2 (Python)
    // `send_text` exactly: `value` is the FULL buffer, `start == end ==
    // len(value) - 1`. This is NOT a span replacement in the usual sense —
    // the wild firmware reads it as "set the field's full value to `value`
    // with cursor at len-1" and diffs against its own previous state. An
    // earlier diff-based shape (insert/delete only the changed slice) was
    // silently dropped by the TV: the IME sheet would open with the right
    // app focused but no letters ever appeared.
    //
    // Counters: both ime_counter and field_counter ride on every frame.
    // Per canonical Python, neither is ever bumped by the sender — they
    // ratchet up via TV echoes only and stay at 0 until the first echo.
    //
    // No-op on empty input: canonical Python raises on empty, and an
    // encoded `start=-1` would land as a huge varint on the wire. The
    // sheet's Backspace button mutates its own TextFieldValue down to
    // empty and we just stop sending when there's nothing to mirror;
    // the TV keeps whatever single char it has until the user types
    // again or closes the sheet.
    suspend fun sendImeText(newText: String) {
        val ch = channel ?: return logW("sendImeText: not connected")
        if (_imePrompt.value == null) return logW("sendImeText: no active IME prompt")
        if (newText.isEmpty()) return
        val pos = newText.length - 1
        logI("sendImeText: \"$newText\" pos=$pos imeCtr=$imeCounter fieldCtr=$fieldCounter")
        ch.send(RemoteMessage.ImeBatchEdit(
            imeCounter = imeCounter,
            fieldCounter = fieldCounter,
            editInfo = listOf(RemoteEditInfo(
                insert = 1,
                textFieldStatus = RemoteImeObject(start = pos, end = pos, value = newText),
            )),
        ))
    }

    // Submit / "Done" — sends KEYCODE_ENTER, which is what the on-screen
    // soft keyboard fires for IME_ACTION_DONE. Most TV search fields treat
    // this as "commit the query". Distinct from DPadCenter (which is the
    // generic "click" focus dispatcher).
    suspend fun sendImeEnter() = sendKey(AndroidTvKey.Enter)

    // The sheet's Backspace button updates its own TextFieldValue and the
    // change re-triggers the debounced send through sendImeText with the
    // already-shortened full buffer, so there's nothing to do here at the
    // wire level. Kept as a hook for the UI so the button still has a
    // semantic call site.
    suspend fun sendImeBackspace() {}

    // UI hook: user tapped the manual "keyboard" button. Seeds the sheet
    // from the TV's last-known focused field if we have one cached (so the
    // user starts editing what's actually on the TV); otherwise opens an
    // empty prompt and the first character lands wherever the TV currently
    // has focus.
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
        // After this long in Active state, treat the connection as stable
        // and zero the consecutiveFailures counter. Picked larger than the
        // observed "TV rejects right after handshake" window (~50 ms per
        // cycle in the Basement TV trace) but small enough that a real
        // long-running session resets promptly.
        private const val STABILITY_GRACE_MS = 10_000L
    }
}
