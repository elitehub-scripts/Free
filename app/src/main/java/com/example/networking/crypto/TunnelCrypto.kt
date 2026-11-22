package com.example.networking.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end tunnel cryptography using ECDH (secp256r1) + HKDF-SHA256 + AES-256-GCM
 * with monotonic sequence numbers and a 64-bit sliding anti-replay window.
 */
object TunnelCrypto {

    const val FRAME_IP_PACKET: Byte = 0x01
    const val FRAME_STREAM_OPEN: Byte = 0x02
    const val FRAME_STREAM_DATA: Byte = 0x03
    const val FRAME_STREAM_CLOSE: Byte = 0x04
    const val FRAME_PING: Byte = 0x05
    const val FRAME_PONG: Byte = 0x06
    const val FRAME_DISCONNECT: Byte = 0x07

    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): String = base64Encode(publicKey.encoded)

    fun decodePublicKey(base64: String): PublicKey {
        val bytes = base64Decode(base64)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun base64Encode(data: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val hasB1 = i < data.size
            val b1 = if (hasB1) data[i++].toInt() and 0xFF else 0
            val hasB2 = i < data.size
            val b2 = if (hasB2) data[i++].toInt() and 0xFF else 0
            out.append(table[b0 ushr 2])
            out.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (hasB1) table[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            out.append(if (hasB2) table[b2 and 0x3F] else '=')
        }
        return out.toString()
    }

    private fun base64Decode(input: String): ByteArray {
        val clean = input.trim()
        if (clean.isEmpty()) return ByteArray(0)
        val rev = IntArray(128) { -1 }
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        table.forEachIndexed { idx, ch -> rev[ch.code] = idx }
        val padding = clean.takeLastWhile { it == '=' }.length
        val out = ByteArray((clean.length * 3) / 4 - padding)
        var outIdx = 0
        var i = 0
        while (i < clean.length) {
            val c0 = rev[clean[i++].code]
            val c1 = rev[clean[i++].code]
            val ch2 = clean[i++]
            val ch3 = clean[i++]
            val c2 = if (ch2 == '=') 0 else rev[ch2.code]
            val c3 = if (ch3 == '=') 0 else rev[ch3.code]
            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIdx < out.size) out[outIdx++] = ((triple ushr 16) and 0xFF).toByte()
            if (ch2 != '=' && outIdx < out.size) out[outIdx++] = ((triple ushr 8) and 0xFF).toByte()
            if (ch3 != '=' && outIdx < out.size) out[outIdx++] = (triple and 0xFF).toByte()
        }
        return out
    }

    fun deriveSessionCipher(
        localKeyPair: KeyPair,
        remotePublicKeyB64: String,
        sessionSalt: ByteArray,
        isHost: Boolean
    ): TunnelSessionCipher {
        val remotePub = decodePublicKey(remotePublicKeyB64)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair.private)
        agreement.doPhase(remotePub, true)
        val sharedSecret = agreement.generateSecret()

        val prk = hkdfExtract(sessionSalt, sharedSecret)
        val hostToClientKey = hkdfExpand(prk, "wifishare-h2c-key".toByteArray(), 32)
        val clientToHostKey = hkdfExpand(prk, "wifishare-c2h-key".toByteArray(), 32)
        val hostToClientIvSalt = hkdfExpand(prk, "wifishare-h2c-salt".toByteArray(), 4)
        val clientToHostIvSalt = hkdfExpand(prk, "wifishare-c2h-salt".toByteArray(), 4)

        val txKeyBytes = if (isHost) hostToClientKey else clientToHostKey
        val rxKeyBytes = if (isHost) clientToHostKey else hostToClientKey
        val txSalt = if (isHost) hostToClientIvSalt else clientToHostIvSalt
        val rxSalt = if (isHost) clientToHostIvSalt else hostToClientIvSalt

        return TunnelSessionCipher(
            txKey = SecretKeySpec(txKeyBytes, "AES"),
            rxKey = SecretKeySpec(rxKeyBytes, "AES"),
            txIvSalt = txSalt,
            rxIvSalt = rxSalt
        )
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }
}

class ReplayWindow {
    private var highestSeq: Long = -1L
    private var bitmap: Long = 0L

    @Synchronized
    fun checkAndAccept(seq: Long): Boolean {
        if (seq < 0L) return false
        if (seq > highestSeq) {
            val shift = seq - highestSeq
            bitmap = if (shift >= 64L) 1L else (bitmap shl shift.toInt()) or 1L
            highestSeq = seq
            return true
        }
        val diff = highestSeq - seq
        if (diff >= 64L) return false
        val mask = 1L shl diff.toInt()
        if ((bitmap and mask) != 0L) return false
        bitmap = bitmap or mask
        return true
    }
}

data class DecryptedTunnelFrame(
    val type: Byte,
    val streamId: Int,
    val sequence: Long,
    val payload: ByteArray
)

class TunnelSessionCipher(
    private val txKey: SecretKey,
    private val rxKey: SecretKey,
    private val txIvSalt: ByteArray,
    private val rxIvSalt: ByteArray
) {
    private val txCounter = AtomicLong(0L)
    private val replayWindow = ReplayWindow()

    /**
     * Wire format:
     * [1B frameType][4B streamId][8B sequence][encrypted (payload) + 16B GCM tag]
     * AAD binds frameType + streamId + sequence to prevent tampering.
     */
    fun seal(frameType: Byte, streamId: Int, payload: ByteArray): ByteArray {
        val seq = txCounter.getAndIncrement()
        val iv = buildIv(txIvSalt, seq)
        val aad = buildAad(frameType, streamId, seq)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, txKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(payload)

        return ByteBuffer.allocate(1 + 4 + 8 + encrypted.size)
            .put(frameType)
            .putInt(streamId)
            .putLong(seq)
            .put(encrypted)
            .array()
    }

    fun open(packet: ByteArray): DecryptedTunnelFrame? {
        if (packet.size < 1 + 4 + 8 + 16) return null
        val buf = ByteBuffer.wrap(packet)
        val frameType = buf.get()
        val streamId = buf.int
        val seq = buf.long
        val ciphertext = ByteArray(buf.remaining())
        buf.get(ciphertext)

        if (!replayWindow.checkAndAccept(seq)) {
            return null
        }

        val iv = buildIv(rxIvSalt, seq)
        val aad = buildAad(frameType, streamId, seq)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, rxKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ciphertext)
            DecryptedTunnelFrame(frameType, streamId, seq, plain)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildIv(salt: ByteArray, seq: Long): ByteArray =
        ByteBuffer.allocate(12).put(salt, 0, 4).putLong(seq).array()

    private fun buildAad(frameType: Byte, streamId: Int, seq: Long): ByteArray =
        ByteBuffer.allocate(13).put(frameType).putInt(streamId).putLong(seq).array()
}
