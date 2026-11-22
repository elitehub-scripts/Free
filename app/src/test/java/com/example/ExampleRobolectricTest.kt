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
import com.example.networking.transport.DirectClientConnector
import com.example.networking.transport.DirectHostGatewayServer
import com.example.networking.transport.TrafficAccounting
import com.example.qr.QrCodeGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
