package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.AppSettingsStore
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.CastMode
import io.github.ddagunts.screencast.MediaProjectionRequestActivity
import io.github.ddagunts.screencast.cast.CastCertPinStore
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import io.github.ddagunts.screencast.media.Resolution
import io.github.ddagunts.screencast.media.StreamConfig
import io.github.ddagunts.screencast.media.StreamConfigStore

class CastViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = CastDiscovery(app).also { it.start() }
    val discovered: StateFlow<List<CastDevice>> = discovery.flow

    // Active + errored casts, keyed by host. UI renders every entry; hosts
    // already in this map are filtered out of the "Available" picker list.
    val activeCasts: StateFlow<Map<String, CastForegroundService.DeviceCast>> =
        CastForegroundService.devicesFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyMap()
        )

    private val store = StreamConfigStore(app)
    private val _streamConfig = MutableStateFlow(store.load())
    val streamConfig: StateFlow<StreamConfig> = _streamConfig

    // App-level cast mode (HLS vs WebRTC). Lives here so the single main-screen
    // mode toggle and the Settings screen can both read/write one source of
    // truth. The two VMs (CastViewModel, WebRtcViewModel) each still own their
    // own discovery + session state — this flag just decides which one the
    // main screen currently surfaces.
    private val appSettings = AppSettingsStore(app)
    private val _castMode = MutableStateFlow(appSettings.castMode)
    val castMode: StateFlow<CastMode> = _castMode

    fun setCastMode(mode: CastMode) {
        _castMode.value = mode
        appSettings.castMode = mode
    }

    // Hosts with a pinned TLS fingerprint. Only mutated by the VM on explicit
    // forget; new pins made during an active cast's handshake won't appear
    // here until refreshPairedHosts() is called (e.g. on Settings reentry).
    private val pinStore = CastCertPinStore(app)
    private val _pairedHosts = MutableStateFlow(pinStore.pinnedHosts())
    val pairedHosts: StateFlow<Set<String>> = _pairedHosts

    fun refreshPairedHosts() {
        _pairedHosts.value = pinStore.pinnedHosts()
    }

    fun forgetPairedHost(host: String) {
        pinStore.forget(host)
        _pairedHosts.value = pinStore.pinnedHosts()
    }

    private fun updateConfig(transform: (StreamConfig) -> StreamConfig) {
        val updated = transform(_streamConfig.value)
        _streamConfig.value = updated
        store.save(updated)
    }

    fun setSegmentDuration(sec: Double) = updateConfig { it.copy(segmentDurationSec = sec) }
    fun setWindowSize(n: Int) = updateConfig { it.copy(windowSize = n) }
    fun setLiveEdgeFactor(f: Double) = updateConfig { it.copy(liveEdgeFactor = f) }
    fun setResolution(r: Resolution) = updateConfig { it.copy(resolution = r) }
    fun setSyncStart(sync: Boolean) {
        updateConfig { it.copy(syncStart = sync) }
        pushSyncConfig()
    }
    fun setSyncIntervalSec(n: Int) {
        updateConfig { it.copy(syncIntervalSec = n) }
        pushSyncConfig()
    }
    fun setSyncDriftMs(n: Int) {
        updateConfig { it.copy(syncDriftThresholdMs = n) }
        pushSyncConfig()
    }

    // Push sync knobs to the running service so mid-cast tweaks take effect
    // on the next maintenance tick. Skip when nothing is casting — the values
    // will be sent on the next ACTION_START anyway, and we don't want to
    // accidentally foreground a service that's already been stopped.
    private fun pushSyncConfig() {
        if (activeCasts.value.isEmpty()) return
        val cfg = _streamConfig.value
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_UPDATE_SYNC_CONFIG)
                .putExtra(CastForegroundService.EXTRA_SYNC_START, cfg.syncStart)
                .putExtra(CastForegroundService.EXTRA_SYNC_INTERVAL_SEC, cfg.syncIntervalSec)
                .putExtra(CastForegroundService.EXTRA_SYNC_DRIFT_MS, cfg.syncDriftThresholdMs)
        )
    }

    fun startCast(device: CastDevice) {
        val ctx = getApplication<Application>()
        val cfg = _streamConfig.value
        val hostAlreadyCasting = activeCasts.value.containsKey(device.host)
        if (hostAlreadyCasting) return
        if (activeCasts.value.size >= CastForegroundService.MAX_DEVICES) return

        if (activeCasts.value.isEmpty()) {
            // First device — route through the trampoline so the system
            // MediaProjection consent dialog fires. Android 14+ rejects an FGS
            // of type mediaProjection that doesn't already hold a valid grant.
            val intent = Intent(ctx, MediaProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(CastForegroundService.EXTRA_DEVICE_NAME, device.name)
                putExtra(CastForegroundService.EXTRA_DEVICE_HOST, device.host)
                putExtra(CastForegroundService.EXTRA_DEVICE_PORT, device.port)
                putExtra(CastForegroundService.EXTRA_SEGMENT_DURATION, cfg.segmentDurationSec)
                putExtra(CastForegroundService.EXTRA_WINDOW_SIZE, cfg.windowSize)
                putExtra(CastForegroundService.EXTRA_LIVE_EDGE, cfg.liveEdgeFactor)
                putExtra(CastForegroundService.EXTRA_RESOLUTION, cfg.resolution.name)
                putExtra(CastForegroundService.EXTRA_SYNC_START, cfg.syncStart)
                putExtra(CastForegroundService.EXTRA_SYNC_INTERVAL_SEC, cfg.syncIntervalSec)
                putExtra(CastForegroundService.EXTRA_SYNC_DRIFT_MS, cfg.syncDriftThresholdMs)
            }
            ctx.startActivity(intent)
        } else {
            // Pipeline already running — open a new session directly without
            // re-prompting for MediaProjection consent. Ship syncStart so the
            // service honors a mid-cast toggle for the device being added.
            ctx.startService(
                Intent(ctx, CastForegroundService::class.java)
                    .setAction(CastForegroundService.ACTION_START)
                    .putExtra(CastForegroundService.EXTRA_DEVICE_NAME, device.name)
                    .putExtra(CastForegroundService.EXTRA_DEVICE_HOST, device.host)
                    .putExtra(CastForegroundService.EXTRA_DEVICE_PORT, device.port)
                    .putExtra(CastForegroundService.EXTRA_SYNC_START, cfg.syncStart)
                    .putExtra(CastForegroundService.EXTRA_SYNC_INTERVAL_SEC, cfg.syncIntervalSec)
                    .putExtra(CastForegroundService.EXTRA_SYNC_DRIFT_MS, cfg.syncDriftThresholdMs)
            )
        }
    }

    fun stopCast(host: String) = sendDeviceAction(CastForegroundService.ACTION_STOP_DEVICE, host)
    fun stopAll() = sendServiceAction(CastForegroundService.ACTION_STOP_ALL)
    fun pause(host: String) = sendDeviceAction(CastForegroundService.ACTION_PAUSE, host)
    fun play(host: String) = sendDeviceAction(CastForegroundService.ACTION_PLAY, host)

    fun setVolume(host: String, level: Double) {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_SET_VOLUME)
                .putExtra(CastForegroundService.EXTRA_DEVICE_HOST, host)
                .putExtra(CastForegroundService.EXTRA_VOLUME_LEVEL, level)
        )
    }

    fun setMute(host: String, muted: Boolean) {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_SET_MUTE)
                .putExtra(CastForegroundService.EXTRA_DEVICE_HOST, host)
                .putExtra(CastForegroundService.EXTRA_MUTED, muted)
        )
    }

    private fun sendDeviceAction(action: String, host: String) {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, CastForegroundService::class.java)
                .setAction(action)
                .putExtra(CastForegroundService.EXTRA_DEVICE_HOST, host)
        )
    }

    private fun sendServiceAction(action: String) {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, CastForegroundService::class.java).setAction(action))
    }

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }
}
