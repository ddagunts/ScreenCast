package io.github.ddagunts.screencast.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

object NetworkUtils {
    // Returns the IPv4 address of a connected Wi-Fi network, or null if none.
    // Filters by TRANSPORT_WIFI via ConnectivityManager rather than interface
    // name (wlan0) because OEMs vary. Chromecast lives on the LAN and our URL
    // scheme is IPv4, so v6 addresses are ignored.
    //
    // Some VPN clients (e.g. Rethink/bravedns) publish a dual-transport network
    // with `Transports: WIFI|VPN` whose first IPv4 LinkAddress is the tunnel
    // endpoint — bind the HTTP server to that and the Chromecast can't route to
    // it. Require NET_CAPABILITY_NOT_VPN so we ignore those mirror networks and
    // land on the actual wlan0 LinkProperties.
    fun getWifiIpAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val link = cm.getLinkProperties(network) ?: continue
            val v4 = link.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            if (v4 != null) return v4.hostAddress
        }
        return null
    }

    // Same filter as getWifiIpAddress but returns the Network handle itself so
    // callers can pass it to ConnectivityManager.bindProcessToNetwork or
    // Network.bindSocket. Needed by WebRTC mode: the RTCConfiguration's
    // networkPreference is only a hint, and libwebrtc's UDP sockets can still
    // get routed through an always-on VPN's default route unless the process
    // is pinned to the real Wi-Fi Network.
    fun getWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            return network
        }
        return null
    }
}
