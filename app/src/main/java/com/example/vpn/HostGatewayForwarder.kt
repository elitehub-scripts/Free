package com.example.vpn

import android.net.Network
import android.util.Log
import com.example.networking.crypto.DecryptedTunnelFrame
import com.example.networking.crypto.TunnelCrypto
import com.example.networking.crypto.TunnelSessionCipher
import com.example.networking.transport.TrafficAccounting
import com.example.networking.transport.TunnelPeerTransport
import com.example.networking.transport.TunnelWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side tunnel termination, user-space L3/L4 NAT forwarder, DNS forwarder, and TCP stream proxy.
 * Each authorized client receives an isolated HostGatewayForwarder bound to its session cipher and transport.
 */
class HostGatewayForwarder(
    private val clientLabel: String,
    private val cipher: TunnelSessionCipher,
    private val transport: TunnelPeerTransport,
    private val accounting: TrafficAccounting,
    private val activeNetworkProvider: () -> Network?,
    private val scope: CoroutineScope,
    private val onClientDisconnected: () -> Unit
) {
    private data class NatKey(
        val protocol: Int,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    private class UdpNatEntry(
        val socket: DatagramSocket,
        val job: Job
    )

    private class TcpNatSession(
        val clientIp: ByteArray,
        val clientPort: Int,
        val remoteIp: ByteArray,
        val remotePort: Int,
        var hostSeq: Long,
        var clientNextSeq: Long,
        @Volatile var socket: Socket? = null,
        @Volatile var out: OutputStream? = null,
        var readerJob: Job? = null
    )

    private class StreamProxySession(
        val socket: Socket,
        val out: OutputStream,
        val readerJob: Job
    )

    private val udpNat = ConcurrentHashMap<NatKey, UdpNatEntry>()
    private val tcpNat = ConcurrentHashMap<NatKey, TcpNatSession>()
    private val streamProxies = ConcurrentHashMap<Int, StreamProxySession>()
    private val seqGenerator = AtomicLong(1000L)

    suspend fun handleIncomingFrame(frame: DecryptedTunnelFrame) {
        when (frame.type) {
            TunnelCrypto.FRAME_PING -> {
                val pong = cipher.seal(TunnelCrypto.FRAME_PONG, 0, frame.payload)
                transport.sendEncrypted(pong)
            }
            TunnelCrypto.FRAME_PONG -> {
                TunnelWireCodec.decodePingTimestamp(frame.payload)?.let { sent ->
                    accounting.updateLatency(System.currentTimeMillis() - sent)
                }
            }
            TunnelCrypto.FRAME_DISCONNECT -> {
                close()
                onClientDisconnected()
            }
            TunnelCrypto.FRAME_STREAM_OPEN -> {
                val target = TunnelWireCodec.decodeStreamOpen(frame.payload) ?: return
                openTcpStreamProxy(frame.streamId, target.first, target.second)
            }
            TunnelCrypto.FRAME_STREAM_DATA -> {
                val session = streamProxies[frame.streamId] ?: return
                runCatching {
                    session.out.write(frame.payload)
                    session.out.flush()
                }.onFailure {
                    closeStreamProxy(frame.streamId)
                }
            }
            TunnelCrypto.FRAME_STREAM_CLOSE -> {
                closeStreamProxy(frame.streamId)
            }
            TunnelCrypto.FRAME_IP_PACKET -> {
                forwardRawIpv4Packet(frame.payload)
            }
        }
    }

    private fun openTcpStreamProxy(streamId: Int, host: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                // Block SSRF / host LAN access to loopback or private tunnel subnet
                val resolved = InetAddress.getByName(host)
                val addrBytes = resolved.address
                if (resolved.isLoopbackAddress || (addrBytes.size == 4 && IpPacketCodec.isIsolatedLocalAddress(addrBytes))) {
                    sendStreamClose(streamId)
                    return@launch
                }

                val net = activeNetworkProvider()
                val sock = net?.socketFactory?.createSocket() ?: Socket()
                sock.connect(InetSocketAddress(resolved, port), 10000)
                sock.tcpNoDelay = true
                val inp: InputStream = sock.getInputStream()
                val out: OutputStream = sock.getOutputStream()

                val job = scope.launch(Dispatchers.IO) {
                    val buf = ByteArray(16384)
                    try {
                        while (isActive) {
                            val read = inp.read(buf)
                            if (read <= 0) break
                            val data = buf.copyOf(read)
                            val encrypted = cipher.seal(TunnelCrypto.FRAME_STREAM_DATA, streamId, data)
                            if (!transport.sendEncrypted(encrypted)) break
                        }
                    } catch (_: Exception) {
                    } finally {
                        closeStreamProxy(streamId)
                        sendStreamClose(streamId)
                    }
                }
                streamProxies[streamId] = StreamProxySession(sock, out, job)
            } catch (e: Exception) {
                Log.w(TAG, "Stream open failed for $host:$port (${e.message})")
                sendStreamClose(streamId)
            }
        }
    }

    private fun sendStreamClose(streamId: Int) {
        val frame = cipher.seal(TunnelCrypto.FRAME_STREAM_CLOSE, streamId, ByteArray(0))
        transport.sendEncrypted(frame)
    }

    private fun closeStreamProxy(streamId: Int) {
        streamProxies.remove(streamId)?.let { session ->
            session.readerJob.cancel()
            runCatching { session.socket.close() }
        }
    }

    private fun forwardRawIpv4Packet(rawPacket: ByteArray) {
        val ip = IpPacketCodec.parseIpv4(rawPacket) ?: return
        if (IpPacketCodec.isIsolatedLocalAddress(ip.dstIp)) return

        when (ip.protocol) {
            IpPacketCodec.PROTO_UDP -> handleUdpPacket(ip)
            IpPacketCodec.PROTO_TCP -> handleTcpPacket(ip)
        }
    }

    private fun handleUdpPacket(ip: IpPacketCodec.Ipv4Packet) {
        val udp = IpPacketCodec.parseUdp(ip.payload) ?: return
        // Forward DNS requests addressed to virtual DNS 10.88.0.1 to upstream 1.1.1.1
        val targetIp = if (ip.dstIpString == "10.88.0.1" && udp.dstPort == 53) {
            InetAddress.getByName("1.1.1.1")
        } else {
            InetAddress.getByAddress(ip.dstIp)
        }

        val key = NatKey(IpPacketCodec.PROTO_UDP, udp.srcPort, ip.dstIpString, udp.dstPort)
        val entry = udpNat.getOrPut(key) {
            val sock = DatagramSocket()
            activeNetworkProvider()?.bindSocket(sock)
            sock.soTimeout = 30000
            val readerJob = scope.launch(Dispatchers.IO) {
                val recvBuf = ByteArray(4096)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    while (isActive) {
                        sock.receive(recvPkt)
                        val respData = recvBuf.copyOf(recvPkt.length)
                        val returnIp = IpPacketCodec.buildIpv4UdpPacket(
                            srcIp = ip.dstIp,
                            dstIp = ip.srcIp,
                            srcPort = udp.dstPort,
                            dstPort = udp.srcPort,
                            data = respData
                        )
                        val sealed = cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, returnIp)
                        transport.sendEncrypted(sealed)
                    }
                } catch (_: Exception) {
                } finally {
                    udpNat.remove(key)
                    runCatching { sock.close() }
                }
            }
            UdpNatEntry(sock, readerJob)
        }

        scope.launch(Dispatchers.IO) {
            runCatching {
                entry.socket.send(DatagramPacket(udp.payload, udp.payload.size, targetIp, udp.dstPort))
            }
        }
    }

    private fun handleTcpPacket(ip: IpPacketCodec.Ipv4Packet) {
        val tcp = IpPacketCodec.parseTcp(ip.payload) ?: return
        val key = NatKey(IpPacketCodec.PROTO_TCP, tcp.srcPort, ip.dstIpString, tcp.dstPort)

        if ((tcp.flags and IpPacketCodec.TCP_RST) != 0) {
            closeTcpNat(key)
            return
        }

        if ((tcp.flags and IpPacketCodec.TCP_SYN) != 0 && (tcp.flags and IpPacketCodec.TCP_ACK) == 0) {
            closeTcpNat(key)
            val initialHostSeq = seqGenerator.addAndGet(50000L)
            val session = TcpNatSession(
                clientIp = ip.srcIp,
                clientPort = tcp.srcPort,
                remoteIp = ip.dstIp,
                remotePort = tcp.dstPort,
                hostSeq = initialHostSeq,
                clientNextSeq = (tcp.seqNum + 1L) and 0xFFFFFFFFL
            )
            tcpNat[key] = session

            scope.launch(Dispatchers.IO) {
                try {
                    val net = activeNetworkProvider()
                    val sock = net?.socketFactory?.createSocket() ?: Socket()
                    sock.connect(InetSocketAddress(InetAddress.getByAddress(ip.dstIp), tcp.dstPort), 10000)
                    sock.tcpNoDelay = true
                    session.socket = sock
                    session.out = sock.getOutputStream()

                    // Send SYN-ACK back to client TUN
                    val synAck = IpPacketCodec.buildIpv4TcpPacket(
                        srcIp = session.remoteIp,
                        dstIp = session.clientIp,
                        srcPort = session.remotePort,
                        dstPort = session.clientPort,
                        seqNum = session.hostSeq,
                        ackNum = session.clientNextSeq,
                        flags = IpPacketCodec.TCP_SYN or IpPacketCodec.TCP_ACK
                    )
                    session.hostSeq = (session.hostSeq + 1L) and 0xFFFFFFFFL
                    transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, synAck))

                    val inp = sock.getInputStream()
                    val readBuf = ByteArray(1320)
                    session.readerJob = scope.launch(Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val n = inp.read(readBuf)
                                if (n <= 0) break
                                val chunk = readBuf.copyOf(n)
                                val dataPkt = IpPacketCodec.buildIpv4TcpPacket(
                                    srcIp = session.remoteIp,
                                    dstIp = session.clientIp,
                                    srcPort = session.remotePort,
                                    dstPort = session.clientPort,
                                    seqNum = session.hostSeq,
                                    ackNum = session.clientNextSeq,
                                    flags = IpPacketCodec.TCP_PSH or IpPacketCodec.TCP_ACK,
                                    data = chunk
                                )
                                session.hostSeq = (session.hostSeq + n.toLong()) and 0xFFFFFFFFL
                                transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, dataPkt))
                            }
                            val finPkt = IpPacketCodec.buildIpv4TcpPacket(
                                srcIp = session.remoteIp,
                                dstIp = session.clientIp,
                                srcPort = session.remotePort,
                                dstPort = session.clientPort,
                                seqNum = session.hostSeq,
                                ackNum = session.clientNextSeq,
                                flags = IpPacketCodec.TCP_FIN or IpPacketCodec.TCP_ACK
                            )
                            transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, finPkt))
                        } catch (_: Exception) {
                        } finally {
                            closeTcpNat(key)
                        }
                    }
                } catch (e: Exception) {
                    val rstPkt = IpPacketCodec.buildIpv4TcpPacket(
                        srcIp = session.remoteIp,
                        dstIp = session.clientIp,
                        srcPort = session.remotePort,
                        dstPort = session.clientPort,
                        seqNum = session.hostSeq,
                        ackNum = session.clientNextSeq,
                        flags = IpPacketCodec.TCP_RST or IpPacketCodec.TCP_ACK
                    )
                    transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, rstPkt))
                    closeTcpNat(key)
                }
            }
            return
        }

        val session = tcpNat[key] ?: return
        if (tcp.payload.isNotEmpty()) {
            val out = session.out ?: return
            scope.launch(Dispatchers.IO) {
                runCatching {
                    out.write(tcp.payload)
                    out.flush()
                    session.clientNextSeq = (tcp.seqNum + tcp.payload.size.toLong()) and 0xFFFFFFFFL
                    val ackPkt = IpPacketCodec.buildIpv4TcpPacket(
                        srcIp = session.remoteIp,
                        dstIp = session.clientIp,
                        srcPort = session.remotePort,
                        dstPort = session.clientPort,
                        seqNum = session.hostSeq,
                        ackNum = session.clientNextSeq,
                        flags = IpPacketCodec.TCP_ACK
                    )
                    transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, ackPkt))
                }.onFailure { closeTcpNat(key) }
            }
        }

        if ((tcp.flags and IpPacketCodec.TCP_FIN) != 0) {
            session.clientNextSeq = (tcp.seqNum + tcp.payload.size.toLong() + 1L) and 0xFFFFFFFFL
            val finAck = IpPacketCodec.buildIpv4TcpPacket(
                srcIp = session.remoteIp,
                dstIp = session.clientIp,
                srcPort = session.remotePort,
                dstPort = session.clientPort,
                seqNum = session.hostSeq,
                ackNum = session.clientNextSeq,
                flags = IpPacketCodec.TCP_FIN or IpPacketCodec.TCP_ACK
            )
            transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, finAck))
            closeTcpNat(key)
        }
    }

    private fun closeTcpNat(key: NatKey) {
        tcpNat.remove(key)?.let { s ->
            s.readerJob?.cancel()
            runCatching { s.socket?.close() }
        }
    }

    fun close() {
        udpNat.keys.toList().forEach { key ->
            udpNat.remove(key)?.let {
                it.job.cancel()
                runCatching { it.socket.close() }
            }
        }
        tcpNat.keys.toList().forEach { closeTcpNat(it) }
        streamProxies.keys.toList().forEach { closeStreamProxy(it) }
        transport.close()
    }

    companion object {
        private const val TAG = "HostGatewayForwarder"
    }
}
