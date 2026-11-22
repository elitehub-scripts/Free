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

/**
 * Monitors active physical network transport (Wi-Fi vs Cellular vs Offline) and interface changes.
 */
class ConnectivityMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkInfo = MutableStateFlow(readCurrentNetworkInfo())
    val networkInfo: StateFlow<ActiveNetworkInfo> = _networkInfo.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    fun start() {
        refresh()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun refresh() {
        _networkInfo.value = readCurrentNetworkInfo()
    }

    fun getActivePhysicalNetwork(): Network? {
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        return active
    }

    private fun readCurrentNetworkInfo(): ActiveNetworkInfo {
        val active = cm.activeNetwork ?: return ActiveNetworkInfo()
        val caps = cm.getNetworkCapabilities(active) ?: return ActiveNetworkInfo()
        val props = cm.getLinkProperties(active)

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransportType.VPN
            else -> NetworkTransportType.OFFLINE
        }

        val ipv4 = props?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress ?: "—"

        return ActiveNetworkInfo(
            transport = if (hasInternet) transport else NetworkTransportType.OFFLINE,
            isConnected = hasInternet && transport != NetworkTransportType.OFFLINE,
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            interfaceName = props?.interfaceName ?: "Unavailable",
            localIp = ipv4
        )
    }
}
