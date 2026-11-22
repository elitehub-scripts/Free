package com.example.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
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

object ClientServiceBridge {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    var statusTitle: String = "Connecting to Host..."

    @Volatile
    var peerLabel: String = ""

    @Volatile
    var modeLabel: String = "Browser Mode"

    @Volatile
    var uploadBps: Long = 0L

    @Volatile
    var downloadBps: Long = 0L

    @Volatile
    var onDisconnectRequested: (() -> Unit)? = null

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}

/**
 * Foreground service keeping Client encrypted connections (handshakes, awaiting approval,
 * Mode A/B local SOCKS5/HTTP tunnel, background downloads, and auto-reconnect) alive in the background.
 */
class ClientTunnelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ClientServiceBridge.onDisconnectRequested?.invoke()
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        WiFiShareRuntime.getInstance(applicationContext)

        ensureChannel()
        val initial = buildNotification(
            title = "WiFiShare Client Tunnel",
            text = ClientServiceBridge.statusTitle
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        acquireLocks()
        ClientServiceBridge.setRunning(true)
        updateJob?.cancel()
        updateJob = scope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (isActive) {
                delay(1500)
                val peer = ClientServiceBridge.peerLabel.ifBlank { "Host" }
                val mode = ClientServiceBridge.modeLabel
                val status = ClientServiceBridge.statusTitle
                val up = formatSpeed(ClientServiceBridge.uploadBps)
                val down = formatSpeed(ClientServiceBridge.downloadBps)
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = "WiFiShare Client ($mode)",
                        text = "$status · $peer · ↑$up ↓$down"
                    )
                )
            }
        }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiFiShare:ClientTunnelWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "WiFiShare:ClientWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun stopSelfCleanly() {
        updateJob?.cancel()
        releaseLocks()
        ClientServiceBridge.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        updateJob?.cancel()
        releaseLocks()
        ClientServiceBridge.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_tunnel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_ARG_CLIENT)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            21,
            Intent(this, ClientTunnelService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wifishare_tunnel_channel"
        private const val NOTIFICATION_ID = 4103
        const val ACTION_START = "com.example.services.CLIENT_START"
        const val ACTION_STOP = "com.example.services.CLIENT_STOP"
    }
}
