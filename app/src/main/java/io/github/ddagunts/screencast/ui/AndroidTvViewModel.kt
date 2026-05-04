package io.github.ddagunts.screencast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.androidtv.AndroidTvCertStore
import io.github.ddagunts.screencast.androidtv.AndroidTvDevice
import io.github.ddagunts.screencast.androidtv.AndroidTvDiscovery
import io.github.ddagunts.screencast.androidtv.AndroidTvKey
import io.github.ddagunts.screencast.androidtv.AndroidTvPersistence
import io.github.ddagunts.screencast.androidtv.AndroidTvRemote
import io.github.ddagunts.screencast.androidtv.AndroidTvState
import io.github.ddagunts.screencast.androidtv.AndroidTvVolume
import io.github.ddagunts.screencast.util.logE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AndroidTvViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = AndroidTvDiscovery(app).also { it.start() }
    val discovered: StateFlow<List<AndroidTvDevice>> = discovery.flow

    private val certStore = AndroidTvCertStore(app)
    private val persistence = AndroidTvPersistence(app)

    private val _paired = MutableStateFlow(persistence.list())
    val paired: StateFlow<List<AndroidTvPersistence.Entry>> = _paired

    // One AndroidTvRemote per host. Keeping these alive across screen
    // toggles avoids having to re-handshake every time the user pops back
    // into the remote screen — most users will toggle in/out frequently.
    // We only tear them down when the VM is cleared.
    private val remotes = mutableMapOf<String, AndroidTvRemote>()

    private val _currentHost = MutableStateFlow<String?>(null)
    val currentHost: StateFlow<String?> = _currentHost

    private val _currentState = MutableStateFlow<AndroidTvState>(AndroidTvState.Idle)
    val currentState: StateFlow<AndroidTvState> = _currentState

    private val _currentVolume = MutableStateFlow(AndroidTvVolume())
    val currentVolume: StateFlow<AndroidTvVolume> = _currentVolume

    // Pairing UI plumbing: when a pair() runs it'll set this prompt, the
    // dialog observes it and resumes the deferred when the user submits.
    // Using a single in-flight prompt is safe — we only ever start one
    // pairing at a time, and starting another while one is open replaces
    // (cancels) the previous one.
    data class PairingPrompt(
        val device: AndroidTvDevice,
        val deferred: CompletableDeferred<String?>,
    )
    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val pairingPrompt: StateFlow<PairingPrompt?> = _prompt

    fun selectDevice(device: AndroidTvDevice) {
        _currentHost.value = device.host
        val remote = remoteFor(device)
        viewModelScope.launch {
            remote.state.collect { _currentState.value = it }
        }
        viewModelScope.launch {
            remote.volume.collect { _currentVolume.value = it }
        }
        if (remote.isPaired) {
            viewModelScope.launch {
                runCatching { remote.connect() }
                    .onFailure { logE("connect failed for ${device.host}", it) }
            }
        }
    }

    fun startPairing(device: AndroidTvDevice) {
        // Replace any in-flight prompt — UI invariant is "at most one
        // pairing dialog open at a time".
        _prompt.value?.deferred?.complete(null)
        val remote = remoteFor(device)
        viewModelScope.launch {
            val result = remote.pair { promptForCode(device) }
            if (result.isSuccess) {
                persistence.upsert(device)
                _paired.value = persistence.list()
                runCatching { remote.connect() }
            }
        }
    }

    private suspend fun promptForCode(device: AndroidTvDevice): String? {
        val deferred = CompletableDeferred<String?>()
        _prompt.value = PairingPrompt(device, deferred)
        return try {
            deferred.await()
        } finally {
            _prompt.value = null
        }
    }

    fun submitPairingCode(code: String) {
        _prompt.value?.deferred?.complete(code)
    }

    fun cancelPairing() {
        _prompt.value?.deferred?.complete(null)
    }

    fun unpair(host: String) {
        val r = remotes.remove(host) ?: return
        r.unpair()
        // Find the persistence entry for this host (key may be a BLE MAC
        // rather than the host string) so we can forget by stable key.
        _paired.value.firstOrNull { it.host == host }?.let { persistence.forget(it.key) }
        _paired.value = persistence.list()
        if (_currentHost.value == host) {
            _currentHost.value = null
            _currentState.value = AndroidTvState.Idle
        }
    }

    fun connectCurrent() {
        val host = _currentHost.value ?: return
        val remote = remotes[host] ?: return
        viewModelScope.launch { runCatching { remote.connect() } }
    }

    fun disconnectCurrent() {
        val host = _currentHost.value ?: return
        remotes[host]?.disconnect()
    }

    // Every UI-triggered remote action gets its own try/catch so a
    // transient socket failure (broken pipe, EOF after TV reboot) just
    // logs and lets the session's reconnect logic recover, rather than
    // bubbling out of viewModelScope.launch and crashing the app.
    private fun launchSafe(name: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { logE("$name failed", it) }
        }
    }

    fun sendKey(key: AndroidTvKey) {
        val host = _currentHost.value ?: return
        launchSafe("sendKey($key)") { remotes[host]?.sendKey(key) }
    }

    fun keyDown(key: AndroidTvKey) {
        val host = _currentHost.value ?: return
        launchSafe("keyDown($key)") { remotes[host]?.keyDown(key) }
    }

    fun keyUp(key: AndroidTvKey) {
        val host = _currentHost.value ?: return
        launchSafe("keyUp($key)") { remotes[host]?.keyUp(key) }
    }

    fun longPress(key: AndroidTvKey, holdMs: Long = 800L) {
        val host = _currentHost.value ?: return
        launchSafe("longPress($key)") { remotes[host]?.longPress(key, holdMs) }
    }

    fun setVolume(level: Float) {
        val host = _currentHost.value ?: return
        launchSafe("setVolume($level)") { remotes[host]?.setVolume(level) }
    }

    fun launchApp(uri: String) {
        val host = _currentHost.value ?: return
        launchSafe("launchApp($uri)") { remotes[host]?.launchApp(uri) }
    }

    private fun remoteFor(device: AndroidTvDevice): AndroidTvRemote =
        remotes.getOrPut(device.host) {
            AndroidTvRemote(getApplication(), device, certStore)
        }

    override fun onCleared() {
        discovery.stop()
        remotes.values.forEach { runCatching { it.disconnect() } }
        remotes.clear()
        super.onCleared()
    }
}
