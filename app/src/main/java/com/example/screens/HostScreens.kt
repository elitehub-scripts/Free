package com.example.screens

import android.content.Intent
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import com.example.model.ActiveNetworkInfo
import com.example.model.ConnectedPeer
import com.example.model.LiveTrafficSnapshot
import com.example.model.PendingJoinRequest
import com.example.model.SharingCodeState
import com.example.model.TunnelState
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
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    onUpdatePermittedModes: (Boolean, Boolean, Boolean) -> Unit,
    onUpdateMaxDevices: (Int) -> Unit,
    onOpenSharingCode: () -> Unit,
    onOpenApprovals: () -> Unit,
    onOpenConnectedDevices: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val semantic = LocalWiFiShareColors.current
    val isHostActive = (tunnelState as? TunnelState.Connected)?.role == "HOST"
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
                            Column {
                                Text("Active Egress Source", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (networkInfo.isConnected) {
                                        "${networkInfo.transport.name} • ${networkInfo.interfaceName} (${networkInfo.localIp})"
                                    } else {
                                        "No internet connection"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(
                                label = if (isHostActive) "SHARING ON" else "STOPPED",
                                color = if (isHostActive) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
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
                        }

                        Button(
                            onClick = if (isHostActive) onStopSharing else onStartSharing,
                            enabled = networkInfo.isConnected || isHostActive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("host_start_stop_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHostActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (isHostActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = if (isHostActive) Icons.Default.StopCircle else Icons.Default.WifiTethering,
                                contentDescription = null
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(if (isHostActive) "Stop Sharing Immediately" else "Start Sharing Internet")
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
                        Text("Permitted Access Modes", style = MaterialTheme.typography.titleMedium)

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
            }
        }
    }
}

@Composable
private fun ModeSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                                text = "Expires in ${formatDuration(remainingSec)} • Approval required",
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
                                text = "Direct Socket: ${sharingCode.listenHost}:${sharingCode.listenPort} • NAT: ${sharingCode.stunEndpoint}",
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
    onApprove: (PendingJoinRequest) -> Unit,
    onReject: (PendingJoinRequest) -> Unit,
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
            if (pendingRequests.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No pending client connection requests. Incoming requests appear here for your explicit authorization.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                pendingRequests.forEach { req ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = semantic.cyanAccent.copy(alpha = 0.45f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(req.clientName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Mode: ${req.requestedMode.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.cyanAccent
                            )
                            Text(
                                "Endpoint: ${req.clientEndpoint} • Requested: ${req.requestedAt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onReject(req) },
                                    modifier = Modifier.weight(1f).testTag("reject_peer_${req.connectionId}")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Reject")
                                }
                                Button(
                                    onClick = { onApprove(req) },
                                    modifier = Modifier.weight(1f).testTag("approve_peer_${req.connectionId}")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Approve")
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
