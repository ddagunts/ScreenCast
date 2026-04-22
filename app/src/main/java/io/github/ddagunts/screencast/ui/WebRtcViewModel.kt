package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.WebRtcForegroundService
import io.github.ddagunts.screencast.WebRtcProjectionRequestActivity
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastDiscovery
import io.github.ddagunts.screencast.webrtc.WebRtcConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// Separate ViewModel from CastViewModel — its discovery lifecycle is
// independent (you can leave the WebRTC screen and HLS mode keeps scanning;
// this VM's NsdManager listener stops when the user navigates off). The
// active-session flow is a single nullable snapshot, matching the FGS's
// single-peer design.
class WebRtcViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = CastDiscovery(app).also { it.start() }
    val discovered: StateFlow<List<CastDevice>> = discovery.flow

    val session: StateFlow<WebRtcForegroundService.SessionSnapshot?> =
        WebRtcForegroundService.sessionFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, null
        )

    private val configStore = WebRtcConfigStore(app)
    private val _appId = MutableStateFlow(configStore.appId)
    val appId: StateFlow<String> = _appId

    fun setAppId(value: String) {
        val trimmed = value.trim()
        _appId.value = trimmed
        configStore.appId = trimmed
    }

    fun startCast(device: CastDevice) {
        val ctx = getApplication<Application>()
        val id = _appId.value
        if (id.isBlank()) return
        if (session.value != null) return
        val intent = Intent(ctx, WebRtcProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WebRtcForegroundService.EXTRA_DEVICE_NAME, device.name)
            putExtra(WebRtcForegroundService.EXTRA_DEVICE_HOST, device.host)
            putExtra(WebRtcForegroundService.EXTRA_DEVICE_PORT, device.port)
            putExtra(WebRtcForegroundService.EXTRA_APP_ID, id)
        }
        ctx.startActivity(intent)
    }

    fun stop() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, WebRtcForegroundService::class.java)
                .setAction(WebRtcForegroundService.ACTION_STOP)
        )
    }

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }
}
