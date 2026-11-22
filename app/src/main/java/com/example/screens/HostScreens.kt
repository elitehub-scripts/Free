package com.example.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.database.SavedPeerEntity
import com.example.model.ActiveNetworkInfo
import com.example.model.ConnectedPeer
import com.example.model.LiveTrafficSnapshot
import com.example.model.NearbyOfflineNeed
import com.example.model.OfflineP2pState
import com.example.model.PendingJoinRequest
import com.example.model.SharingCodeState
import com.example.model.TunnelState
import com.example.networking.transport.OfflineWifiDirectManager
import com.example.qr.QrCodeGenerator
import com.example.settings.WiFiShareSettings
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.components.TrustNoticeBanner
import com.example.ui.components.WiFiShareTopBar
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatSpeed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalWiFiShareColors
import kotlinx.coroutines.delay

@Composable
fun HostModeScreen(
    contentPadding: PaddingValues,
    networkInfo: ActiveNetworkInfo,
    tunnelState: TunnelState,
    sharingCode: SharingCodeState,
    traffic: LiveTrafficSnapshot,
    settings: WiFiShareSettings,
    connectedPeers: List<ConnectedPeer>,
    pendingRequests: List<PendingJoinRequest>,
    nearbyOfflineNeeds: List<NearbyOfflineNeed>,
    isHostServiceRunning: Boolean,
    offlineP2pState: OfflineP2pState,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    onStartOfflineWifiDirectGroup: () -> Unit,
    onStopOfflineWifiDirect: () -> Unit,
    onUpdatePermittedModes: (Boolean, Boolean, Boolean) -> Unit,
    onUpdateMaxDevices: (Int) -> Unit,
    onUpdateAutoAcceptTrusted: (Boolean) -> Unit,
    onUpdateAllowOpenOfflineRequests: (Boolean) -> Unit,
    onUpdateAutoAcceptOpenRequests: (Boolean) -> Unit,
    onApproveRequest: (PendingJoinRequest, Boolean) -> Unit,
    onRejectRequest: (PendingJoinRequest) -> Unit,
    onOpenSharingCode: () -> Unit,
    onOpenApprovals: () -> Unit,
    onOpenConnectedDevices: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val semantic = LocalWiFiShareColors.current
    val isHostActive = (tunnelState as? TunnelState.Connected)?.role == "HOST"
    val isStartingHost = tunnelState is TunnelState.Handshaking
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permissionHint by remember { mutableStateOf<String?>(null) }

    val p2pPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionHint = null
            onStartOfflineWifiDirectGroup()
        } else {
            permissionHint = "Nearby Wi-Fi permission denied. You can still share over Wi-Fi or Portable Hotspot."
        }
    }

    LaunchedEffect(isHostActive) {
        while (isHostActive) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val durationSec = if (isHostActive) {
        (((tunnelState as TunnelState.Connected).startedAtMs).let { nowMs - it } / 1000L).coerceAtLeast(0L)
    } else 0L

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Host Internet Gateway", onBack = onBack) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .padding(bottom = contentPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TrustNoticeBanner(
                    text = stringResource(R.string.host_trust_warning),
                    icon = Icons.Default.Security,
                    tint = semantic.warning
                )

                // Egress Network & Main Toggle Card
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Host Gateway & Background Mode", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = when {
                                        networkInfo.isConnected -> "${networkInfo.transport.name} • ${networkInfo.interfaceName} (${networkInfo.localIp})"
                                        networkInfo.hasLocalLink -> "Offline Local Link • ${networkInfo.interfaceName} (${networkInfo.localIp})"
                                        else -> "Offline Standby • Accepts local Hotspot / Wi-Fi Direct connections"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(
                                label = when {
                                    isHostActive && isHostServiceRunning -> "BG SHARING ON"
                                    isHostActive -> "SHARING ON"
                                    isStartingHost -> "STARTING..."
                                    tunnelState is TunnelState.Error -> "ERROR"
                                    else -> "STOPPED"
                                },
                                color = when {
                                    isHostActive -> semantic.success
                                    isStartingHost -> semantic.cyanAccent
                                    tunnelState is TunnelState.Error -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        if (tunnelState is TunnelState.Handshaking) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = tunnelState.stage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.cyanAccent
                                )
                            }
                        } else if (tunnelState is TunnelState.Error) {
                            Text(
                                text = "${tunnelState.message} ${tunnelState.recoveryHint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (isHostActive) {
                            Text(
                                text = "Code: ${sharingCode.code}  •  Duration: ${formatDuration(durationSec)}",
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = semantic.cyanAccent
                            )
                            Text(
                                text = "Up ${formatSpeed(traffic.uploadBps)} (${formatBytes(traffic.totalUploadedBytes)})  •  Down ${formatSpeed(traffic.downloadBps)} (${formatBytes(traffic.totalDownloadedBytes)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (settings.autoAcceptOpenRequests) {
                                    "Public Wi-Fi Share ON: Anyone nearby can connect & browse automatically."
                                } else {
                                    "Runs continuously in the background with notification Accept / Reject controls."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = semantic.success
                            )
                        }

                        Button(
                            onClick = if (isHostActive || isStartingHost) onStopSharing else onStartSharing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("host_start_stop_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHostActive || isStartingHost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (isHostActive || isStartingHost) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = if (isHostActive || isStartingHost) Icons.Default.StopCircle else Icons.Default.WifiTethering,
                                contentDescription = null
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    isHostActive -> "Stop Sharing Immediately"
                                    isStartingHost -> "Cancel Starting"
                                    networkInfo.isConnected -> "Start Background Host Sharing"
                                    else -> "Start Offline Host & Accept Mode"
                                }
                            )
                        }

                        ModeSwitchRow(
                            title = "Open Wi-Fi Share (Auto-Accept Anyone Nearby)",
                            checked = settings.autoAcceptOpenRequests,
                            onCheckedChange = onUpdateAutoAcceptOpenRequests,
                            switchTestTag = "host_auto_accept_open_switch"
                        )
                    }
                }

                // Inline Pending Connection Requests (1-tap Accept / Reject right on Host dashboard)
                if (pendingRequests.isNotEmpty()) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = semantic.warning.copy(alpha = 0.65f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Incoming Connection Requests", style = MaterialTheme.typography.titleMedium)
                                StatusPill("${pendingRequests.size} WAITING", semantic.warning)
                            }
                            pendingRequests.forEach { req ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(req.clientName, style = MaterialTheme.typography.titleSmall)
                                        if (req.isOfflineRequest) {
                                            StatusPill("OFFLINE REQ", semantic.warning)
                                        }
                                    }
                                    Text(
                                        "${req.requestedMode.label} • ${req.clientEndpoint}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = semantic.cyanAccent
                                    )
                                    if (req.requestNote.isNotBlank()) {
                                        Text(
                                            "\"${req.requestNote}\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onRejectRequest(req) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("host_inline_reject_${req.connectionId}")
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text("Reject")
                                        }
                                        Button(
                                            onClick = { onApproveRequest(req, false) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("host_inline_approve_${req.connectionId}")
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text("Accept")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { onApproveRequest(req, true) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("host_inline_trust_${req.connectionId}")
                                    ) {
                                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text("Trust & Accept Offline")
                                    }
                                }
                            }
                        }
                    }
                }

                // Offline Wi-Fi Direct Group Card (Works with Zero Router / Zero Internet)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Offline Wi-Fi Direct Link", style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = if (offlineP2pState.isGroupActive) "P2P ACTIVE" else "READY",
                                color = if (offlineP2pState.isGroupActive) semantic.success else semantic.cyanAccent
                            )
                        }
                        Text(
                            text = offlineP2pState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!offlineP2pState.isWifiEnabled) semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!offlineP2pState.isWifiEnabled) {
                            OutlinedButton(
                                onClick = { openWifiPanelOrSettings(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("host_open_wifi_btn")
                            ) {
                                Text("Turn On Wi-Fi (Required for Direct Sharing)")
                            }
                        }
                        if (offlineP2pState.isGroupActive && offlineP2pState.groupSsid.isNotBlank()) {
                            Text(
                                text = "SSID: ${offlineP2pState.groupSsid}\nPassphrase: ${offlineP2pState.groupPassphrase}\nGateway IP: ${offlineP2pState.groupOwnerIp.ifBlank { "192.168.49.1" }}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = semantic.cyanAccent
                            )
                        }
                        permissionHint?.let { hint ->
                            Text(hint, style = MaterialTheme.typography.bodySmall, color = semantic.warning)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (OfflineWifiDirectManager.hasRequiredPermission(context)) {
                                        onStartOfflineWifiDirectGroup()
                                    } else {
                                        p2pPermissionLauncher.launch(OfflineWifiDirectManager.requiredPermission())
                                    }
                                },
                                enabled = offlineP2pState.isSupported,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("host_wifi_direct_btn")
                            ) {
                                Text(if (offlineP2pState.isGroupActive) "Refresh P2P Group" else "Create Offline P2P Group")
                            }
                            if (offlineP2pState.isGroupActive) {
                                OutlinedButton(
                                    onClick = onStopOfflineWifiDirect,
                                    modifier = Modifier.testTag("host_stop_wifi_direct_btn")
                                ) {
                                    Text("Stop P2P")
                                }
                            }
                        }
                    }
                }

                // Quick Navigation Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSharingCode,
                        enabled = sharingCode.code.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("host_sharing_code_btn")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Code & QR")
                    }
                    OutlinedButton(
                        onClick = onOpenApprovals,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("host_approvals_btn")
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Approvals (${pendingRequests.size})")
                    }
                }

                OutlinedButton(
                    onClick = onOpenConnectedDevices,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_connected_devices_btn")
                ) {
                    Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Manage Connected Devices (${connectedPeers.size}/${settings.maxConnectedDevices})")
                }

                // Host Policy Controls
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Permitted Access Modes & Offline Trust", style = MaterialTheme.typography.titleMedium)

                        ModeSwitchRow(
                            title = "Mode A · Built-in Browser",
                            checked = settings.allowBrowserMode,
                            onCheckedChange = { onUpdatePermittedModes(it, settings.allowDownloadsMode, settings.allowVpnMode) }
                        )
                        ModeSwitchRow(
                            title = "Mode B · Browser & Downloads",
                            checked = settings.allowDownloadsMode,
                            onCheckedChange = { onUpdatePermittedModes(settings.allowBrowserMode, it, settings.allowVpnMode) }
                        )
                        ModeSwitchRow(
                            title = "Mode C · Whole-Device VPN",
                            checked = settings.allowVpnMode,
                            onCheckedChange = { onUpdatePermittedModes(settings.allowBrowserMode, settings.allowDownloadsMode, it) }
                        )
                        ModeSwitchRow(
                            title = "Auto-Accept Trusted Offline Peers",
                            checked = settings.autoAcceptTrustedPeers,
                            onCheckedChange = onUpdateAutoAcceptTrusted
                        )
                        ModeSwitchRow(
                            title = "Allow Open Offline Internet Requests (No Code)",
                            checked = settings.allowOpenOfflineRequests,
                            onCheckedChange = onUpdateAllowOpenOfflineRequests,
                            switchTestTag = "host_allow_open_offline_switch"
                        )

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Max Connected Devices: ${settings.maxConnectedDevices}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Slider(
                            value = settings.maxConnectedDevices.toFloat(),
                            onValueChange = { onUpdateMaxDevices(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8
                        )
                    }
                }
                if (nearbyOfflineNeeds.isNotEmpty()) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = semantic.warning.copy(alpha = 0.55f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Nearby Offline Requesters", style = MaterialTheme.typography.titleMedium)
                                StatusPill("${nearbyOfflineNeeds.size} NEARBY", semantic.warning)
                            }
                            nearbyOfflineNeeds.forEach { need ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "${need.deviceName} (${need.senderIp}) · ${need.requestedMode.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (need.note.isNotBlank()) {
                                        Text(
                                            "\"${need.note}\"",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = semantic.cyanAccent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (switchTestTag.isNotBlank()) Modifier.testTag(switchTestTag) else Modifier
        )
    }
}

