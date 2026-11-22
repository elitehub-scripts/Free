package com.example.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.model.ActiveNetworkInfo
import com.example.model.NetworkTransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Monitors active physical network transport (Wi-Fi vs Cellular vs Offline) and local LAN/Hotspot/P2P interfaces.
 * Supports offline local-link detection so Host and Client can pair and accept connections without upstream internet.
 */
class ConnectivityMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkInfo = MutableStateFlow(readCurrentNetworkInfo())
    val networkInfo: StateFlow<ActiveNetworkInfo> = _networkInfo.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = refresh()
    }

    fun start() {
        refresh()
        val request = NetworkRequest.Builder().build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun refresh() {
        _networkInfo.value = readCurrentNetworkInfo()
    }

    @Suppress("DEPRECATION")
    fun getActivePhysicalNetwork(): Network? {
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return active
            }
        }
        val candidates = cm.allNetworks.filter { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    fun getDefaultGatewayIp(): String? {
        val active = cm.activeNetwork
        val props = active?.let { cm.getLinkProperties(it) }
        val routeGw = props?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isLoopbackAddress }
            ?.gateway?.hostAddress
        if (!routeGw.isNullOrBlank() && routeGw != "0.0.0.0") return routeGw

        @Suppress("DEPRECATION")
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val gw = lp.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isLoopbackAddress }
                ?.gateway?.hostAddress
            if (!gw.isNullOrBlank() && gw != "0.0.0.0") return gw
        }
        return null
    }

    private fun readCurrentNetworkInfo(): ActiveNetworkInfo {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val props = active?.let { cm.getLinkProperties(it) }

        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transport = when {
            caps == null -> NetworkTransportType.OFFLINE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransportType.VPN
            else -> NetworkTransportType.OFFLINE
        }

        val activeIpv4 = props?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress

        val localInterfaces = discoverLocalIpv4Addresses()
        val preferredLocalIp = activeIpv4
            ?: localInterfaces.firstOrNull { it.first.startsWith("p2p") }?.second
            ?: localInterfaces.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") }?.second
            ?: localInterfaces.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: localInterfaces.firstOrNull()?.second
            ?: "—"

        val ifaceLabel = props?.interfaceName
            ?: localInterfaces.firstOrNull { it.second == preferredLocalIp }?.first
            ?: "Unavailable"

        return ActiveNetworkInfo(
            transport = if (hasInternet) transport else NetworkTransportType.OFFLINE,
            isConnected = hasInternet && transport != NetworkTransportType.OFFLINE,
            hasLocalLink = localInterfaces.isNotEmpty() || !activeIpv4.isNullOrBlank(),
            isMetered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false,
            interfaceName = ifaceLabel,
            localIp = preferredLocalIp,
            localAddresses = localInterfaces.map { "${it.first}:${it.second}" }
        )
    }

    private fun discoverLocalIpv4Addresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        runCatching {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return emptyList())
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("dummy")) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddr = addr.hostAddress ?: continue
                        if (!hostAddr.startsWith("10.88.")) {
                            result.add(iface.name to hostAddr)
                        }
                    }
                }
            }
        }
        return result
    }
}
