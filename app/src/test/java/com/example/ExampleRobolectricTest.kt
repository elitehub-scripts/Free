package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.authentication.LocalPasswordHasher
import com.example.database.LocalAccountEntity
import com.example.database.SavedPeerEntity
import com.example.database.WiFiShareDatabase
import com.example.database.WiFiShareLocalRepository
import com.example.model.SharingMode
import com.example.networking.crypto.TunnelCrypto
import com.example.networking.proxy.LocalTunnelStreamMux
import com.example.networking.transport.DirectClientConnector
import com.example.networking.transport.DirectHostGatewayServer
import com.example.networking.transport.DirectTcpProbe
import com.example.networking.transport.TrafficAccounting
import com.example.qr.QrCodeGenerator
import com.example.vpn.HostGatewayForwarder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("WiFiShare", appName)
    }

    @Test
    fun `extract sharing code and generate qr bitmap`() {
        val extracted = QrCodeGenerator.extractSharingCode("wifishare://join?code=WFS-8K9M-2P4X&host=192.168.1.10&port=51821")
        assertEquals("WFS-8K9M-2P4X", extracted)
        assertNotNull(QrCodeGenerator.generateQrBitmap("wifishare://join?code=WFS-8K9M-2P4X&host=192.168.1.10&port=51821", 128))
    }

    @Test
    fun `room database persists local account and saved peer without backend`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, WiFiShareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = WiFiShareLocalRepository(db)
            val hashed = LocalPasswordHasher.hashPassword("SecureLocalPass88")
            val id = repo.createLocalAccount(
                LocalAccountEntity(
                    email = "host@local.test",
                    displayName = "Host Phone",
                    passwordSaltHex = hashed.saltHex,
                    passwordHashHex = hashed.hashHex
                )
            )
            assertTrue(id > 0L)
            val loaded = repo.findAccountByEmail("host@local.test")
            assertNotNull(loaded)
            assertTrue(LocalPasswordHasher.verifyPassword("SecureLocalPass88", loaded!!.passwordSaltHex, loaded.passwordHashHex))

            repo.savePeerRecord(
                SavedPeerEntity(
                    role = "HOST",
                    deviceUuid = "192.168.1.10:51821",
                    peerName = "Host Phone",
                    hostIp = "192.168.1.10",
                    hostPort = 51821,
                    sharingCode = "WFS-8K9M-2P4X",
                    modeCode = SharingMode.WHOLE_DEVICE_VPN.code,
                    approvalStatus = "APPROVED"
                )
            )
            val saved = repo.savedHosts.first()
            assertEquals(1, saved.size)
            assertEquals("WFS-8K9M-2P4X", saved.first().sharingCode)
        } finally {
            db.close()
        }
    }

    @Test
    fun `direct p2p host server and client handshake approve and exchange encrypted frame`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val code = "WFS-TEST-1234"

        val pendingDeferred = CompletableDeferred<com.example.model.PendingJoinRequest>()
        val hostReceivedPing = CompletableDeferred<ByteArray>()
        val clientApprovedDeferred = CompletableDeferred<String>()

        val server = DirectHostGatewayServer(
            sharingCode = code,
            hostDisplayName = "Test Host",
            networkType = "WIFI",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "direct",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 4 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { req -> pendingDeferred.complete(req) },
            onPendingClosed = {},
            onServerError = {}
        )

        val port = server.start(0).getOrThrow()
        val connector = DirectClientConnector(
            hostIp = "127.0.0.1",
            hostPort = port,
            sharingCode = code,
            deviceUuid = "client-uuid-1",
            deviceName = "Test Client",
            clientPublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            requestedMode = SharingMode.BROWSER_ONLY,
            stunEndpoint = "direct",
            scope = scope
        )

        try {
            val clientJob = scope.launch(Dispatchers.IO) {
                connector.connectAndAwaitApproval(
                    onAwaitingApproval = {},
                    onRejected = { reason -> clientApprovedDeferred.completeExceptionally(AssertionError(reason)) },
                    onApproved = { hostInfo, assignedIp, createTransport ->
                        val cipher = TunnelCrypto.deriveSessionCipher(
                            localKeyPair = clientKp,
                            remotePublicKeyB64 = hostInfo.hostPublicKeyB64,
                            sessionSalt = code.toByteArray(Charsets.UTF_8),
                            isHost = false
                        )
                        val transport = createTransport(cipher, TrafficAccounting(), {}, {})
                        transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_PING, 0, "hello-p2p".toByteArray()))
                        clientApprovedDeferred.complete(assignedIp)
                    },
                    onError = { err -> clientApprovedDeferred.completeExceptionally(err) }
                )
            }

            val req = withTimeout(5_000L) { pendingDeferred.await() }
            assertEquals("Test Client", req.clientName)

            val hostCipher = TunnelCrypto.deriveSessionCipher(
                localKeyPair = hostKp,
                remotePublicKeyB64 = req.clientPublicKeyB64,
                sessionSalt = code.toByteArray(Charsets.UTF_8),
                isHost = true
            )
            val hostTransport = server.approvePendingClient(
                connectionId = req.connectionId,
                assignedIp = "10.88.0.2",
                cipher = hostCipher,
                accounting = TrafficAccounting(),
                onFrameReceived = { frame ->
                    if (frame.type == TunnelCrypto.FRAME_PING) {
                        hostReceivedPing.complete(frame.payload)
                    }
                },
                onTransportError = {}
            ).getOrThrow()

            val assignedIp = withTimeout(5_000L) { clientApprovedDeferred.await() }
            val pingPayload = withTimeout(5_000L) { hostReceivedPing.await() }

            assertEquals("10.88.0.2", assignedIp)
            assertEquals("hello-p2p", String(pingPayload))

            hostTransport.close()
            clientJob.cancel()
        } finally {
            connector.cancel()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `offline trusted peer auto-accept approves returning client without internet`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val code = "WFS-OFFL-7788"
        val trustedUuid = "trusted-offline-client-1"

        val autoApproved = CompletableDeferred<com.example.model.PendingJoinRequest>()
        val clientAssigned = CompletableDeferred<String>()

        val server = DirectHostGatewayServer(
            sharingCode = code,
            hostDisplayName = "Offline Host",
            networkType = "OFFLINE_LOCAL",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "Offline LAN / Direct P2P",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 4 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { req -> autoApproved.completeExceptionally(AssertionError("Should auto-accept trusted peer ${req.clientName}")) },
            onPendingClosed = {},
            onServerError = {},
            autoAcceptTrustedProvider = { true },
            trustedDeviceUuidsProvider = { setOf(trustedUuid) },
            onAutoApproveRequested = { req -> autoApproved.complete(req) }
        )

        val port = server.start(0).getOrThrow()
        val connector = DirectClientConnector(
            hostIp = "127.0.0.1",
            hostPort = port,
            sharingCode = code,
            deviceUuid = trustedUuid,
            deviceName = "Trusted Phone",
            clientPublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            requestedMode = SharingMode.BROWSER_AND_DOWNLOADS,
            stunEndpoint = "offline-local",
            scope = scope
        )

        try {
            val clientJob = scope.launch(Dispatchers.IO) {
                connector.connectAndAwaitApproval(
                    onAwaitingApproval = {},
                    onRejected = { reason -> clientAssigned.completeExceptionally(AssertionError(reason)) },
                    onApproved = { _, assignedIp, _ -> clientAssigned.complete(assignedIp) },
                    onError = { err -> clientAssigned.completeExceptionally(err) }
                )
            }

            val req = withTimeout(5_000L) { autoApproved.await() }
            assertEquals(trustedUuid, req.clientDeviceId)

            val hostCipher = TunnelCrypto.deriveSessionCipher(
                localKeyPair = hostKp,
                remotePublicKeyB64 = req.clientPublicKeyB64,
                sessionSalt = code.toByteArray(Charsets.UTF_8),
                isHost = true
            )
            val transport = server.approvePendingClient(
                connectionId = req.connectionId,
                assignedIp = "10.88.0.2",
                cipher = hostCipher,
                accounting = TrafficAccounting(),
                onFrameReceived = {},
                onTransportError = {}
            ).getOrThrow()

            val assignedIp = withTimeout(5_000L) { clientAssigned.await() }
            assertEquals("10.88.0.2", assignedIp)

            transport.close()
            clientJob.cancel()
        } finally {
            connector.cancel()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `client can request internet offline without sharing code and establish encrypted tunnel`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val hostSecretSessionCode = "WFS-OFFL-9911"

        val pendingDeferred = CompletableDeferred<com.example.model.PendingJoinRequest>()
        val hostReceivedPing = CompletableDeferred<ByteArray>()
        val clientApprovedIp = CompletableDeferred<String>()

        val server = DirectHostGatewayServer(
            sharingCode = hostSecretSessionCode,
            hostDisplayName = "Offline Share Host",
            networkType = "OFFLINE_LOCAL",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "Offline LAN",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 4 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { req -> pendingDeferred.complete(req) },
            onPendingClosed = {},
            onServerError = {},
            allowOpenOfflineRequestsProvider = { true }
        )

        val port = server.start(0).getOrThrow()
        val connector = DirectClientConnector(
            hostIp = "127.0.0.1",
            hostPort = port,
            sharingCode = "",
            deviceUuid = "offline-requester-uuid",
            deviceName = "Offline Phone",
            clientPublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            requestedMode = SharingMode.BROWSER_ONLY,
            stunEndpoint = "offline-local",
            scope = scope,
            isOfflineRequest = true,
            requestNote = "Need quick offline maps"
        )

        try {
            val clientJob = scope.launch(Dispatchers.IO) {
                connector.connectAndAwaitApproval(
                    onAwaitingApproval = {},
                    onRejected = { reason -> clientApprovedIp.completeExceptionally(AssertionError(reason)) },
                    onApproved = { hostInfo, assignedIp, createTransport ->
                        val effectiveSalt = hostInfo.resolvedSharingCode.toByteArray(Charsets.UTF_8)
                        val cipher = TunnelCrypto.deriveSessionCipher(
                            localKeyPair = clientKp,
                            remotePublicKeyB64 = hostInfo.hostPublicKeyB64,
                            sessionSalt = effectiveSalt,
                            isHost = false
                        )
                        val transport = createTransport(cipher, TrafficAccounting(), {}, {})
                        transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_PING, 0, "offline-hello".toByteArray()))
                        clientApprovedIp.complete(assignedIp)
                    },
                    onError = { err -> clientApprovedIp.completeExceptionally(err) }
                )
            }

            val req = withTimeout(5_000L) { pendingDeferred.await() }
            assertTrue(req.isOfflineRequest)
            assertEquals("Need quick offline maps", req.requestNote)

            val hostCipher = TunnelCrypto.deriveSessionCipher(
                localKeyPair = hostKp,
                remotePublicKeyB64 = req.clientPublicKeyB64,
                sessionSalt = hostSecretSessionCode.toByteArray(Charsets.UTF_8),
                isHost = true
            )
            val hostTransport = server.approvePendingClient(
                connectionId = req.connectionId,
                assignedIp = "10.88.0.2",
                cipher = hostCipher,
                accounting = TrafficAccounting(),
                onFrameReceived = { frame ->
                    if (frame.type == TunnelCrypto.FRAME_PING) {
                        hostReceivedPing.complete(frame.payload)
                    }
                },
                onTransportError = {}
            ).getOrThrow()

            val assignedIp = withTimeout(5_000L) { clientApprovedIp.await() }
            val pingPayload = withTimeout(5_000L) { hostReceivedPing.await() }
            assertEquals("10.88.0.2", assignedIp)
            assertEquals("offline-hello", String(pingPayload))

            hostTransport.close()
            clientJob.cancel()
        } finally {
            connector.cancel()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `host rejects open offline internet request when policy disabled`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val rejectedDeferred = CompletableDeferred<String>()

        val server = DirectHostGatewayServer(
            sharingCode = "WFS-PRIV-0001",
            hostDisplayName = "Locked Host",
            networkType = "WIFI",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "direct",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 4 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { rejectedDeferred.completeExceptionally(AssertionError("Should reject open offline request")) },
            onPendingClosed = {},
            onServerError = {},
            allowOpenOfflineRequestsProvider = { false }
        )

        val port = server.start(0).getOrThrow()
        val connector = DirectClientConnector(
            hostIp = "127.0.0.1",
            hostPort = port,
            sharingCode = "",
            deviceUuid = "offline-requester-2",
            deviceName = "Stranger Phone",
            clientPublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            requestedMode = SharingMode.BROWSER_ONLY,
            stunEndpoint = "offline-local",
            scope = scope,
            isOfflineRequest = true,
            requestNote = "Please share"
        )

        try {
            val clientJob = scope.launch(Dispatchers.IO) {
                connector.connectAndAwaitApproval(
                    onAwaitingApproval = {},
                    onRejected = { reason -> rejectedDeferred.complete(reason) },
                    onApproved = { _, _, _ -> rejectedDeferred.completeExceptionally(AssertionError("Should not approve")) },
                    onError = { err -> rejectedDeferred.completeExceptionally(err) }
                )
            }
            val reason = withTimeout(5_000L) { rejectedDeferred.await() }
            assertTrue(reason.contains("requires a sharing code", ignoreCase = true))
            clientJob.cancel()
        } finally {
            connector.cancel()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `direct tcp probe discovers host without opening pending request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val joinCalls = AtomicInteger(0)

        val server = DirectHostGatewayServer(
            sharingCode = "WFS-PRBE-2026",
            hostDisplayName = "Probeable Host",
            networkType = "WIFI",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "direct",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 5 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { joinCalls.incrementAndGet() },
            onPendingClosed = {},
            onServerError = {}
        )

        val port = server.start(0).getOrThrow()
        try {
            val discovered = withTimeout(3_000L) {
                DirectTcpProbe.probeHost("127.0.0.1", port, timeoutMs = 1_000)
            }
            assertNotNull(discovered)
            assertEquals("WFS-PRBE-2026", discovered!!.sharingCode)
            assertEquals("Probeable Host", discovered.hostName)
            assertEquals(port, discovered.hostPort)
            assertEquals(0, joinCalls.get())
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `public open wifi share auto-accepts anyone nearby without manual tap`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val hostSecretSessionCode = "WFS-OPEN-8899"

        val autoApprovedReq = CompletableDeferred<com.example.model.PendingJoinRequest>()
        val clientAssignedIp = CompletableDeferred<String>()

        val server = DirectHostGatewayServer(
            sharingCode = hostSecretSessionCode,
            hostDisplayName = "Public Host",
            networkType = "WIFI",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "direct",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 5 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { req -> autoApprovedReq.completeExceptionally(AssertionError("Should auto-accept ${req.clientName}")) },
            onPendingClosed = {},
            onServerError = {},
            allowOpenOfflineRequestsProvider = { true },
            autoAcceptOpenRequestsProvider = { true },
            onAutoApproveRequested = { req -> autoApprovedReq.complete(req) }
        )

        val port = server.start(0).getOrThrow()
        val connector = DirectClientConnector(
            hostIp = "127.0.0.1",
            hostPort = port,
            sharingCode = "",
            deviceUuid = "random-nearby-guest",
            deviceName = "Nearby Guest",
            clientPublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            requestedMode = SharingMode.BROWSER_ONLY,
            stunEndpoint = "offline-local",
            scope = scope,
            isOfflineRequest = true,
            requestNote = "Quick browse"
        )

        try {
            val clientJob = scope.launch(Dispatchers.IO) {
                connector.connectAndAwaitApproval(
                    onAwaitingApproval = {},
                    onRejected = { reason -> clientAssignedIp.completeExceptionally(AssertionError(reason)) },
                    onApproved = { _, assignedIp, _ -> clientAssignedIp.complete(assignedIp) },
                    onError = { err -> clientAssignedIp.completeExceptionally(err) }
                )
            }

            val req = withTimeout(5_000L) { autoApprovedReq.await() }
            val hostCipher = TunnelCrypto.deriveSessionCipher(
                localKeyPair = hostKp,
                remotePublicKeyB64 = req.clientPublicKeyB64,
                sessionSalt = hostSecretSessionCode.toByteArray(Charsets.UTF_8),
                isHost = true
            )
            val transport = server.approvePendingClient(
                connectionId = req.connectionId,
                assignedIp = "10.88.0.5",
                cipher = hostCipher,
                accounting = TrafficAccounting(),
                onFrameReceived = {},
                onTransportError = {}
            ).getOrThrow()

            val ip = withTimeout(5_000L) { clientAssignedIp.await() }
            assertEquals("10.88.0.5", ip)
            transport.close()
            clientJob.cancel()
        } finally {
            connector.cancel()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `browser stream mux forwards immediate http request and response through host gateway forwarder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val code = "WFS-HTTP-5511"

        // Local upstream HTTP test server simulating the internet website reached by Host
        val upstreamServer = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val upstreamPort = upstreamServer.localPort
        val upstreamJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val sock = upstreamServer.accept()
                sock.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "WIFISHARE_TUNNEL_HTTP_OK"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    s.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                }
            }
        }

        val pendingDeferred = CompletableDeferred<com.example.model.PendingJoinRequest>()
        val muxDeferred = CompletableDeferred<LocalTunnelStreamMux>()

        val server = DirectHostGatewayServer(
            sharingCode = code,
            hostDisplayName = "HTTP Forwarder Host",
            networkType = "WIFI",
            hostPublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            stunEndpoint = "direct",
            permittedModesProvider = { SharingMode.entries.toSet() },
            maxDevicesProvider = { 4 },
            activeDevicesCountProvider = { 0 },
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 600L,
            scope = scope,
            onJoinRequested = { req -> pendingDeferred.complete(req) },
            onPendingClosed = {},
            onServerError = {}
        )

        val port = server.start(0).getOrThrow()
        val connector = DirectClientConnector(
            hostIp = "127.0.0.1",
            hostPort = port,
            sharingCode = code,
            deviceUuid = "http-client-1",
            deviceName = "Browser Client",
            clientPublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            requestedMode = SharingMode.BROWSER_ONLY,
            stunEndpoint = "direct",
            scope = scope
        )

        var hostForwarder: HostGatewayForwarder? = null
        var clientMux: LocalTunnelStreamMux? = null
        try {
            val clientJob = scope.launch(Dispatchers.IO) {
                connector.connectAndAwaitApproval(
                    onAwaitingApproval = {},
                    onRejected = { reason -> muxDeferred.completeExceptionally(AssertionError(reason)) },
                    onApproved = { hostInfo, _, createTransport ->
                        val cipher = TunnelCrypto.deriveSessionCipher(
                            localKeyPair = clientKp,
                            remotePublicKeyB64 = hostInfo.hostPublicKeyB64,
                            sessionSalt = code.toByteArray(Charsets.UTF_8),
                            isHost = false
                        )
                        var localMux: LocalTunnelStreamMux? = null
                        val transport = createTransport(cipher, TrafficAccounting(), { frame ->
                            localMux?.onFrameReceived(frame)
                        }, {})
                        localMux = LocalTunnelStreamMux(cipher, transport, scope).also {
                            it.startLocalProxy()
                            muxDeferred.complete(it)
                        }
                    },
                    onError = { err -> muxDeferred.completeExceptionally(err) }
                )
            }

            val req = withTimeout(5_000L) { pendingDeferred.await() }
            val hostCipher = TunnelCrypto.deriveSessionCipher(
                localKeyPair = hostKp,
                remotePublicKeyB64 = req.clientPublicKeyB64,
                sessionSalt = code.toByteArray(Charsets.UTF_8),
                isHost = true
            )
            var forwarderRef: HostGatewayForwarder? = null
            val hostTransport = server.approvePendingClient(
                connectionId = req.connectionId,
                assignedIp = "10.88.0.2",
                cipher = hostCipher,
                accounting = TrafficAccounting(),
                onFrameReceived = { frame -> forwarderRef?.handleIncomingFrame(frame) },
                onTransportError = {}
            ).getOrThrow()

            hostForwarder = HostGatewayForwarder(
                clientLabel = req.clientName,
                cipher = hostCipher,
                transport = hostTransport,
                accounting = TrafficAccounting(),
                activeNetworkProvider = { null },
                scope = scope,
                onClientDisconnected = {},
                allowLoopbackUpstream = true
            ).also { forwarderRef = it }

            clientMux = withTimeout(5_000L) { muxDeferred.await() }
            val httpClient = clientMux.createTunnelOkHttpClient()
            val responseText = withTimeout(5_000L) {
                val httpReq = Request.Builder().url("http://127.0.0.1:$upstreamPort/hello").build()
                httpClient.newCall(httpReq).execute().use { resp ->
                    assertEquals(200, resp.code)
                    resp.body?.string().orEmpty()
                }
            }
            assertEquals("WIFISHARE_TUNNEL_HTTP_OK", responseText)

            hostTransport.close()
            clientJob.cancel()
        } finally {
            clientMux?.close()
            hostForwarder?.close()
            connector.cancel()
            server.stop()
            runCatching { upstreamServer.close() }
            upstreamJob.cancel()
            scope.cancel()
        }
    }
}
