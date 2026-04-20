package io.github.ddagunts.screencast.cast

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

data class CastDevice(val name: String, val host: String, val port: Int)

class CastDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val devices = mutableMapOf<String, CastDevice>()
    private val _flow = MutableStateFlow<List<CastDevice>>(emptyList())
    val flow: StateFlow<List<CastDevice>> = _flow

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = logI("mDNS discovery started ($type)")
        override fun onDiscoveryStopped(type: String) = logI("mDNS discovery stopped")
        override fun onStartDiscoveryFailed(type: String, e: Int) = logE("discovery start failed $e")
        override fun onStopDiscoveryFailed(type: String, e: Int) = logE("discovery stop failed $e")
        override fun onServiceFound(info: NsdServiceInfo) {
            logD("found: ${info.serviceName}")
            resolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            logI("lost: ${info.serviceName}")
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
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) = logE("resolve failed $e for ${i.serviceName}")
                override fun onServiceResolved(i: NsdServiceInfo) = onResolved(info.serviceName, i)
            })
        }
    }

    private fun onResolved(key: String, info: NsdServiceInfo) {
        val address = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull()
        else @Suppress("DEPRECATION") info.host
        val host = address?.hostAddress ?: run {
            logW("resolved ${info.serviceName} but no host address — skipping")
            return
        }
        val friendly = info.attributes["fn"]?.let { String(it, Charsets.UTF_8) } ?: info.serviceName
        val device = CastDevice(friendly, host, info.port)
        devices[key] = device
        _flow.value = devices.values.sortedBy { it.name }
        logI("resolved: $friendly @ $host:${info.port}")
    }

    fun start() = nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    fun stop() = runCatching { nsd.stopServiceDiscovery(listener) }
}
