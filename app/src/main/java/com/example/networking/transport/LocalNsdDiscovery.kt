package com.example.networking.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.model.DiscoveredHostPeer
import com.example.model.SharingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local mDNS / NSD service advertisement & discovery (_wifishare._tcp.)
 * Enables Clients on the same Wi-Fi, Hotspot, or LAN to find Hosts using only the short sharing code
 * without any backend server.
 */
class LocalNsdDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHostPeer>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHostPeer>> = _discoveredHosts.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    fun isSupported(): Boolean = nsdManager != null

    fun registerHostService(
        sharingCode: String,
        hostName: String,
        port: Int,
        networkType: String,
        hostPublicKeyB64: String,
        permittedModes: Set<SharingMode>
    ) {
        val manager = nsdManager ?: return
        unregisterHostService()
        acquireMulticastLock()

        val info = NsdServiceInfo().apply {
            serviceName = sharingCode
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("code", sharingCode)
            setAttribute("host", hostName.take(40))
            setAttribute("net", networkType)
            setAttribute("modes", permittedModes.joinToString(",") { it.code })
            // Keep TXT attribute compact (< 255 bytes)
            setAttribute("pk", hostPublicKeyB64.take(180))
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered NSD service: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed ($errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Unregistered NSD service: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed ($errorCode)")
            }
        }
        registrationListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService error: ${it.message}") }
    }

    fun unregisterHostService() {
        val manager = nsdManager ?: return
        registrationListener?.let { listener ->
            runCatching { manager.unregisterService(listener) }
        }
        registrationListener = null
        releaseMulticastLockIfIdle()
    }

    fun startDiscovery() {
        val manager = nsdManager ?: return
        stopDiscovery()
        acquireMulticastLock()
        _discoveredHosts.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _isDiscovering.value = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_wifishare")) return
                resolveService(manager, service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _discoveredHosts.value = _discoveredHosts.value.filterNot {
                    it.serviceName == service.serviceName || it.sharingCode == service.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD startDiscovery failed ($errorCode)")
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isDiscovering.value = false
            }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { _isDiscovering.value = false }
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        discoveryListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        _isDiscovering.value = false
        releaseMulticastLockIfIdle()
    }

    @Suppress("DEPRECATION")
    private fun resolveService(manager: NsdManager, service: NsdServiceInfo) {
        runCatching {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val ip = resolved.host?.hostAddress ?: return
                    val port = resolved.port.takeIf { it > 0 } ?: return
                    val attrs = resolved.attributes
                    val code = attrs["code"]?.decodeToString()?.uppercase()
                        ?: resolved.serviceName.uppercase()
                    val hostName = attrs["host"]?.decodeToString() ?: "WiFiShare Host"
                    val netType = attrs["net"]?.decodeToString() ?: "WIFI"
                    val pk = attrs["pk"]?.decodeToString().orEmpty()
                    val modesCsv = attrs["modes"]?.decodeToString().orEmpty()
                    val modes = modesCsv.split(",").mapNotNull { c ->
                        SharingMode.entries.firstOrNull { it.code == c }
                    }.toSet().ifEmpty { SharingMode.entries.toSet() }

                    val peer = DiscoveredHostPeer(
                        serviceName = resolved.serviceName,
                        sharingCode = code,
                        hostName = hostName,
                        hostIp = ip,
                        hostPort = port,
                        hostPublicKeyB64 = pk,
                        networkType = netType,
                        permittedModes = modes
                    )
                    _discoveredHosts.value = (_discoveredHosts.value.filterNot { it.sharingCode == peer.sharingCode } + peer)
                }
            })
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = runCatching {
                wifiManager?.createMulticastLock("wifishare_nsd_lock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        } else if (multicastLock?.isHeld == false) {
            runCatching { multicastLock?.acquire() }
        }
    }

    private fun releaseMulticastLockIfIdle() {
        if (registrationListener == null && discoveryListener == null) {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            }
        }
    }

    companion object {
        private const val TAG = "LocalNsdDiscovery"
        const val SERVICE_TYPE = "_wifishare._tcp."
    }
}
