package io.github.ddagunts.screencast.androidtv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Discovers Android TV / Google TV devices that advertise the
// `_androidtvremote2._tcp` mDNS service. Mirror of cast/CastDiscovery.kt
// — same NsdManager pattern, different service type, plus extracts the
// BLE MAC and model name from TXT records when present.
//
// `bleMac` (`bt`/`bs` TXT) is the only stable identifier across DHCP
// renewals. Where it's missing (some firmwares omit it), the friendly
// name is the fallback key for paired-device persistence — fragile, but
// matches the constraints of the protocol.
class AndroidTvDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val devices = mutableMapOf<String, AndroidTvDevice>()
    private val _flow = MutableStateFlow<List<AndroidTvDevice>>(emptyList())
    val flow: StateFlow<List<AndroidTvDevice>> = _flow

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = logI("ATV mDNS discovery started ($type)")
        override fun onDiscoveryStopped(type: String) = logI("ATV mDNS discovery stopped")
        override fun onStartDiscoveryFailed(type: String, e: Int) = logE("ATV discovery start failed $e")
        override fun onStopDiscoveryFailed(type: String, e: Int) = logE("ATV discovery stop failed $e")
        override fun onServiceFound(info: NsdServiceInfo) {
            logD("ATV found: ${info.serviceName}")
            resolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            logI("ATV lost: ${info.serviceName}")
            devices.remove(info.serviceName)
            _flow.value = devices.values.sortedBy { it.name }
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(info, { it.run() }, object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(e: Int) {}
                override fun onServiceUpdated(updated: NsdServiceInfo) = onResolved(info.serviceName, updated)
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) =
                    logE("ATV resolve failed $e for ${i.serviceName}")
                override fun onServiceResolved(i: NsdServiceInfo) = onResolved(info.serviceName, i)
            })
        }
    }

    private fun onResolved(key: String, info: NsdServiceInfo) {
        val address = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull()
        else @Suppress("DEPRECATION") info.host
        val host = address?.hostAddress ?: run {
            logW("ATV resolved ${info.serviceName} but no host address — skipping")
            return
        }
        // ATV doesn't expose `fn` (friendly name) the way Cast does; the
        // service name itself is the user-facing label that the TV's
        // settings show ("Living Room TV"), with the `_androidtvremote2…`
        // suffix already stripped by NsdManager.
        val name = info.serviceName.ifEmpty { key }
        val txt = info.attributes
        val bleMac = (txt["bt"] ?: txt["bs"])
            ?.let { String(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
        val model = txt["md"]?.let { String(it, Charsets.UTF_8) }
        val device = AndroidTvDevice(
            name = name, host = host, port = info.port,
            modelName = model, bleMac = bleMac,
        )
        devices[key] = device
        _flow.value = devices.values.sortedBy { it.name }
        logI("ATV resolved: $name @ $host:${info.port} (mac=${bleMac ?: "?"}, model=${model ?: "?"})")
    }

    fun start() = nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    fun stop() = runCatching { nsd.stopServiceDiscovery(listener) }

    companion object {
        private const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
