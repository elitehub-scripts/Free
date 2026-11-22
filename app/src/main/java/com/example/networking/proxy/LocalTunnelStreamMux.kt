package com.example.networking.proxy

import android.util.Log
import com.example.networking.crypto.DecryptedTunnelFrame
import com.example.networking.crypto.TunnelCrypto
import com.example.networking.crypto.TunnelSessionCipher
import com.example.networking.transport.TunnelPeerTransport
import com.example.networking.transport.TunnelWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * Client-side encrypted stream multiplexer and local loopback HTTP/CONNECT proxy.
 * Allows Mode A (Built-in Browser) and Mode B (Downloads) to route TCP/TLS traffic
 * through the Host's internet egress without requiring VpnService permission.
 */
class LocalTunnelStreamMux(
    private val cipher: TunnelSessionCipher,
    private val transport: TunnelPeerTransport,
    private val scope: CoroutineScope
) {
    private val nextStreamId = AtomicInteger(1)
    private val activeStreams = ConcurrentHashMap<Int, PipedOutputStream>()
    private var proxyServerSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var localProxyPort: Int = -1
        private set

    fun startLocalProxy(): Int {
        if (localProxyPort > 0) return localProxyPort
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        proxyServerSocket = server
        localProxyPort = server.localPort

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val client = server.accept()
                    launch { handleProxyClient(client) }
                }
            } catch (_: Exception) {
            }
        }
        return localProxyPort
    }

    fun onFrameReceived(frame: DecryptedTunnelFrame) {
        when (frame.type) {
            TunnelCrypto.FRAME_STREAM_DATA -> {
                val pipeOut = activeStreams[frame.streamId] ?: return
                runCatching {
                    pipeOut.write(frame.payload)
                    pipeOut.flush()
                }.onFailure { closeStream(frame.streamId) }
            }
            TunnelCrypto.FRAME_STREAM_CLOSE -> {
                closeStream(frame.streamId)
            }
        }
    }

    fun createTunnelOkHttpClient(): OkHttpClient {
        val port = startLocalProxy()
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun createTunnelSocketFactory(): SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = throw UnsupportedOperationException("Use connect(host, port)")
        override fun createSocket(host: String, port: Int): Socket = openTunneledSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            openTunneledSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int): Socket =
            openTunneledSocket(host.hostAddress ?: host.hostName, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            openTunneledSocket(address.hostAddress ?: address.hostName, port)
    }

    private fun openTunneledSocket(host: String, port: Int): Socket {
        val streamId = nextStreamId.getAndIncrement()
        val pipeIn = PipedInputStream(65536)
        val pipeOut = PipedOutputStream(pipeIn)
        activeStreams[streamId] = pipeOut

        val openPayload = TunnelWireCodec.encodeStreamOpen(host, port)
        val sealed = cipher.seal(TunnelCrypto.FRAME_STREAM_OPEN, streamId, openPayload)
        if (!transport.sendEncrypted(sealed)) {
            closeStream(streamId)
            throw IOException("Tunnel transport disconnected")
        }

        return object : Socket() {
            override fun getInputStream(): InputStream = pipeIn
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (len <= 0) return
                    val chunk = b.copyOfRange(off, off + len)
                    val frame = cipher.seal(TunnelCrypto.FRAME_STREAM_DATA, streamId, chunk)
                    if (!transport.sendEncrypted(frame)) {
                        throw IOException("Tunnel send failed")
                    }
                }
            }
            override fun close() {
                closeStream(streamId)
                transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_STREAM_CLOSE, streamId, ByteArray(0)))
                super.close()
            }
            override fun connect(endpoint: SocketAddress?) {}
            override fun connect(endpoint: SocketAddress?, timeout: Int) {}
        }
    }

    private suspend fun handleProxyClient(client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            val headerLines = mutableListOf<String>()
            val lineBuf = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b == -1) break
                lineBuf.append(b.toChar())
                if (lineBuf.endsWith("\r\n")) {
                    val line = lineBuf.substring(0, lineBuf.length - 2)
                    lineBuf.clear()
                    if (line.isEmpty()) break
                    headerLines.add(line)
                }
            }
            val requestLine = headerLines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            if (method.equals("CONNECT", ignoreCase = true)) {
                val hostPort = target.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                val tunneled = openTunneledSocket(host, port)
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
                relayBidirectional(client, inp, out, tunneled)
            } else {
                val uri = java.net.URI(target)
                val host = uri.host ?: return
                val port = if (uri.port > 0) uri.port else 80
                val tunneled = openTunneledSocket(host, port)
                val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val reconstructed = buildString {
                    append("$method $path HTTP/1.1\r\n")
                    headerLines.drop(1).forEach { line ->
                        if (!line.startsWith("Proxy-Connection", ignoreCase = true)) {
                            append(line).append("\r\n")
                        }
                    }
                    append("Connection: close\r\n\r\n")
                }
                tunneled.getOutputStream().write(reconstructed.toByteArray())
                tunneled.getOutputStream().flush()
                relayBidirectional(client, inp, out, tunneled)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Local proxy client closed: ${e.message}")
            runCatching { client.close() }
        }
    }

    private fun relayBidirectional(
        localSocket: Socket,
        localIn: InputStream,
        localOut: OutputStream,
        tunnelSocket: Socket
    ) {
        val upJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16384)
            try {
                val tOut = tunnelSocket.getOutputStream()
                while (isActive) {
                    val n = localIn.read(buf)
                    if (n <= 0) break
                    tOut.write(buf, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { tunnelSocket.close() }
                runCatching { localSocket.close() }
            }
        }
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16384)
            try {
                val tIn = tunnelSocket.getInputStream()
                while (isActive) {
                    val n = tIn.read(buf)
                    if (n <= 0) break
                    localOut.write(buf, 0, n)
                    localOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                upJob.cancel()
                runCatching { tunnelSocket.close() }
                runCatching { localSocket.close() }
            }
        }
    }

    private fun closeStream(streamId: Int) {
        activeStreams.remove(streamId)?.let { runCatching { it.close() } }
    }

    fun close() {
        acceptJob?.cancel()
        runCatching { proxyServerSocket?.close() }
        proxyServerSocket = null
        localProxyPort = -1
        activeStreams.keys.toList().forEach { closeStream(it) }
    }

    companion object {
        private const val TAG = "LocalTunnelStreamMux"
    }
}
