package com.example.networking.transport

import android.util.Log
import com.example.model.DiscoveredHostPeer
import com.example.model.NearbyOfflineNeed
import com.example.model.SharingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections

@Serializable
private data class OfflineBeaconPacket(
    val code: String,
    val hostName: String,
    val port: Int,
    val net: String,
    val modes: String,
    val pk: String
)

@Serializable
private data class OfflineNeedPacket(
    val deviceUuid: String,
    val deviceName: String,
    val requestedMode: String,
    val note: String = ""
)

/**
 * Zero-permission, offline-capable UDP LAN/Hotspot/Wi-Fi Direct broadcast discovery & offline-need beacon.
 * - Port 51822: Host advertisement beacons -> Client discovery
 * - Port 51823: Client "I need internet offline" request beacons -> Host notification & direct reply
 */
class LocalBeaconDiscovery(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private var advertiserJob: Job? = null
    private var listenerJob: Job? = null
    private var listenSocket: DatagramSocket? = null

    private var offlineNeedBroadcasterJob: Job? = null
    private var offlineNeedListenerJob: Job? = null
    private var offlineNeedListenSocket: DatagramSocket? = null

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredHostPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredHostPeer>> = _discoveredPeers.asStateFlow()

    private val _nearbyOfflineNeeds = MutableStateFlow<List<NearbyOfflineNeed>>(emptyList())
    val nearbyOfflineNeeds: StateFlow<List<NearbyOfflineNeed>> = _nearbyOfflineNeeds.asStateFlow()

    fun startAdvertising(
        sharingCode: String,
        hostName: String,
        port: Int,
        networkTypeProvider: () -> String,
        hostPublicKeyB64: String,
        permittedModesProvider: () -> Set<SharingMode>
    ) {
        stopAdvertising()
        advertiserJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                while (isActive) {
                    val payload = OfflineBeaconPacket(
                        code = sharingCode,
                        hostName = hostName.take(40),
                        port = port,
                        net = networkTypeProvider(),
                        modes = permittedModesProvider().joinToString(",") { it.code },
                        pk = hostPublicKeyB64
                    )
                    val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                    val targets = collectBroadcastTargets()
                    for (target in targets) {
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, target, BEACON_PORT))
                        }
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Beacon advertiser stopped: ${e.message}")
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    fun stopAdvertising() {
        advertiserJob?.cancel()
        advertiserJob = null
    }

    fun sendDirectHostReply(
        targetAddress: InetAddress,
        sharingCode: String,
        hostName: String,
        port: Int,
        networkType: String,
        hostPublicKeyB64: String,
        permittedModes: Set<SharingMode>
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { sock ->
                    val payload = OfflineBeaconPacket(
                        code = sharingCode,
                        hostName = hostName.take(40),
                        port = port,
                        net = networkType,
                        modes = permittedModes.joinToString(",") { it.code },
                        pk = hostPublicKeyB64
                    )
                    val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, targetAddress, BEACON_PORT))
                }
            }
        }
    }

    fun startListening() {
        stopListening()
        _discoveredPeers.value = emptyList()
        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2500
                    bind(InetSocketAddress(BEACON_PORT))
                }
                listenSocket = sock
                val buf = ByteArray(4096)
                val localAddrs = collectOwnLocalAddresses()

                while (isActive && !sock.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        val senderIp = packet.address?.hostAddress ?: continue
                        if (senderIp in localAddrs) continue

                        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val beacon = runCatching { json.decodeFromString<OfflineBeaconPacket>(raw) }.getOrNull() ?: continue
                        val modes = beacon.modes.split(",").mapNotNull { c ->
                            SharingMode.entries.firstOrNull { it.code == c }
                        }.toSet().ifEmpty { SharingMode.entries.toSet() }

                        val peer = DiscoveredHostPeer(
                            serviceName = beacon.code,
                            sharingCode = beacon.code.uppercase(),
                            hostName = beacon.hostName.ifBlank { "WiFiShare Host" },
                            hostIp = senderIp,
                            hostPort = beacon.port,
                            hostPublicKeyB64 = beacon.pk,
                            networkType = beacon.net,
                            permittedModes = modes
                        )
                        _discoveredPeers.value = _discoveredPeers.value
                            .filterNot { it.sharingCode == peer.sharingCode && it.hostIp == peer.hostIp } + peer
                    } catch (_: java.net.SocketTimeoutException) {
                        // Continue polling while listener is active
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Beacon listener ended: ${e.message}")
            } finally {
                runCatching { listenSocket?.close() }
                listenSocket = null
            }
        }
    }

    fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
    }

    fun startBroadcastingOfflineNeed(
        deviceUuid: String,
        deviceName: String,
        requestedMode: SharingMode,
        note: String
    ) {
        stopBroadcastingOfflineNeed()
        offlineNeedBroadcasterJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                while (isActive) {
                    val pkt = OfflineNeedPacket(
                        deviceUuid = deviceUuid,
                        deviceName = deviceName.take(40),
                        requestedMode = requestedMode.code,
                        note = note.take(120)
                    )
                    val bytes = json.encodeToString(pkt).toByteArray(Charsets.UTF_8)
                    val targets = collectBroadcastTargets()
                    for (target in targets) {
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, target, OFFLINE_REQUEST_PORT))
                        }
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Offline need broadcaster stopped: ${e.message}")
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    fun stopBroadcastingOfflineNeed() {
        offlineNeedBroadcasterJob?.cancel()
        offlineNeedBroadcasterJob = null
    }

    fun startListeningForOfflineNeeds(onNeedReceived: (NearbyOfflineNeed, InetAddress) -> Unit) {
        stopListeningForOfflineNeeds()
        _nearbyOfflineNeeds.value = emptyList()
        offlineNeedListenerJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2500
                    bind(InetSocketAddress(OFFLINE_REQUEST_PORT))
                }
                offlineNeedListenSocket = sock
                val buf = ByteArray(4096)
                val localAddrs = collectOwnLocalAddresses()

                while (isActive && !sock.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        val senderAddr = packet.address ?: continue
                        val senderIp = senderAddr.hostAddress ?: continue
                        if (senderIp in localAddrs) continue

                        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val needPkt = runCatching { json.decodeFromString<OfflineNeedPacket>(raw) }.getOrNull() ?: continue
                        val mode = SharingMode.entries.firstOrNull { it.code == needPkt.requestedMode }
                            ?: SharingMode.BROWSER_ONLY
                        val need = NearbyOfflineNeed(
                            deviceUuid = needPkt.deviceUuid,
                            deviceName = needPkt.deviceName.ifBlank { "Offline Device" },
                            requestedMode = mode,
                            note = needPkt.note,
                            senderIp = senderIp,
                            seenAtMs = System.currentTimeMillis()
                        )
                        _nearbyOfflineNeeds.value = _nearbyOfflineNeeds.value
                            .filterNot { it.deviceUuid == need.deviceUuid } + need
                        onNeedReceived(need, senderAddr)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Prune needs older than 20 seconds
                        val cutoff = System.currentTimeMillis() - 20_000L
                        _nearbyOfflineNeeds.value = _nearbyOfflineNeeds.value.filter { it.seenAtMs >= cutoff }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Offline need listener ended: ${e.message}")
            } finally {
                runCatching { offlineNeedListenSocket?.close() }
                offlineNeedListenSocket = null
            }
        }
    }

    fun stopListeningForOfflineNeeds() {
        offlineNeedListenerJob?.cancel()
        offlineNeedListenerJob = null
        runCatching { offlineNeedListenSocket?.close() }
        offlineNeedListenSocket = null
        _nearbyOfflineNeeds.value = emptyList()
    }

    private fun collectBroadcastTargets(): Set<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return@runCatching)
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ifAddr in iface.interfaceAddresses) {
                    val bcast = ifAddr.broadcast
                    if (bcast is Inet4Address) {
                        targets.add(bcast)
                    }
                }
            }
        }
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        return targets
    }

    private fun collectOwnLocalAddresses(): Set<String> {
        val own = hashSetOf("127.0.0.1")
        runCatching {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return own)
            for (iface in ifaces) {
                for (addr in Collections.list(iface.inetAddresses)) {
                    addr.hostAddress?.let { own.add(it) }
                }
            }
        }
        return own
    }

    companion object {
        private const val TAG = "LocalBeaconDiscovery"
        const val BEACON_PORT = 51822
        const val OFFLINE_REQUEST_PORT = 51823
    }
}
