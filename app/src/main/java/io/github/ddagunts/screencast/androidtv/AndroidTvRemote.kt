package io.github.ddagunts.screencast.androidtv

import android.content.Context
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Single-class façade the UI / VM consumes for one Android TV device.
//
// Wraps:
//   * the per-app cert store (read-only from here — the cert is a
//     singleton across all paired TVs),
//   * the pairing channel (used once per TV, on first contact),
//   * the session (used for the lifetime of the connection).
//
// Lifecycle: callers can `pair()` on a never-paired device (which
// implicitly persists the server cert pin on success), `connect()` on a
// paired device, and `disconnect()` whenever. Re-pairing after a TV
// reset goes through `pair()` again — the pairing channel will pin the
// new server cert, replacing whatever was stored before.
class AndroidTvRemote(
    private val context: Context,
    val device: AndroidTvDevice,
    private val certStore: AndroidTvCertStore = AndroidTvCertStore(context),
) {

    // Stable StateFlows the UI can collect once and never re-subscribe.
    // Earlier versions returned `session?.state ?: _state` from a getter,
    // which evaluated lazily — so a collector that subscribed before
    // connect() pinned itself to the placeholder _state forever and
    // never saw updates from the live session. Now we pipe the session's
    // flows into ours via a long-lived background scope inside the
    // facade.
    private val _state = MutableStateFlow<AndroidTvState>(AndroidTvState.Idle)
    val state: StateFlow<AndroidTvState> = _state

    private val _volume = MutableStateFlow(AndroidTvVolume())
    val volume: StateFlow<AndroidTvVolume> = _volume

    @Volatile private var session: AndroidTvSession? = null
    private var pipeScope: CoroutineScope? = null

    val isPaired: Boolean get() = certStore.getServerPin(device.host) != null

    // Run the pairing handshake. Suspends inside the handshake at the
    // CONFIGURATION_ACK boundary until `onCodePrompt` returns the code
    // typed by the user (or null to cancel). Persists the server cert
    // SHA-256 on success — a subsequent connect() will use it as the pin.
    suspend fun pair(onCodePrompt: suspend () -> String?): Result<Unit> {
        val material = certStore.getOrCreateClient()
        val channel = AndroidTvPairingChannel(
            host = device.host,
            pairingPort = device.pairingPort,
            clientMaterial = material,
        )
        return channel.pair(onCodePrompt).fold(
            onSuccess = { result ->
                certStore.pinServer(device.host, result.serverCertSha256)
                logI("paired with ${device.host}, pin = ${result.serverCertSha256}")
                Result.success(Unit)
            },
            onFailure = { e ->
                logE("pairing failed for ${device.host}", e)
                Result.failure(e)
            },
        )
    }

    suspend fun connect() {
        logI("AndroidTvRemote.connect() entry for ${device.host}")
        val pin = certStore.getServerPin(device.host)
            ?: return run {
                logW("connect() called on unpaired device ${device.host}")
                _state.value = AndroidTvState.Error("Not paired")
            }
        logI("AndroidTvRemote.connect(): pin found, building session")
        val material = certStore.getOrCreateClient()
        val s = session ?: AndroidTvSession(device, material, pin).also { newSession ->
            session = newSession
            // Pipe session flows into our stable _state / _volume flows.
            // Long-lived collector lives until disconnect/unpair tears the
            // pipeScope down. SupervisorJob so a transient collector
            // failure on one flow doesn't kill the other.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            pipeScope = scope
            scope.launch { newSession.state.collect { _state.value = it } }
            scope.launch { newSession.volume.collect { _volume.value = it } }
        }
        s.connect()
        logI("AndroidTvRemote.connect() returned, session state = ${s.state.value}")
    }

    fun disconnect() {
        session?.disconnect()
    }

    // Forget the pairing for this TV (locally only — the TV still
    // remembers our cert until it evicts it from its own list, which
    // happens after ~10 new clients pair). The next connect() call will
    // refuse; the next pair() will issue a fresh server-cert pin.
    fun unpair() {
        disconnect()
        certStore.forget(device.host)
        pipeScope?.cancel()
        pipeScope = null
        session = null
    }

    suspend fun sendKey(key: AndroidTvKey) { session?.sendKey(key) }
    suspend fun keyDown(key: AndroidTvKey) { session?.keyDown(key) }
    suspend fun keyUp(key: AndroidTvKey) { session?.keyUp(key) }
    suspend fun longPress(key: AndroidTvKey, holdMs: Long = 800L) { session?.longPress(key, holdMs) }
    suspend fun setVolume(level: Float) { session?.setVolume(level) }
    suspend fun setMuted(muted: Boolean) { session?.setMuted(muted) }
    suspend fun launchApp(uri: String) { session?.launchApp(uri) }
}
