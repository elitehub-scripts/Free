package com.example.networking.transport

import android.util.Log
import com.example.model.LiveTrafficSnapshot
import com.example.networking.crypto.DecryptedTunnelFrame
import com.example.networking.crypto.TunnelSessionCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks real TX/RX bytes, packet counters, replay drops, and 1-second speed samples.
 */
class TrafficAccounting {
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    private val txPackets = AtomicLong(0L)
    private val rxPackets = AtomicLong(0L)
    private val replayDrops = AtomicLong(0L)
    private val latencyMs = AtomicLong(0L)

    private var prevTxBytes = 0L
    private var prevRxBytes = 0L
    private val txHistory = ArrayDeque<Long>(24)
    private val rxHistory = ArrayDeque<Long>(24)

    private val _snapshot = MutableStateFlow(LiveTrafficSnapshot())
    val snapshot: StateFlow<LiveTrafficSnapshot> = _snapshot.asStateFlow()

    fun recordTx(bytes: Int) {
        txBytes.addAndGet(bytes.toLong())
        txPackets.incrementAndGet()
    }

    fun recordRx(bytes: Int) {
        rxBytes.addAndGet(bytes.toLong())
        rxPackets.incrementAndGet()
    }

    fun recordReplayDrop() {
        replayDrops.incrementAndGet()
    }

    fun updateLatency(rttMs: Long) {
        latencyMs.set(rttMs.coerceAtLeast(0L))
    }

    fun sampleSecond() {
        val curTx = txBytes.get()
        val curRx = rxBytes.get()
        val upSpeed = (curTx - prevTxBytes).coerceAtLeast(0L)
        val downSpeed = (curRx - prevRxBytes).coerceAtLeast(0L)
        prevTxBytes = curTx
        prevRxBytes = curRx

        if (txHistory.size >= 24) txHistory.removeFirst()
        if (rxHistory.size >= 24) rxHistory.removeFirst()
        txHistory.addLast(upSpeed)
        rxHistory.addLast(downSpeed)

        _snapshot.value = LiveTrafficSnapshot(
            uploadBps = upSpeed,
            downloadBps = downSpeed,
            totalUploadedBytes = curTx,
            totalDownloadedBytes = curRx,
            txPackets = txPackets.get(),
            rxPackets = rxPackets.get(),
            droppedReplayPackets = replayDrops.get(),
            tunnelLatencyMs = latencyMs.get(),
            speedHistoryUpload = txHistory.toList(),
            speedHistoryDownload = rxHistory.toList()
        )
    }

    fun reset() {
        txBytes.set(0L)
        rxBytes.set(0L)
        txPackets.set(0L)
        rxPackets.set(0L)
        replayDrops.set(0L)
        latencyMs.set(0L)
        prevTxBytes = 0L
        prevRxBytes = 0L
        txHistory.clear()
        rxHistory.clear()
        _snapshot.value = LiveTrafficSnapshot()
    }
}

interface TunnelPeerTransport {
    val transportLabel: String
    val isConnected: Boolean
    fun sendEncrypted(frameBytes: ByteArray): Boolean
    fun close()
}

/**
 * Direct P2P UDP tunnel transport using STUN-discovered endpoints & NAT hole-punching.
 */
class DirectUdpTransport(
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    private val cipher: TunnelSessionCipher,
    private val accounting: TrafficAccounting,
    private val scope: CoroutineScope,
    private val onFrameReceived: suspend (DecryptedTunnelFrame) -> Unit,
    private val onTransportError: (Throwable) -> Unit
) : TunnelPeerTransport {

    override val transportLabel: String = "Direct P2P UDP"
    @Volatile
    override var isConnected: Boolean = true
        private set

    private var readerJob: Job? = null

    fun start() {
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(65535)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                while (isActive && isConnected) {
                    socket.receive(pkt)
                    val raw = buf.copyOf(pkt.length)
                    val frame = cipher.open(raw)
                    if (frame == null) {
                        accounting.recordReplayDrop()
                        continue
                    }
                    accounting.recordRx(raw.size)
                    onFrameReceived(frame)
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Log.w(TAG, "Direct UDP reader ended: ${e.message}")
                    onTransportError(e)
                }
            }
        }
    }

    override fun sendEncrypted(frameBytes: ByteArray): Boolean {
        if (!isConnected) return false
        return try {
            socket.send(DatagramPacket(frameBytes, frameBytes.size, remoteAddress))
            accounting.recordTx(frameBytes.size)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct UDP send error", e)
            false
        }
    }

    override fun close() {
        isConnected = false
        readerJob?.cancel()
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "DirectUdpTransport"
    }
}

/**
 * Utility to pack/unpack STREAM_OPEN, STREAM_DATA, and PING payloads inside encrypted frames.
 */
object TunnelWireCodec {
    fun encodeStreamOpen(host: String, port: Int): ByteArray {
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(2 + hostBytes.size + 2)
            .putShort(hostBytes.size.toShort())
            .put(hostBytes)
            .putShort(port.toShort())
            .array()
    }

    fun decodeStreamOpen(payload: ByteArray): Pair<String, Int>? {
        if (payload.size < 4) return null
        val buf = ByteBuffer.wrap(payload)
        val hostLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < hostLen + 2) return null
        val hostBytes = ByteArray(hostLen)
        buf.get(hostBytes)
        val port = buf.short.toInt() and 0xFFFF
        return String(hostBytes, Charsets.UTF_8) to port
    }

    fun encodePingTimestamp(nowMs: Long = System.currentTimeMillis()): ByteArray =
        ByteBuffer.allocate(8).putLong(nowMs).array()

    fun decodePingTimestamp(payload: ByteArray): Long? =
        if (payload.size >= 8) ByteBuffer.wrap(payload).long else null
}
