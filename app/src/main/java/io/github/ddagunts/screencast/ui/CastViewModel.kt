package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.MediaProjectionRequestActivity
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastDiscovery
import io.github.ddagunts.screencast.cast.CastVolume
import io.github.ddagunts.screencast.media.Resolution
import io.github.ddagunts.screencast.media.StreamConfig
import io.github.ddagunts.screencast.media.StreamConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class CastViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = CastDiscovery(app).also { it.start() }
    val devices: StateFlow<List<CastDevice>> = discovery.flow

    val phase: StateFlow<CastForegroundService.Phase> =
        CastForegroundService.flow.stateIn(
            viewModelScope, SharingStarted.Eagerly, CastForegroundService.Phase.Idle
        )

    val playerState: StateFlow<String> =
        CastForegroundService.playerStateFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, "IDLE"
        )

    val volume: StateFlow<CastVolume> =
        CastForegroundService.volumeFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, CastVolume()
        )

    private val store = StreamConfigStore(app)
    private val _streamConfig = MutableStateFlow(store.load())
    val streamConfig: StateFlow<StreamConfig> = _streamConfig

    private fun updateConfig(transform: (StreamConfig) -> StreamConfig) {
        val updated = transform(_streamConfig.value)
        _streamConfig.value = updated
        store.save(updated)
    }

    fun setSegmentDuration(sec: Double) = updateConfig { it.copy(segmentDurationSec = sec) }
    fun setWindowSize(n: Int) = updateConfig { it.copy(windowSize = n) }
    fun setLiveEdgeFactor(f: Double) = updateConfig { it.copy(liveEdgeFactor = f) }
    fun setResolution(r: Resolution) = updateConfig { it.copy(resolution = r) }

    fun startCast(device: CastDevice) {
        val ctx = getApplication<Application>()
        val cfg = _streamConfig.value
        // Consent first: trampoline Activity requests MediaProjection, then starts
        // the FGS with the consent token. Android 14+ rejects an FGS of type
        // mediaProjection that doesn't already hold a valid projection grant.
        val intent = Intent(ctx, MediaProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CastForegroundService.EXTRA_DEVICE_NAME, device.name)
            putExtra(CastForegroundService.EXTRA_DEVICE_HOST, device.host)
            putExtra(CastForegroundService.EXTRA_DEVICE_PORT, device.port)
            putExtra(CastForegroundService.EXTRA_SEGMENT_DURATION, cfg.segmentDurationSec)
            putExtra(CastForegroundService.EXTRA_WINDOW_SIZE, cfg.windowSize)
            putExtra(CastForegroundService.EXTRA_LIVE_EDGE, cfg.liveEdgeFactor)
            putExtra(CastForegroundService.EXTRA_RESOLUTION, cfg.resolution.name)
        }
        ctx.startActivity(intent)
    }

    fun stopCast() = sendServiceAction(CastForegroundService.ACTION_STOP)
    fun pause() = sendServiceAction(CastForegroundService.ACTION_PAUSE)
    fun play() = sendServiceAction(CastForegroundService.ACTION_PLAY)

    fun setVolume(level: Double) {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_SET_VOLUME)
                .putExtra(CastForegroundService.EXTRA_VOLUME_LEVEL, level)
        )
    }

    fun setMute(muted: Boolean) {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_SET_MUTE)
                .putExtra(CastForegroundService.EXTRA_MUTED, muted)
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
