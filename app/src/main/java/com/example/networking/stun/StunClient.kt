package com.example.networking.stun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

data class StunDiscoveryResult(
    val publicIp: String,
    val publicPort: Int,
    val localPort: Int,
    val rttMs: Long
)

/**
 * Genuine RFC 5389 STUN Binding Request client to discover NAT-mapped reflexive endpoints.
 */
object StunClient {
    private const val STUN_BINDING_REQUEST: Short = 0x0001
    private const val STUN_BINDING_RESPONSE: Short = 0x0101
    private const val MAGIC_COOKIE: Int = 0x2112A442
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020

    suspend fun discoverPublicEndpoint(
        stunHost: String,
        stunPort: Int,
        existingSocket: DatagramSocket? = null,
        timeoutMs: Int = 3000
    ): Result<StunDiscoveryResult> = withContext(Dispatchers.IO) {
        val ownsSocket = existingSocket == null
        val socket = existingSocket ?: DatagramSocket()
        try {
            socket.soTimeout = timeoutMs
            val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val request = ByteBuffer.allocate(20)
                .putShort(STUN_BINDING_REQUEST)
                .putShort(0)
                .putInt(MAGIC_COOKIE)
                .put(txId)
                .array()

            val address = InetAddress.getByName(stunHost)
            val startNs = System.nanoTime()
            socket.send(DatagramPacket(request, request.size, address, stunPort))

            val recvBuf = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)
            val rttMs = (System.nanoTime() - startNs) / 1_000_000L

            val parsed = parseBindingResponse(recvBuf, recvPacket.length, txId)
                ?: return@withContext Result.failure(IllegalStateException("Invalid STUN response"))

            Result.success(
                StunDiscoveryResult(
                    publicIp = parsed.first,
                    publicPort = parsed.second,
                    localPort = socket.localPort,
                    rttMs = rttMs
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (ownsSocket) {
                runCatching { socket.close() }
            }
        }
    }

    internal fun parseBindingResponse(
        data: ByteArray,
        length: Int,
        expectedTxId: ByteArray
    ): Pair<String, Int>? {
        if (length < 20) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        val msgType = buf.short
        val msgLen = buf.short.toInt() and 0xFFFF
        val cookie = buf.int
        val rxTxId = ByteArray(12)
        buf.get(rxTxId)

        if (msgType != STUN_BINDING_RESPONSE || cookie != MAGIC_COOKIE) return null
        if (!rxTxId.contentEquals(expectedTxId)) return null
        if (20 + msgLen > length) return null

        var mappedFallback: Pair<String, Int>? = null
        val endOffset = 20 + msgLen
        while (buf.position() + 4 <= endOffset) {
            val attrType = buf.short.toInt() and 0xFFFF
            val attrLen = buf.short.toInt() and 0xFFFF
            if (buf.position() + attrLen > endOffset) break

            val attrBytes = ByteArray(attrLen)
            buf.get(attrBytes)
            val paddedSkip = (4 - (attrLen % 4)) % 4
            if (buf.position() + paddedSkip <= endOffset) {
                buf.position(buf.position() + paddedSkip)
            }

            if (attrType == ATTR_XOR_MAPPED_ADDRESS && attrBytes.size >= 8) {
                val family = attrBytes[1].toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    val xPort = ((attrBytes[2].toInt() and 0xFF) shl 8) or (attrBytes[3].toInt() and 0xFF)
                    val port = xPort xor ((MAGIC_COOKIE ushr 16) and 0xFFFF)
                    val cookieBytes = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
                    val ipBytes = ByteArray(4) { i -> (attrBytes[4 + i].toInt() xor cookieBytes[i].toInt()).toByte() }
                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: continue
                    return ip to port
                }
            } else if (attrType == ATTR_MAPPED_ADDRESS && attrBytes.size >= 8) {
                val family = attrBytes[1].toInt() and 0xFF
                if (family == 0x01) {
                    val port = ((attrBytes[2].toInt() and 0xFF) shl 8) or (attrBytes[3].toInt() and 0xFF)
                    val ipBytes = attrBytes.copyOfRange(4, 8)
                    val ip = InetAddress.getByAddress(ipBytes).hostAddress
                    if (ip != null) mappedFallback = ip to port
                }
            }
        }
        return mappedFallback
    }
}
