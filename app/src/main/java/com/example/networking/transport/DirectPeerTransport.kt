package com.example.networking.transport

import android.util.Log
import com.example.model.HostPublicInfo
import com.example.model.PendingJoinRequest
import com.example.model.SharingMode
import com.example.networking.crypto.DecryptedTunnelFrame
import com.example.networking.crypto.TunnelCrypto
import com.example.networking.crypto.TunnelSessionCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val OP_ENCRYPTED_FRAME: Int = 0x01
private const val OP_JOIN_REQUEST: Int = 0x10
private const val OP_JOIN_PENDING: Int = 0x11
private const val OP_JOIN_APPROVED: Int = 0x12
private const val OP_JOIN_REJECTED: Int = 0x13
private const val OP_KEEPALIVE: Int = 0x14

private val controlJson = Json { ignoreUnknownKeys = true }

@Serializable
data class DirectJoinRequestPayload(
    val sharingCode: String,
    val deviceUuid: String,
    val deviceName: String,
    val clientPublicKey: String,
    val requestedMode: String,
    val stunEndpoint: String = "direct"
)

@Serializable
data class DirectJoinPendingPayload(
    val hostName: String,
    val networkType: String,
    val hostPublicKey: String,
    val stunEndpoint: String,
    val permittedModes: List<String>,
    val maxDevices: Int,
    val activeDevices: Int,
    val expiresAt: Long
)

@Serializable
data class DirectJoinApprovedPayload(
    val assignedIp: String,
    val hostPublicKey: String,
    val permittedModes: List<String>
)

@Serializable
data class DirectJoinRejectedPayload(
    val reason: String
)

/**
 * Direct peer-to-peer TCP socket transport carrying end-to-end encrypted WiFiShare frames.
 */
class DirectSocketTunnelTransport(
    private val socket: Socket,
    private val out: DataOutputStream,
    private val inp: DataInputStream,
    private val cipher: TunnelSessionCipher,
    private val accounting: TrafficAccounting,
    private val scope: CoroutineScope,
    override val transportLabel: String,
    private val onFrameReceived: suspend (DecryptedTunnelFrame) -> Unit,
    private val onTransportError: (Throwable) -> Unit
) : TunnelPeerTransport {

    @Volatile
    override var isConnected: Boolean = true
        private set

    private var readerJob: Job? = null
    private var keepaliveJob: Job? = null

    fun startReaderLoop() {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isConnected) {
                    val op = inp.readByte().toInt() and 0xFF
                    val len = inp.readInt()
                    if (len < 0 || len > 262_144) {
                        throw IllegalStateException("Invalid direct tunnel frame length: $len")
                    }
                    val payload = ByteArray(len)
                    if (len > 0) inp.readFully(payload)
                    when (op) {
                        OP_ENCRYPTED_FRAME -> {
                            val frame = cipher.open(payload)
                            if (frame == null) {
                                accounting.recordReplayDrop()
                                continue
                            }
                            accounting.recordRx(payload.size)
                            onFrameReceived(frame)
                        }
                        OP_KEEPALIVE -> Unit
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    isConnected = false
                    onTransportError(e)
                }
            }
        }

        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                delay(12_000)
                runCatching {
                    synchronized(out) {
                        out.writeByte(OP_KEEPALIVE)
                        out.writeInt(0)
                        out.flush()
                    }
                }
            }
        }
    }

    override fun sendEncrypted(frameBytes: ByteArray): Boolean {
        if (!isConnected) return false
        return try {
            synchronized(out) {
                out.writeByte(OP_ENCRYPTED_FRAME)
                out.writeInt(frameBytes.size)
                out.write(frameBytes)
                out.flush()
            }
            accounting.recordTx(frameBytes.size)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct socket send error", e)
            isConnected = false
            onTransportError(e)
            false
        }
    }

    override fun close() {
        isConnected = false
        readerJob?.cancel()
        keepaliveJob?.cancel()
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "DirectSocketTransport"
    }
}

/**
 * Serverless Host Gateway Server running directly on the Host Android device.
 * Accepts incoming direct Client connections, verifies sharing codes and device limits,
 * holds sockets in Awaiting-Approval until the Host explicitly approves, and transitions
 * approved sockets into encrypted TunnelPeerTransport channels.
 */