private fun openWifiPanelOrSettings(context: Context) {
    val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(panelIntent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
fun SharingCodeScreen(
    sharingCode: SharingCodeState,
    onRegenerate: () -> Unit,
    onRevoke: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val semantic = LocalWiFiShareColors.current
    val qrBitmap = remember(sharingCode.qrPayload) {
        QrCodeGenerator.generateQrBitmap(sharingCode.qrPayload, 560)
    }

    var remainingSec by remember(sharingCode.expiresAtEpochSec) {
        mutableLongStateOf((sharingCode.expiresAtEpochSec - System.currentTimeMillis() / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(sharingCode.expiresAtEpochSec) {
        while (sharingCode.expiresAtEpochSec > 0L) {
            remainingSec = (sharingCode.expiresAtEpochSec - System.currentTimeMillis() / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Sharing Code & QR", onBack = onBack) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (sharingCode.code.isBlank()) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No active sharing code. Start Host Sharing first to generate a cryptographic code.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("ONE-TIME SHARING CODE", style = MaterialTheme.typography.labelSmall, color = semantic.cyanAccent)
                            Text(
                                text = sharingCode.code,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Expires in ${formatDuration(remainingSec)} • Offline & online approval ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .size(230.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color.White)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "WiFiShare pairing QR code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Text(
                                text = "Direct Socket: ${sharingCode.listenHost}:${sharingCode.listenPort} • ${sharingCode.stunEndpoint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(sharingCode.inviteUri.ifBlank { sharingCode.code })) },
                                    modifier = Modifier.weight(1f).testTag("copy_code_btn")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Copy Invite")
                                }
                                Button(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "Connect to my WiFiShare gateway:\nCode: ${sharingCode.code}\nDirect Host: ${sharingCode.listenHost}:${sharingCode.listenPort}\nInvite: ${sharingCode.inviteUri}"
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share WiFiShare Invite"))
                                    },
                                    modifier = Modifier.weight(1f).testTag("share_code_btn")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Share")
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRegenerate,
                            modifier = Modifier.weight(1f).testTag("regenerate_code_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Regenerate")
                        }
                        Button(
                            onClick = { onRevoke(); onBack() },
                            modifier = Modifier.weight(1f).testTag("revoke_code_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Revoke Code")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApprovalScreen(
    pendingRequests: List<PendingJoinRequest>,
    nearbyOfflineNeeds: List<NearbyOfflineNeed>,
    savedClients: List<SavedPeerEntity>,
    autoAcceptTrusted: Boolean,
    allowOpenOfflineRequests: Boolean,
    onToggleAutoAcceptTrusted: (Boolean) -> Unit,
    onToggleAllowOpenOfflineRequests: (Boolean) -> Unit,
    onApprove: (PendingJoinRequest, Boolean) -> Unit,
    onReject: (PendingJoinRequest) -> Unit,
    onSetClientTrusted: (SavedPeerEntity, Boolean) -> Unit,
    onRemoveSavedClient: (Long) -> Unit,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    Scaffold(
        topBar = { WiFiShareTopBar(title = "Connection Approvals", onBack = onBack) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwitchRow(
                        title = "Auto-Accept Trusted Devices Offline & Background",
                        checked = autoAcceptTrusted,
                        onCheckedChange = onToggleAutoAcceptTrusted
                    )
                    ModeSwitchRow(
                        title = "Allow Open Offline Internet Requests (No Code)",
                        checked = allowOpenOfflineRequests,
                        onCheckedChange = onToggleAllowOpenOfflineRequests
                    )
                }
            }

            Text("Pending Requests", style = MaterialTheme.typography.titleMedium)
            if (pendingRequests.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No pending client connection requests. Incoming code or offline requests can be accepted here or directly from the background notification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                pendingRequests.forEach { req ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (req.isOfflineRequest) {
                            semantic.warning.copy(alpha = 0.55f)
                        } else {
                            semantic.cyanAccent.copy(alpha = 0.45f)
                        }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(req.clientName, style = MaterialTheme.typography.titleMedium)
                                if (req.isOfflineRequest) {
                                    StatusPill("OFFLINE REQUEST", semantic.warning)
                                }
                            }
                            Text(
                                "Mode: ${req.requestedMode.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.cyanAccent
                            )
                            if (req.requestNote.isNotBlank()) {
                                Text(
                                    "Note: \"${req.requestNote}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                "Endpoint: ${req.clientEndpoint} • Requested: ${req.requestedAt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onReject(req) },
                                    modifier = Modifier.weight(1f).testTag("reject_peer_${req.connectionId}")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Reject")
                                }
                                OutlinedButton(
                                    onClick = { onApprove(req, false) },
                                    modifier = Modifier.weight(1f).testTag("approve_peer_${req.connectionId}")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Approve")
                                }
                            }
                            Button(
                                onClick = { onApprove(req, true) },
                                modifier = Modifier.fillMaxWidth().testTag("trust_approve_peer_${req.connectionId}")
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Trust & Accept (Auto-Accept Offline)")
                            }
                        }
                    }
                }
            }

            if (nearbyOfflineNeeds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Nearby Offline Need Broadcasts", style = MaterialTheme.typography.titleMedium)
                nearbyOfflineNeeds.forEach { need ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(need.deviceName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${need.senderIp} • ${need.requestedMode.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = semantic.cyanAccent
                            )
                            if (need.note.isNotBlank()) {
                                Text(
                                    "\"${need.note}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (savedClients.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Saved Offline Client Devices", style = MaterialTheme.typography.titleMedium)
                savedClients.forEach { client ->
                    val isTrusted = client.approvalStatus == "TRUSTED"
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(client.peerName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${client.approvalStatus} • ${client.hostIp}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTrusted) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isTrusted,
                                onCheckedChange = { onSetClientTrusted(client, it) }
                            )
                            IconButton(onClick = { onRemoveSavedClient(client.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove ${client.peerName}",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedDevicesScreen(
    peers: List<ConnectedPeer>,
    onDisconnectPeer: (Long) -> Unit,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    Scaffold(
        topBar = { WiFiShareTopBar(title = "Connected Devices", onBack = onBack) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (peers.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No devices are currently connected to your gateway.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                peers.forEach { peer ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${peer.virtualIp} • ${peer.mode.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = semantic.cyanAccent
                                )
                                Text(
                                    "↑ ${formatSpeed(peer.uploadBps)} (${formatBytes(peer.bytesUploaded)})  ↓ ${formatSpeed(peer.downloadBps)} (${formatBytes(peer.bytesDownloaded)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDisconnectPeer(peer.connectionId) },
                                modifier = Modifier.testTag("disconnect_peer_${peer.connectionId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.StopCircle,
                                    contentDescription = "Disconnect ${peer.deviceName}",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
