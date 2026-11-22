package com.example.networking.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.OfflineP2pPeer
import com.example.model.OfflineP2pState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages offline device-to-device Wi-Fi Direct (WifiP2pManager) links so Host and Client
 * can connect and accept connections with zero router, zero mobile data, and zero internet.
 */
class OfflineWifiDirectManager(
    context: Context,
    private val onGroupConnectionUpdated: (isGroupOwner: Boolean, ownerIp: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        runCatching { appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _state = MutableStateFlow(
        OfflineP2pState(
            isSupported = manager != null,
            statusMessage = if (manager != null) "Offline Wi-Fi Direct ready" else "Wi-Fi Direct unsupported on this hardware"
        )
    )
    val state: StateFlow<OfflineP2pState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.value = _state.value.copy(
                        isEnabled = enabled,
                        statusMessage = if (enabled) _state.value.statusMessage else "Enable Wi-Fi on this phone for Offline Wi-Fi Direct"
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
        if (!receiverRegistered && channel != null) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        return channel
    }

    @SuppressLint("MissingPermission")
    fun createOfflineGroup(): Result<Unit> {
        val mgr = manager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        if (!hasRequiredPermission(appContext)) {
            return Result.failure(SecurityException("Nearby Wi-Fi permission required for Wi-Fi Direct"))
        }
        val ch = ensureChannelAndReceiver() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))

        _state.value = _state.value.copy(statusMessage = "Creating offline Wi-Fi Direct group...")
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
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
                // If a group already existed, query it instead of failing
                mgr.requestGroupInfo(ch) { grp ->
                    if (grp != null) {
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
                            statusMessage = "Wi-Fi Direct group unavailable (code $reason); Hotspot / LAN still works"
                        )
                    }
                }
            }
        })
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery(): Result<Unit> {
        val mgr = manager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        if (!hasRequiredPermission(appContext)) {
            return Result.failure(SecurityException("Nearby Wi-Fi permission required for Wi-Fi Direct"))
        }
        val ch = ensureChannelAndReceiver() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(
                    isDiscovering = true,
                    statusMessage = "Scanning offline Wi-Fi Direct peers..."
                )
            }

            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(
                    isDiscovering = false,
                    statusMessage = "Wi-Fi Direct scan failed (code $reason). Ensure Wi-Fi is turned on."
                )
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
        val ch = ensureChannelAndReceiver() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        _state.value = _state.value.copy(statusMessage = "Connecting offline via Wi-Fi Direct...")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(
                    isDiscovering = false,
                    statusMessage = "Wi-Fi Direct link negotiated; awaiting IP..."
                )
            }

            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(
                    statusMessage = "Wi-Fi Direct connect failed (code $reason)"
                )
            }
        })
        return Result.success(Unit)
    }

    fun stopAll() {
        val mgr = manager ?: return
        val ch = channel
        if (ch != null) {
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
