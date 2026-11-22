package com.example.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

object IpPacketCodec {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class Ipv4Packet(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val payload: ByteArray
    ) {
        val srcIpString: String get() = InetAddress.getByAddress(srcIp).hostAddress ?: ""
        val dstIpString: String get() = InetAddress.getByAddress(dstIp).hostAddress ?: ""
    }

    data class UdpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    data class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val ackNum: Long,
        val flags: Int,
        val window: Int,
        val payload: ByteArray
    )

    fun parseIpv4(raw: ByteArray, length: Int = raw.size): Ipv4Packet? {
        if (length < 20) return null
        val version = (raw[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (raw[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        val totalLen = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val effectiveLen = minOf(totalLen, length)
        if (effectiveLen < ihl) return null

        val protocol = raw[9].toInt() and 0xFF
        val srcIp = raw.copyOfRange(12, 16)
        val dstIp = raw.copyOfRange(16, 20)
        val payload = raw.copyOfRange(ihl, effectiveLen)
        return Ipv4Packet(version, ihl, effectiveLen, protocol, srcIp, dstIp, payload)
    }

    fun parseUdp(payload: ByteArray): UdpSegment? {
        if (payload.size < 8) return null
        val srcPort = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val dstPort = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        val udpLen = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        val end = minOf(udpLen, payload.size)
        if (end < 8) return null
        return UdpSegment(srcPort, dstPort, payload.copyOfRange(8, end))
    }

    fun parseTcp(payload: ByteArray): TcpSegment? {
        if (payload.size < 20) return null
        val buf = ByteBuffer.wrap(payload)
        val srcPort = buf.short.toInt() and 0xFFFF
        val dstPort = buf.short.toInt() and 0xFFFF
        val seq = buf.int.toLong() and 0xFFFFFFFFL
        val ack = buf.int.toLong() and 0xFFFFFFFFL
        val dataOffsetAndFlags = buf.short.toInt() and 0xFFFF
        val dataOffset = ((dataOffsetAndFlags ushr 12) and 0x0F) * 4
        if (dataOffset < 20 || dataOffset > payload.size) return null
        val flags = dataOffsetAndFlags and 0x3F
        val window = buf.short.toInt() and 0xFFFF
        val appPayload = payload.copyOfRange(dataOffset, payload.size)
        return TcpSegment(srcPort, dstPort, seq, ack, flags, window, appPayload)
    }

    fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        data: ByteArray
    ): ByteArray {
        val udpLen = 8 + data.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        writeIpv4Header(out, totalLen, PROTO_UDP, srcIp, dstIp)

        out[20] = (srcPort ushr 8).toByte()
        out[21] = srcPort.toByte()
        out[22] = (dstPort ushr 8).toByte()
        out[23] = dstPort.toByte()
        out[24] = (udpLen ushr 8).toByte()
        out[25] = udpLen.toByte()
        out[26] = 0
        out[27] = 0
        System.arraycopy(data, 0, out, 28, data.size)

        val udpChecksum = computeTransportChecksum(srcIp, dstIp, PROTO_UDP, out, 20, udpLen)
        out[26] = (udpChecksum ushr 8).toByte()
        out[27] = udpChecksum.toByte()
        return out
    }

    fun buildIpv4TcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        window: Int = 65535,
        data: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpLen = 20 + data.size
        val totalLen = 20 + tcpLen
        val out = ByteArray(totalLen)
        writeIpv4Header(out, totalLen, PROTO_TCP, srcIp, dstIp)

        val buf = ByteBuffer.wrap(out, 20, tcpLen)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt((seqNum and 0xFFFFFFFFL).toInt())
        buf.putInt((ackNum and 0xFFFFFFFFL).toInt())
        buf.putShort(((5 shl 12) or (flags and 0x3F)).toShort())
        buf.putShort(window.toShort())
        buf.putShort(0) // checksum placeholder
        buf.putShort(0) // urgent pointer
        if (data.isNotEmpty()) {
            buf.put(data)
        }

        val tcpChecksum = computeTransportChecksum(srcIp, dstIp, PROTO_TCP, out, 20, tcpLen)
        out[36] = (tcpChecksum ushr 8).toByte()
        out[37] = tcpChecksum.toByte()
        return out
    }

    private fun writeIpv4Header(
        out: ByteArray,
        totalLen: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        out[0] = 0x45.toByte() // IPv4, IHL=5 (20 bytes)
        out[1] = 0x00
        out[2] = (totalLen ushr 8).toByte()
        out[3] = totalLen.toByte()
        out[4] = 0
        out[5] = 1
        out[6] = 0x40.toByte() // Don't Fragment
        out[7] = 0
        out[8] = 64 // TTL
        out[9] = protocol.toByte()
        out[10] = 0
        out[11] = 0
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)

        val headerChecksum = checksum(out, 0, 20)
        out[10] = (headerChecksum ushr 8).toByte()
        out[11] = headerChecksum.toByte()
    }

    private fun computeTransportChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        buf: ByteArray,
        offset: Int,
        len: Int
    ): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol.toLong()
        sum += len.toLong()

        sum += sumBytes(buf, offset, len)
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFFL).toInt()
        return if (protocol == PROTO_UDP && result == 0) 0xFFFF else result
    }

    private fun checksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = sumBytes(buf, offset, len)
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun sumBytes(buf: ByteArray, offset: Int, len: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        return sum
    }

    fun isIsolatedLocalAddress(ip: ByteArray): Boolean {
        if (ip.size != 4) return false
        val b0 = ip[0].toInt() and 0xFF
        val b1 = ip[1].toInt() and 0xFF
        // Block loopback 127.0.0.0/8, link-local 169.254.0.0/16, and peer tunnel subnet 10.88.0.0/24
        return b0 == 127 ||
            (b0 == 169 && b1 == 254) ||
            (b0 == 10 && b1 == 88 && (ip[2].toInt() and 0xFF) == 0 && (ip[3].toInt() and 0xFF) != 1)
    }
}
