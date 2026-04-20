package io.github.ddagunts.screencast.util

import java.net.NetworkInterface
import java.net.Inet4Address

object NetworkUtils {
    fun getWifiIpAddress(): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