class DirectHostGatewayServer(
    private val sharingCode: String,
    private val hostDisplayName: String,
    private val networkType: String,
    private val hostPublicKeyB64: String,
    private val stunEndpoint: String,
    private val permittedModesProvider: () -> Set<SharingMode>,
    private val maxDevicesProvider: () -> Int,
    private val activeDevicesCountProvider: () -> Int,
    private val expiresAtEpochSec: Long,
    private val scope: CoroutineScope,
    private val onJoinRequested: (PendingJoinRequest) -> Unit,
    private val onPendingClosed: (Long) -> Unit,
    private val onServerError: (Throwable) -> Unit
) {
    private class PendingSocketEntry(
        val connectionId: Long,
        val socket: Socket,
        val inp: DataInputStream,
        val out: DataOutputStream,
        val request: PendingJoinRequest,
        val keepaliveJob: Job
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val nextConnId = AtomicLong(1L)
    private val pendingSockets = ConcurrentHashMap<Long, PendingSocketEntry>()

    var actualListenPort: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(preferredPort: Int): Result<Int> {
        return runCatching {
            val ss = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort))
                }
            }.getOrElse {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
            }
            serverSocket = ss
            actualListenPort = ss.localPort
            isRunning = true

            acceptJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && isRunning) {
                        val clientSocket = ss.accept()
                        clientSocket.tcpNoDelay = true
                        launch { handleIncomingSocket(clientSocket) }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        isRunning = false
                        onServerError(e)
                    }
                }
            }
            actualListenPort
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val inp = DataInputStream(socket.getInputStream())
            val out = DataOutputStream(socket.getOutputStream())

            val op = inp.readByte().toInt() and 0xFF
            val len = inp.readInt()
            if (op != OP_JOIN_REQUEST || len <= 0 || len > 32_768) {
                sendRejection(out, "Invalid WiFiShare handshake frame.")
                socket.close()
                return
            }

            val reqBytes = ByteArray(len)
            inp.readFully(reqBytes)
            val req = controlJson.decodeFromString<DirectJoinRequestPayload>(
                String(reqBytes, Charsets.UTF_8)
            )

            val nowSec = System.currentTimeMillis() / 1000L
            if (!req.sharingCode.equals(sharingCode, ignoreCase = true)) {
                sendRejection(out, "Sharing code does not match this Host session.")
                socket.close()
                return
            }
            if (expiresAtEpochSec in 1 until nowSec) {
                sendRejection(out, "This Host sharing code has expired.")
                socket.close()
                return
            }
            val permitted = permittedModesProvider()
            val requestedMode = SharingMode.entries.firstOrNull { it.code == req.requestedMode }
                ?: SharingMode.BROWSER_ONLY
            if (requestedMode !in permitted) {
                sendRejection(out, "Host policy does not permit ${requestedMode.label}.")
                socket.close()
                return
            }
            if (activeDevicesCountProvider() >= maxDevicesProvider()) {
                sendRejection(out, "Host has reached its max connected device limit (${maxDevicesProvider()}).")
                socket.close()
                return
            }

            // Send pending confirmation + Host public summary
            val pendingInfo = DirectJoinPendingPayload(
                hostName = hostDisplayName,
                networkType = networkType,
                hostPublicKey = hostPublicKeyB64,
                stunEndpoint = stunEndpoint,
                permittedModes = permitted.map { it.code },
                maxDevices = maxDevicesProvider(),
                activeDevices = activeDevicesCountProvider(),
                expiresAt = expiresAtEpochSec
            )
            sendControlFrame(out, OP_JOIN_PENDING, controlJson.encodeToString(pendingInfo).toByteArray(Charsets.UTF_8))
            socket.soTimeout = 0

            val connId = nextConnId.getAndIncrement()
            val remoteEndpoint = socket.inetAddress?.hostAddress?.let { "$it:${socket.port}" } ?: req.stunEndpoint
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val pendingReq = PendingJoinRequest(
                connectionId = connId,
                clientDeviceId = req.deviceUuid,
                clientName = req.deviceName.ifBlank { "Android Client" },
                requestedMode = requestedMode,
                clientPublicKeyB64 = req.clientPublicKey,
                clientEndpoint = remoteEndpoint,
                requestedAt = timestamp
            )

            // Keep pending socket alive and detect early client cancel
            val keepaliveJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && pendingSockets.containsKey(connId)) {
                        delay(8_000)
                        synchronized(out) {
                            out.writeByte(OP_KEEPALIVE)
                            out.writeInt(0)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                    if (pendingSockets.remove(connId) != null) {
                        runCatching { socket.close() }
                        onPendingClosed(connId)
                    }
                }
            }

            pendingSockets[connId] = PendingSocketEntry(connId, socket, inp, out, pendingReq, keepaliveJob)
            onJoinRequested(pendingReq)
        } catch (e: Exception) {
            Log.w(TAG, "Incoming client handshake failed: ${e.message}")
            runCatching { socket.close() }
        }
    }

    suspend fun approvePendingClient(
        connectionId: Long,
        assignedIp: String,
        cipher: TunnelSessionCipher,
        accounting: TrafficAccounting,
        onFrameReceived: suspend (DecryptedTunnelFrame) -> Unit,
        onTransportError: (Throwable) -> Unit
    ): Result<DirectSocketTunnelTransport> = withContext(Dispatchers.IO) {
        val entry = pendingSockets.remove(connectionId)
            ?: return@withContext Result.failure(IllegalStateException("Client request is no longer waiting."))
        entry.keepaliveJob.cancel()

        runCatching {
            val approved = DirectJoinApprovedPayload(
                assignedIp = assignedIp,
                hostPublicKey = hostPublicKeyB64,
                permittedModes = permittedModesProvider().map { it.code }
            )
            sendControlFrame(entry.out, OP_JOIN_APPROVED, controlJson.encodeToString(approved).toByteArray(Charsets.UTF_8))

            val label = "Direct P2P (${entry.request.clientEndpoint})"
            DirectSocketTunnelTransport(
                socket = entry.socket,
                out = entry.out,
                inp = entry.inp,
                cipher = cipher,
                accounting = accounting,
                scope = scope,
                transportLabel = label,
                onFrameReceived = onFrameReceived,
                onTransportError = onTransportError
            ).also { it.startReaderLoop() }
        }.onFailure {
            runCatching { entry.socket.close() }
        }
    }

    suspend fun rejectPendingClient(connectionId: Long, reason: String) = withContext(Dispatchers.IO) {
        val entry = pendingSockets.remove(connectionId) ?: return@withContext
        entry.keepaliveJob.cancel()
        runCatching {
            sendRejection(entry.out, reason)
            entry.socket.close()
        }
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        pendingSockets.values.forEach { entry ->
            entry.keepaliveJob.cancel()
            runCatching { entry.socket.close() }
        }
        pendingSockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun sendRejection(out: DataOutputStream, reason: String) {
        val bytes = controlJson.encodeToString(DirectJoinRejectedPayload(reason)).toByteArray(Charsets.UTF_8)
        sendControlFrame(out, OP_JOIN_REJECTED, bytes)
    }

    private fun sendControlFrame(out: DataOutputStream, op: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(op)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    companion object {
        private const val TAG = "DirectHostGatewayServer"
    }
}

/**
 * Serverless Client connector that connects directly to a Host's DirectHostGatewayServer socket,
 * awaits explicit Host approval, and promotes the socket into an encrypted DirectSocketTunnelTransport.
 */
class DirectClientConnector(
    private val hostIp: String,
    private val hostPort: Int,
    private val sharingCode: String,
    private val deviceUuid: String,
    private val deviceName: String,
    private val clientPublicKeyB64: String,
    private val requestedMode: SharingMode,
    private val stunEndpoint: String,
    private val scope: CoroutineScope
) {
    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var cancelled: Boolean = false

    fun cancel() {
        cancelled = true
        runCatching { activeSocket?.close() }
    }

    suspend fun connectAndAwaitApproval(
        onAwaitingApproval: (HostPublicInfo) -> Unit,
        onRejected: (String) -> Unit,
        onApproved: suspend (
            hostInfo: HostPublicInfo,
            assignedIp: String,
            createTransport: (
                cipher: TunnelSessionCipher,
                accounting: TrafficAccounting,
                onFrame: suspend (DecryptedTunnelFrame) -> Unit,
                onError: (Throwable) -> Unit
            ) -> DirectSocketTunnelTransport
        ) -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            activeSocket = socket
            socket.connect(InetSocketAddress(InetAddress.getByName(hostIp), hostPort), 8_000)
            socket.tcpNoDelay = true

            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())

            val reqPayload = DirectJoinRequestPayload(
                sharingCode = sharingCode,
                deviceUuid = deviceUuid,
                deviceName = deviceName,
                clientPublicKey = clientPublicKeyB64,
                requestedMode = requestedMode.code,
                stunEndpoint = stunEndpoint
            )
            val reqBytes = controlJson.encodeToString(reqPayload).toByteArray(Charsets.UTF_8)
            synchronized(out) {
                out.writeByte(OP_JOIN_REQUEST)
                out.writeInt(reqBytes.size)
                out.write(reqBytes)
                out.flush()
            }

            var latestHostInfo: HostPublicInfo? = null
            while (!cancelled) {
                val op = inp.readByte().toInt() and 0xFF
                val len = inp.readInt()
                if (len < 0 || len > 131_072) {
                    throw IllegalStateException("Malformed control frame length: $len")
                }
                val payload = ByteArray(len)
                if (len > 0) inp.readFully(payload)

                when (op) {
                    OP_JOIN_PENDING -> {
                        val info = controlJson.decodeFromString<DirectJoinPendingPayload>(
                            String(payload, Charsets.UTF_8)
                        )
                        val permitted = info.permittedModes.mapNotNull { code ->
                            SharingMode.entries.firstOrNull { it.code == code }
                        }.toSet()
                        val hostInfo = HostPublicInfo(
                            sessionId = System.currentTimeMillis(),
                            hostDisplayName = info.hostName,
                            networkType = info.networkType,
                            hostPublicKeyB64 = info.hostPublicKey,
                            hostIp = hostIp,
                            hostPort = hostPort,
                            stunEndpoint = info.stunEndpoint,
                            permittedModes = permitted,
                            maxDevices = info.maxDevices,
                            activeDevices = info.activeDevices,
                            expiresAtEpochSec = info.expiresAt
                        )
                        latestHostInfo = hostInfo
                        onAwaitingApproval(hostInfo)
                    }
                    OP_JOIN_APPROVED -> {
                        val approved = controlJson.decodeFromString<DirectJoinApprovedPayload>(
                            String(payload, Charsets.UTF_8)
                        )
                        val hostInfo = (latestHostInfo ?: HostPublicInfo(
                            sessionId = System.currentTimeMillis(),
                            hostDisplayName = "WiFiShare Host ($hostIp)",
                            networkType = "DIRECT",
                            hostPublicKeyB64 = approved.hostPublicKey,
                            hostIp = hostIp,
                            hostPort = hostPort,
                            stunEndpoint = "direct",
                            permittedModes = SharingMode.entries.toSet(),
                            maxDevices = 4,
                            activeDevices = 1,
                            expiresAtEpochSec = 0L
                        )).copy(hostPublicKeyB64 = approved.hostPublicKey)

                        onApproved(hostInfo, approved.assignedIp) { cipher, accounting, onFrame, onErr ->
                            DirectSocketTunnelTransport(
                                socket = socket,
                                out = out,
                                inp = inp,
                                cipher = cipher,
                                accounting = accounting,
                                scope = scope,
                                transportLabel = "Direct P2P ($hostIp:$hostPort)",
                                onFrameReceived = onFrame,
                                onTransportError = onErr
                            ).also { it.startReaderLoop() }
                        }
                        return@withContext
                    }
                    OP_JOIN_REJECTED -> {
                        val rejected = controlJson.decodeFromString<DirectJoinRejectedPayload>(
                            String(payload, Charsets.UTF_8)
                        )
                        runCatching { socket.close() }
                        onRejected(rejected.reason)
                        return@withContext
                    }
                    OP_KEEPALIVE -> Unit
                }
            }
        } catch (e: Exception) {
            if (!cancelled) {
                runCatching { activeSocket?.close() }
                onError(e)
            }
        }
    }
}
