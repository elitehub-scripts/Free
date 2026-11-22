package com.example.networking.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.OfflineP2pPeer
import com.example.model.OfflineP2pService
import com.example.model.OfflineP2pState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/**
 * Manages offline device-to-device Wi-Fi Direct (WifiP2pManager + DNS-SD service discovery)
 * AND automatic SoftAP joining via WifiNetworkSpecifier so Host and Client can connect
 * with zero router, zero mobile data, and zero internet.
 */
class OfflineWifiDirectManager(
    context: Context,
    private val onGroupConnectionUpdated: (isGroupOwner: Boolean, ownerIp: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        runCatching { appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()
    private val wifiManager: WifiManager? =
        runCatching { appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()
    private val connectivityManager: ConnectivityManager? =
        runCatching { appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }.getOrNull()

    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var listenersConfigured = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastGroupCreatedAtMs: Long = 0L

    @Volatile
    private var boundSoftApNetwork: Network? = null
    private var softApNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val _state = MutableStateFlow(
        OfflineP2pState(
            isSupported = manager != null,
            isWifiEnabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull() ?: true,
            statusMessage = if (manager != null) {
                if (runCatching { wifiManager?.isWifiEnabled }.getOrNull() == false) {
                    "Wi-Fi is OFF — turn on Wi-Fi for Offline Wi-Fi Direct & SoftAP linking"
                } else {
                    "Offline Wi-Fi Direct ready"
                }
            } else {
                "Wi-Fi Direct unsupported on this hardware"
            }
        )
    )
    val state: StateFlow<OfflineP2pState> = _state.asStateFlow()

    init {
        ensureChannelAndReceiver()
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    val wifiOn = wifiState == WifiManager.WIFI_STATE_ENABLED
                    _state.value = _state.value.copy(
                        isWifiEnabled = wifiOn,
                        statusMessage = if (!wifiOn) {
                            "Wi-Fi is OFF — turn on Wi-Fi to share or connect offline"
                        } else if (_state.value.statusMessage.contains("Wi-Fi is OFF", ignoreCase = true)) {
                            "Offline Wi-Fi Direct ready"
                        } else {
                            _state.value.statusMessage
                        }
                    )
                }

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    val wifiOn = runCatching { wifiManager?.isWifiEnabled }.getOrNull() ?: enabled
                    _state.value = _state.value.copy(
                        isEnabled = enabled,
                        isWifiEnabled = wifiOn,
                        statusMessage = if (enabled || wifiOn) {
                            _state.value.statusMessage
                        } else {
                            "Turn ON Wi-Fi on this phone for Offline Wi-Fi Direct"
                        }
                    )
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasRequiredPermission(appContext)) return
                    val ch = channel ?: return
                    runCatching {
                        manager?.requestPeers(ch) { peerList ->
                            val mapped = peerList.deviceList.map { dev ->
                                OfflineP2pPeer(
                                    deviceName = dev.deviceName.ifBlank { dev.deviceAddress },
                                    deviceAddress = dev.deviceAddress,
                                    statusLabel = peerStatusLabel(dev.status)
                                )
                            }
                            _state.value = _state.value.copy(peers = mapped)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    runCatching {
                        manager?.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                            if (info != null && info.groupFormed) {
                                val ownerIp = info.groupOwnerAddress?.hostAddress.orEmpty()
                                _state.value = _state.value.copy(
                                    isGroupActive = true,
                                    isGroupOwner = info.isGroupOwner,
                                    groupOwnerIp = ownerIp,
                                    statusMessage = if (info.isGroupOwner) {
                                        "Offline P2P Group active ($ownerIp)"
                                    } else {
                                        "Connected offline to Host ($ownerIp)"
                                    }
                                )
                                if (hasRequiredPermission(appContext)) {
                                    manager.requestGroupInfo(ch) { grp ->
                                        if (grp != null) {
                                            _state.value = _state.value.copy(
                                                groupSsid = grp.networkName.orEmpty(),
                                                groupPassphrase = grp.passphrase.orEmpty()
                                            )
                                        }
                                    }
                                }
                                if (ownerIp.isNotBlank()) {
                                    onGroupConnectionUpdated(info.isGroupOwner, ownerIp)
                                }
                            } else {
                                // Ignore stale sticky disconnection broadcast within 4s of createGroup()
                                val elapsedSinceCreate = System.currentTimeMillis() - lastGroupCreatedAtMs
                                if (_state.value.isGroupOwner && elapsedSinceCreate < 4_000L) {
                                    return@requestConnectionInfo
                                }
                                if (boundSoftApNetwork != null) {
                                    return@requestConnectionInfo
                                }
                                _state.value = _state.value.copy(
                                    isGroupActive = false,
                                    isGroupOwner = false,
                                    groupSsid = "",
                                    groupPassphrase = "",
                                    groupOwnerIp = ""
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ensureChannelAndReceiver(): WifiP2pManager.Channel? {
        val mgr = manager ?: return null
        if (channel == null) {
            channel = runCatching {
                mgr.initialize(appContext, Looper.getMainLooper(), null)
            }.getOrNull()
        }
        val ch = channel
        if (ch != null && !listenersConfigured) {
            listenersConfigured = true
            runCatching {
                mgr.setDnsSdResponseListeners(
                    ch,
                    { instanceName, registrationType, srcDevice ->
                        Log.d(TAG, "P2P service found: $instanceName ($registrationType) from ${srcDevice.deviceName}")
                    },
                    { fullDomainName, record, srcDevice ->
                        if (!fullDomainName.contains("_wifishare._tcp", ignoreCase = true)) return@setDnsSdResponseListeners
                        val code = record["code"].orEmpty().trim().uppercase()
                        val port = record["port"]?.toIntOrNull() ?: 51821
                        val ssid = record["ssid"].orEmpty().trim()
                        val pass = record["pass"].orEmpty().trim()
                        val p2pIp = record["p2pip"].orEmpty().trim().ifBlank { "192.168.49.1" }
                        val open = record["open"] != "0"
                        val auto = record["auto"] == "1"
                        val service = OfflineP2pService(
                            deviceName = srcDevice.deviceName.ifBlank { "WiFiShare Host" },
                            deviceAddress = srcDevice.deviceAddress,
                            sharingCode = code,
                            hostPort = port,
                            p2pSsid = ssid,
                            p2pPassphrase = pass,
                            p2pIp = p2pIp,
                            allowOpenOffline = open,
                            autoAcceptOpen = auto
                        )
                        _state.value = _state.value.copy(
                            services = _state.value.services.filterNot { it.deviceAddress == service.deviceAddress } + service,
                            statusMessage = "Found nearby WiFiShare Host (${service.deviceName})"
                        )
                    }
                )
            }
        }
        if (!receiverRegistered && ch != null) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        return ch
    }

    fun isWifiEnabled(): Boolean {
        val enabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull() ?: _state.value.isWifiEnabled
        _state.value = _state.value.copy(isWifiEnabled = enabled)
        return enabled
    }

    @SuppressLint("MissingPermission")
    fun createOfflineGroup(): Result<Unit> {
        val mgr = manager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        if (!hasRequiredPermission(appContext)) {
            return Result.failure(SecurityException("Nearby Wi-Fi permission required for Wi-Fi Direct"))
        }
        if (!isWifiEnabled()) {
            val msg = "Turn ON Wi-Fi first to create an Offline Wi-Fi Direct group."
            _state.value = _state.value.copy(statusMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }
        val ch = ensureChannelAndReceiver() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))

        _state.value = _state.value.copy(statusMessage = "Creating offline Wi-Fi Direct group...")
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                lastGroupCreatedAtMs = System.currentTimeMillis()
                mgr.requestGroupInfo(ch) { grp ->
                    _state.value = _state.value.copy(
                        isGroupActive = true,
                        isGroupOwner = true,
                        groupSsid = grp?.networkName.orEmpty(),
                        groupPassphrase = grp?.passphrase.orEmpty(),
                        groupOwnerIp = "192.168.49.1",
                        statusMessage = "Offline Wi-Fi Direct Group ready"
                    )
                    onGroupConnectionUpdated(true, "192.168.49.1")
                }
            }

            override fun onFailure(reason: Int) {
                mgr.requestGroupInfo(ch) { grp ->
                    if (grp != null) {
                        lastGroupCreatedAtMs = System.currentTimeMillis()
                        _state.value = _state.value.copy(
                            isGroupActive = true,
                            isGroupOwner = true,
                            groupSsid = grp.networkName.orEmpty(),
                            groupPassphrase = grp.passphrase.orEmpty(),
                            groupOwnerIp = "192.168.49.1",
                            statusMessage = "Offline Wi-Fi Direct Group active"
                        )
                        onGroupConnectionUpdated(true, "192.168.49.1")
                    } else {
                        _state.value = _state.value.copy(
                            statusMessage = "Wi-Fi Direct unavailable (${reasonLabel(reason)}); turn on Wi-Fi or use Hotspot"
                        )
                    }
                }
            }
        })
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun registerHostP2pService(
        sharingCode: String,
        hostPort: Int,
        p2pSsid: String = "",
        p2pPassphrase: String = "",
        allowOpenOffline: Boolean = true,
        autoAcceptOpen: Boolean = false
    ) {
        val mgr = manager ?: return
        if (!hasRequiredPermission(appContext) || !isWifiEnabled()) return
        val ch = ensureChannelAndReceiver() ?: return

        val txt = linkedMapOf(
            "code" to sharingCode.take(18),
            "port" to hostPort.toString(),
            "open" to if (allowOpenOffline) "1" else "0",
            "auto" to if (autoAcceptOpen) "1" else "0"
        )
        if (p2pSsid.isNotBlank() && p2pPassphrase.isNotBlank()) {
            txt["ssid"] = p2pSsid.take(40)
            txt["pass"] = p2pPassphrase.take(32)
            txt["p2pip"] = "192.168.49.1"
        }

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("WiFiShare", "_wifishare._tcp", txt)
        runCatching {
            mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    runCatching { mgr.addLocalService(ch, serviceInfo, null) }
                }

                override fun onFailure(reason: Int) {
                    runCatching { mgr.addLocalService(ch, serviceInfo, null) }
                }
            })
        }
    }

    fun unregisterHostP2pService() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.clearLocalServices(ch, null) }
    }

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery(allowRetryOnBusy: Boolean = true): Result<Unit> {
        val mgr = manager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        if (!hasRequiredPermission(appContext)) {
            return Result.failure(SecurityException("Nearby Wi-Fi permission required for Wi-Fi Direct"))
        }
        if (!isWifiEnabled()) {
            val msg = "Wi-Fi is turned OFF. Turn ON Wi-Fi so this phone can find nearby Hosts offline."
            _state.value = _state.value.copy(isDiscovering = false, statusMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }
        val ch = ensureChannelAndReceiver() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))

        // Register DNS-SD service discovery request so we pick up Host code/SSID/pass over the air
        runCatching {
            mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    runCatching {
                        mgr.addServiceRequest(
                            ch,
                            WifiP2pDnsSdServiceRequest.newInstance(),
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    runCatching { mgr.discoverServices(ch, null) }
                                }

                                override fun onFailure(reason: Int) = Unit
                            }
                        )
                    }
                }

                override fun onFailure(reason: Int) = Unit
            })
        }

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(
                    isDiscovering = true,
                    statusMessage = "Scanning offline Wi-Fi Direct Hosts & peers..."
                )
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && allowRetryOnBusy) {
                    _state.value = _state.value.copy(statusMessage = "Wi-Fi Direct busy; retrying peer scan...")
                    mainHandler.postDelayed({
                        startPeerDiscovery(allowRetryOnBusy = false)
                    }, 1500L)
                } else {
                    _state.value = _state.value.copy(
                        isDiscovering = false,
                        statusMessage = "Wi-Fi Direct scan failed (${reasonLabel(reason)}). Ensure Wi-Fi & Location are ON."
                    )
                }
            }
        })
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String): Result<Unit> {
        val mgr = manager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        if (!hasRequiredPermission(appContext)) {
            return Result.failure(SecurityException("Nearby Wi-Fi permission required"))
        }
        if (!isWifiEnabled()) {
            return Result.failure(IllegalStateException("Turn ON Wi-Fi first to connect offline via Wi-Fi Direct."))
        }
        val ch = ensureChannelAndReceiver() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        _state.value = _state.value.copy(statusMessage = "Connecting offline via Wi-Fi Direct...")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(
                    isDiscovering = false,
                    statusMessage = "Wi-Fi Direct link negotiated; awaiting Host IP..."
                )
            }

            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(
                    statusMessage = "Wi-Fi Direct connect failed (${reasonLabel(reason)})"
                )
            }
        })
        return Result.success(Unit)
    }

    /**
     * Connects an offline Client directly to a Host's Wi-Fi Direct SoftAP (SSID + passphrase)
     * on Android 10+ using WifiNetworkSpecifier, returning an active local Network ready for sockets.
     */
    suspend fun joinWifiDirectAp(
        ssid: String,
        passphrase: String,
        timeoutMs: Long = 22_000L
    ): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (ssid.isBlank() || passphrase.isBlank() || !isWifiEnabled()) return null
        val cm = connectivityManager ?: return null

        releaseSoftApNetwork()
        _state.value = _state.value.copy(statusMessage = "Joining Host Wi-Fi Direct network ($ssid)...")

        val deferred = CompletableDeferred<Network?>()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundSoftApNetwork = network
                _state.value = _state.value.copy(
                    isGroupActive = true,
                    isGroupOwner = false,
                    groupSsid = ssid,
                    groupPassphrase = passphrase,
                    groupOwnerIp = "192.168.49.1",
                    statusMessage = "Connected to Host P2P AP ($ssid)"
                )
                deferred.complete(network)
            }

            override fun onUnavailable() {
                deferred.complete(null)
            }

            override fun onLost(network: Network) {
                if (boundSoftApNetwork == network) {
                    boundSoftApNetwork = null
                }
            }
        }
        softApNetworkCallback = callback

        return try {
            cm.requestNetwork(req, callback)
            val joined = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (joined == null) {
                releaseSoftApNetwork()
                _state.value = _state.value.copy(
                    statusMessage = "Could not join Host Wi-Fi Direct network ($ssid)."
                )
            }
            joined
        } catch (e: Exception) {
            Log.w(TAG, "joinWifiDirectAp failed: ${e.message}")
            releaseSoftApNetwork()
            null
        }
    }

    fun releaseSoftApNetwork() {
        softApNetworkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        softApNetworkCallback = null
        boundSoftApNetwork = null
    }

    @Suppress("DEPRECATION")
    fun getBoundP2pNetwork(): Network? {
        boundSoftApNetwork?.let { return it }
        val cm = connectivityManager ?: return null
        return cm.allNetworks.firstOrNull { net ->
            val lp = cm.getLinkProperties(net) ?: return@firstOrNull false
            val iface = lp.interfaceName?.lowercase().orEmpty()
            val hasP2pIp = lp.linkAddresses.any {
                it.address is Inet4Address && it.address.hostAddress?.startsWith("192.168.49.") == true
            }
            iface.startsWith("p2p") || hasP2pIp
        }
    }

    fun stopAll() {
        releaseSoftApNetwork()
        val mgr = manager ?: return
        val ch = channel
        if (ch != null) {
            runCatching { mgr.clearLocalServices(ch, null) }
            runCatching { mgr.clearServiceRequests(ch, null) }
            runCatching { mgr.stopPeerDiscovery(ch, null) }
            runCatching { mgr.removeGroup(ch, null) }
        }
        _state.value = _state.value.copy(
            isDiscovering = false,
            isGroupActive = false,
            isGroupOwner = false,
            groupSsid = "",
            groupPassphrase = "",
            groupOwnerIp = "",
            peers = emptyList(),
            services = emptyList(),
            statusMessage = "Offline Wi-Fi Direct idle"
        )
    }

    fun release() {
        stopAll()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "code 0 - check Wi-Fi & Location ON"
        WifiP2pManager.P2P_UNSUPPORTED -> "code 1 - hardware unsupported"
        WifiP2pManager.BUSY -> "code 2 - framework busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "code 3 - no service requests"
        else -> "code $reason"
    }

    private fun peerStatusLabel(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.AVAILABLE -> "Available (Offline P2P)"
        WifiP2pDevice.FAILED -> "Failed"
        else -> "Unavailable"
    }

    companion object {
        private const val TAG = "OfflineWifiDirect"

        fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

        fun hasRequiredPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
