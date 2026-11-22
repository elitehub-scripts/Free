package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.networking.crypto.DecryptedTunnelFrame
import com.example.networking.crypto.TunnelCrypto
import com.example.networking.crypto.TunnelSessionCipher
import com.example.networking.transport.TrafficAccounting
import com.example.networking.transport.TunnelPeerTransport
import com.example.ui.components.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Active VPN session runtime bridge shared between the ViewModel/Controller and WiFiShareVpnService.
 */
object VpnRuntimeBridge {
    @Volatile
    var activeCipher: TunnelSessionCipher? = null

    @Volatile
    var activeTransport: TunnelPeerTransport? = null

    @Volatile
    var activeAccounting: TrafficAccounting? = null

    @Volatile
    var assignedIp: String = "10.88.0.2"

    @Volatile
    var mtu: Int = 1400

    @Volatile
    var ipv6Enabled: Boolean = true

    @Volatile
    var dnsLeakProtection: Boolean = true

    @Volatile
    var onVpnStoppedExternally: (() -> Unit)? = null

    private val _isVpnRunning = MutableStateFlow(false)
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

    @Volatile
    private var tunOutputStream: FileOutputStream? = null

    internal fun attachTunOutput(out: FileOutputStream?) {
        tunOutputStream = out
        _isVpnRunning.value = out != null
    }

    fun deliverDecryptedIpPacket(frame: DecryptedTunnelFrame) {
        if (frame.type != TunnelCrypto.FRAME_IP_PACKET) return
        val out = tunOutputStream ?: return
        runCatching {
            synchronized(out) {
                out.write(frame.payload)
            }
        }.onFailure {
            Log.w("VpnRuntimeBridge", "Failed writing return packet to TUN: ${it.message}")
        }
    }
}

class WiFiShareVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var packetReaderJob: Job? = null
    private var notifierJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpnSession()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = buildNotification("Connecting encrypted tunnel...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startVpnSession()
        return START_STICKY
    }

    private fun startVpnSession() {
        val cipher = VpnRuntimeBridge.activeCipher
        val transport = VpnRuntimeBridge.activeTransport
        if (cipher == null || transport == null || !transport.isConnected) {
            Log.e(TAG, "Cannot start VPN without active encrypted peer transport")
            stopVpnSession()
            return
        }

        runCatching { tunInterface?.close() }
        val builder = Builder()
            .setSession("WiFiShare Encrypted Tunnel")
            .setMtu(VpnRuntimeBridge.mtu)
            .addAddress(VpnRuntimeBridge.assignedIp, 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.88.0.1")

        if (VpnRuntimeBridge.ipv6Enabled) {
            runCatching {
                builder.addAddress("fd88:5746::2", 64)
                builder.addRoute("::", 0)
            }
        }
        // Exclude WiFiShare itself so underlying encrypted tunnel sockets never loop back into TUN
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null")
            stopVpnSession()
            return
        }
        tunInterface = pfd

        val tunInput = FileInputStream(pfd.fileDescriptor)
        val tunOutput = FileOutputStream(pfd.fileDescriptor)
        VpnRuntimeBridge.attachTunOutput(tunOutput)

        packetReaderJob?.cancel()
        packetReaderJob = serviceScope.launch {
            val buffer = ByteArray(32767)
            try {
                while (isActive && transport.isConnected) {
                    val length = tunInput.read(buffer)
                    if (length > 0) {
                        val packet = buffer.copyOf(length)
                        val sealed = cipher.seal(TunnelCrypto.FRAME_IP_PACKET, 0, packet)
                        if (!transport.sendEncrypted(sealed)) {
                            break
                        }
                    } else if (length < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "VPN TUN reader loop ended: ${e.message}")
            } finally {
                stopVpnSession()
            }
        }

        notifierJob?.cancel()
        notifierJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (isActive) {
                delay(1500)
                val snap = VpnRuntimeBridge.activeAccounting?.snapshot?.value
                val text = if (snap != null) {
                    "Up ${formatSpeed(snap.uploadBps)} · Down ${formatSpeed(snap.downloadBps)} · ${VpnRuntimeBridge.assignedIp}"
                } else {
                    "Encrypted tunnel active (${VpnRuntimeBridge.assignedIp})"
                }
                nm.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun stopVpnSession() {
        packetReaderJob?.cancel()
        notifierJob?.cancel()
        VpnRuntimeBridge.attachTunOutput(null)
        runCatching { tunInterface?.close() }
        tunInterface = null
        VpnRuntimeBridge.onVpnStoppedExternally?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpnSession()
        super.onRevoke()
    }

    override fun onDestroy() {
        packetReaderJob?.cancel()
        notifierJob?.cancel()
        VpnRuntimeBridge.attachTunOutput(null)
        runCatching { tunInterface?.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_tunnel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WiFiShareVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WiFiShare Whole-Device VPN")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "WiFiShareVpnService"
        private const val CHANNEL_ID = "wifishare_tunnel_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.vpn.ACTION_START"
        const val ACTION_STOP = "com.example.vpn.ACTION_STOP"
    }
}
