package com.example

import com.example.authentication.LocalPasswordHasher
import com.example.browser.BrowserStateHolder
import com.example.networking.crypto.ReplayWindow
import com.example.networking.crypto.TunnelCrypto
import com.example.qr.QrCodeGenerator
import com.example.settings.SearchEngine
import com.example.vpn.IpPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WiFiShareCoreTests {

    @Test
    fun `ecdh tunnel cipher encrypts decrypts and blocks replay`() {
        val hostKp = TunnelCrypto.generateEphemeralKeyPair()
        val clientKp = TunnelCrypto.generateEphemeralKeyPair()
        val salt = "WFS-8K9M-2P4X".toByteArray()

        val hostCipher = TunnelCrypto.deriveSessionCipher(
            localKeyPair = hostKp,
            remotePublicKeyB64 = TunnelCrypto.encodePublicKey(clientKp.public),
            sessionSalt = salt,
            isHost = true
        )
        val clientCipher = TunnelCrypto.deriveSessionCipher(
            localKeyPair = clientKp,
            remotePublicKeyB64 = TunnelCrypto.encodePublicKey(hostKp.public),
            sessionSalt = salt,
            isHost = false
        )

        val payload = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val sealed = clientCipher.seal(TunnelCrypto.FRAME_STREAM_DATA, 42, payload)

        val opened = hostCipher.open(sealed)
        assertNotNull(opened)
        assertEquals(TunnelCrypto.FRAME_STREAM_DATA, opened!!.type)
        assertEquals(42, opened.streamId)
        assertArrayEquals(payload, opened.payload)

        // Replay of exact same packet must be rejected
        assertNull(hostCipher.open(sealed))
    }

    @Test
    fun `replay window rejects duplicate and stale packets`() {
        val window = ReplayWindow()
        assertTrue(window.checkAndAccept(0))
        assertTrue(window.checkAndAccept(5))
        assertTrue(window.checkAndAccept(3))
        assertFalse(window.checkAndAccept(3)) // duplicate
        assertTrue(window.checkAndAccept(100))
        assertFalse(window.checkAndAccept(10)) // older than 64-packet window
    }

    @Test
    fun `ip packet codec builds and parses ipv4 udp and tcp and enforces client isolation`() {
        val srcIp = byteArrayOf(10, 88, 0, 2)
        val dstIp = byteArrayOf(1, 1, 1, 1)
        val udpPayload = byteArrayOf(1, 2, 3, 4, 5)
        val builtUdp = IpPacketCodec.buildIpv4UdpPacket(srcIp, dstIp, 54321, 53, udpPayload)

        val parsedIp = IpPacketCodec.parseIpv4(builtUdp)
        assertNotNull(parsedIp)
        assertEquals(IpPacketCodec.PROTO_UDP, parsedIp!!.protocol)
        val parsedUdp = IpPacketCodec.parseUdp(parsedIp.payload)
        assertNotNull(parsedUdp)
        assertEquals(54321, parsedUdp!!.srcPort)
        assertEquals(53, parsedUdp.dstPort)
        assertArrayEquals(udpPayload, parsedUdp.payload)

        val builtTcp = IpPacketCodec.buildIpv4TcpPacket(
            srcIp, dstIp, 40000, 443, 123456L, 654321L, IpPacketCodec.TCP_SYN or IpPacketCodec.TCP_ACK
        )
        val parsedTcpIp = IpPacketCodec.parseIpv4(builtTcp)!!
        val parsedTcp = IpPacketCodec.parseTcp(parsedTcpIp.payload)!!
        assertEquals(40000, parsedTcp.srcPort)
        assertEquals(443, parsedTcp.dstPort)
        assertEquals(123456L, parsedTcp.seqNum)

        assertTrue(IpPacketCodec.isIsolatedLocalAddress(byteArrayOf(10, 88, 0, 3)))
        assertTrue(IpPacketCodec.isIsolatedLocalAddress(byteArrayOf(127, 0, 0, 1)))
        assertFalse(IpPacketCodec.isIsolatedLocalAddress(byteArrayOf(1, 1, 1, 1)))
    }

    @Test
    fun `browser resolves urls and search queries`() {
        val holder = BrowserStateHolder()
        assertEquals("https://example.org", holder.resolveInputToUrl("example.org", SearchEngine.DUCKDUCKGO))
        assertEquals(
            "https://duckduckgo.com/?q=wifishare+vpn",
            holder.resolveInputToUrl("wifishare vpn", SearchEngine.DUCKDUCKGO)
        )
    }

    @Test
    fun `qr payload extraction parses direct invite uri and raw sharing code`() {
        assertEquals("WFS-8K9M-2P4X", QrCodeGenerator.extractSharingCode("WFS-8K9M-2P4X"))

        val inviteUri = QrCodeGenerator.buildInviteUri("WFS-8K9M-2P4X", "192.168.1.42", 51821, "PUBKEY==")
        val parsed = QrCodeGenerator.parseShareInvite(inviteUri)
        assertNotNull(parsed)
        assertEquals("WFS-8K9M-2P4X", parsed!!.code)
        assertEquals("192.168.1.42", parsed.hostIp)
        assertEquals(51821, parsed.hostPort)
        assertEquals("PUBKEY==", parsed.hostPublicKeyB64)
    }

    @Test
    fun `local password hasher verifies matching password and rejects wrong password`() {
        val hashed = LocalPasswordHasher.hashPassword("CorrectHorseBattery99!")
        assertTrue(LocalPasswordHasher.verifyPassword("CorrectHorseBattery99!", hashed.saltHex, hashed.hashHex))
        assertFalse(LocalPasswordHasher.verifyPassword("WrongPassword", hashed.saltHex, hashed.hashHex))
    }
}
