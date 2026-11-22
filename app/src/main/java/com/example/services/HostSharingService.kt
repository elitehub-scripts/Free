package com.example.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.PendingJoinRequest
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
    private const val REQUEST_CHANNEL_ID = "wifishare_requests_channel"
    private const val REQUEST_NOTIFICATION_BASE_ID = 4200

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    var activeCode: String = ""

    @Volatile
    var connectedCount: Int = 0

    @Volatile
    var pendingCount: Int = 0

    @Volatile
    var uploadBps: Long = 0L

    @Volatile
    var downloadBps: Long = 0L

    @Volatile
    var egressStatusLabel: String = "Online"

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    @Volatile
    var onApprovePeerRequested: ((Long) -> Unit)? = null

    @Volatile
    var onRejectPeerRequested: ((Long) -> Unit)? = null

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    @SuppressLint("MissingPermission")
    fun notifyIncomingRequest(context: Context, request: PendingJoinRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureRequestChannel(context, nm)

        val openIntent = PendingIntent.getActivity(
            context,
            (request.connectionId.toInt() * 10) + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_ARG_APPROVALS)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val approveIntent = PendingIntent.getService(
            context,
            (request.connectionId.toInt() * 10) + 2,
            Intent(context, HostSharingService::class.java).apply {
                action = HostSharingService.ACTION_APPROVE_PEER
                putExtra(HostSharingService.EXTRA_CONNECTION_ID, request.connectionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rejectIntent = PendingIntent.getService(
            context,
            (request.connectionId.toInt() * 10) + 3,
            Intent(context, HostSharingService::class.java).apply {
                action = HostSharingService.ACTION_REJECT_PEER
                putExtra(HostSharingService.EXTRA_CONNECTION_ID, request.connectionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (request.isOfflineRequest) {
            "Offline Internet Request: ${request.clientName}"
        } else {
            "Join Request: ${request.clientName}"
        }
        val noteSuffix = if (request.requestNote.isNotBlank()) " · \"${request.requestNote}\"" else ""
        val notification = NotificationCompat.Builder(context, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("${request.requestedMode.label} • ${request.clientEndpoint}$noteSuffix")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Accept", approveIntent)
            .addAction(0, "Reject", rejectIntent)
            .build()

        nm.notify(REQUEST_NOTIFICATION_BASE_ID + request.connectionId.toInt(), notification)
    }

    fun dismissRequestNotification(context: Context, connectionId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(REQUEST_NOTIFICATION_BASE_ID + connectionId.toInt())
    }

    private fun ensureRequestChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    REQUEST_CHANNEL_ID,
                    context.getString(R.string.notification_channel_requests),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }
}

class HostSharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                HostServiceBridge.onStopRequested?.invoke()
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_APPROVE_PEER -> {
                val connId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
                if (connId > 0L) {
                    HostServiceBridge.dismissRequestNotification(this, connId)
                    val handler = HostServiceBridge.onApprovePeerRequested
                    if (handler != null) {
                        handler.invoke(connId)
                    } else {
                        WiFiShareRuntime.getInstance(applicationContext).approvePeerById(connId, approve = true)
                    }
                }
                return START_STICKY
            }
            ACTION_REJECT_PEER -> {
                val connId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
                if (connId > 0L) {
                    HostServiceBridge.dismissRequestNotification(this, connId)
                    val handler = HostServiceBridge.onRejectPeerRequested
                    if (handler != null) {
                        handler.invoke(connId)
                    } else {
                        WiFiShareRuntime.getInstance(applicationContext).approvePeerById(connId, approve = false)
                    }
                }
                return START_STICKY
            }
        }

        // Ensure runtime is bound even after process restart
        WiFiShareRuntime.getInstance(applicationContext)

        ensureChannel()
        val initial = buildNotification("Initializing Host sharing gateway...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        acquireLocks()
        HostServiceBridge.setRunning(true)
        updateJob?.cancel()
        updateJob = scope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (isActive) {
                delay(1500)
                val code = HostServiceBridge.activeCode.ifBlank { "Active" }
                val peers = HostServiceBridge.connectedCount
                val pending = HostServiceBridge.pendingCount
                val up = formatSpeed(HostServiceBridge.uploadBps)
                val down = formatSpeed(HostServiceBridge.downloadBps)
                val egress = HostServiceBridge.egressStatusLabel
                val pendingSuffix = if (pending > 0) " · $pending waiting" else ""
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification("$code ($egress) · $peers client(s)$pendingSuffix · ↑$up ↓$down")
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
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiFiShare:HostGatewayWakeLock")?.apply {
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
                wifiLock = wm?.createWifiLock(mode, "WiFiShare:HostWifiLock")?.apply {
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
        HostServiceBridge.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        updateJob?.cancel()
        releaseLocks()
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
        val targetRoute = if (HostServiceBridge.pendingCount > 0) {
            MainActivity.ROUTE_ARG_APPROVALS
        } else {
            MainActivity.ROUTE_ARG_HOST
        }
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, targetRoute)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, HostSharingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WiFiShare Host Gateway (Background)")
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
        const val ACTION_APPROVE_PEER = "com.example.services.HOST_APPROVE_PEER"
        const val ACTION_REJECT_PEER = "com.example.services.HOST_REJECT_PEER"
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
    }
}
