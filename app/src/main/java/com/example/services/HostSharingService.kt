package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

object HostServiceBridge {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    var activeCode: String = ""

    @Volatile
    var connectedCount: Int = 0

    @Volatile
    var uploadBps: Long = 0L

    @Volatile
    var downloadBps: Long = 0L

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}

class HostSharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HostServiceBridge.onStopRequested?.invoke()
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        ensureChannel()
        val initial = buildNotification("Initializing Host sharing session...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        HostServiceBridge.setRunning(true)
        updateJob?.cancel()
        updateJob = scope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (isActive) {
                delay(1500)
                val code = HostServiceBridge.activeCode.ifBlank { "Active" }
                val peers = HostServiceBridge.connectedCount
                val up = formatSpeed(HostServiceBridge.uploadBps)
                val down = formatSpeed(HostServiceBridge.downloadBps)
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification("Code $code · $peers client(s) · ↑$up ↓$down")
                )
            }
        }
        return START_STICKY
    }

    private fun stopSelfCleanly() {
        updateJob?.cancel()
        HostServiceBridge.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        updateJob?.cancel()
        HostServiceBridge.setRunning(false)
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

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, HostSharingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WiFiShare Host Gateway")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Sharing", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wifishare_tunnel_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.example.services.HOST_START"
        const val ACTION_STOP = "com.example.services.HOST_STOP"
    }
}
