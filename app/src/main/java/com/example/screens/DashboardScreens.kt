package com.example.screens

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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ActiveNetworkInfo
import com.example.model.AuthUserSession
import com.example.model.LiveTrafficSnapshot
import com.example.model.NetworkTransportType
import com.example.model.TunnelState
import com.example.ui.components.GlassCard
import com.example.ui.components.MetricTile
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatSpeed
import com.example.ui.theme.LocalWiFiShareColors
import kotlinx.coroutines.delay

@Composable
fun HomeDashboardScreen(
    contentPadding: PaddingValues,
    authSession: AuthUserSession,
    networkInfo: ActiveNetworkInfo,
    tunnelState: TunnelState,
    traffic: LiveTrafficSnapshot,
    connectedPeersCount: Int,
    pendingRequestsCount: Int,
    isBackgroundServiceActive: Boolean = false,
    onNavigateHost: () -> Unit,
    onNavigateClient: () -> Unit,
    onRequestOfflineInternet: () -> Unit,
    onNavigateBrowser: () -> Unit,
    onNavigateDownloads: () -> Unit,
    onNavigateVpn: () -> Unit,
    onNavigateTraffic: () -> Unit,
    onNavigatePrivacy: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateDiagnostics: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnelState) {
        while (tunnelState is TunnelState.Connected) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val durationSec = if (tunnelState is TunnelState.Connected) {
        ((nowMs - tunnelState.startedAtMs) / 1000L).coerceAtLeast(0L)
    } else 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top greeting + quick diagnostics/settings icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "WiFiShare Control",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = authSession.displayName.ifBlank { authSession.email }.ifBlank { "Signed In" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(
                        onClick = onNavigateDiagnostics,
                        modifier = Modifier.testTag("dash_diagnostics_btn")
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = "Diagnostics & Troubleshooting")
                    }
                    IconButton(
                        onClick = onNavigateSettings,
                        modifier = Modifier.testTag("dash_settings_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }

            // Hero Network Status Card with generated artwork
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_network),
                    contentDescription = "Encrypted network tunnel mesh",
                    modifier = Modifier
                        .matchParentSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.28f
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
                                )
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (statusLabel, statusColor) = when (tunnelState) {
                                is TunnelState.Idle -> "Standby" to MaterialTheme.colorScheme.onSurfaceVariant
                                is TunnelState.Handshaking -> "Handshaking" to semantic.warning
                                is TunnelState.AwaitingHostApproval -> "Awaiting Approval" to semantic.warning
                                is TunnelState.Connected -> {
                                    val bgSuffix = if (isBackgroundServiceActive) " · BG" else ""
                                    "${tunnelState.role} · Connected$bgSuffix" to semantic.success
                                }
                                is TunnelState.Reconnecting -> "Reconnecting" to semantic.warning
                                is TunnelState.Error -> "Tunnel Error" to MaterialTheme.colorScheme.error
                            }
                            StatusPill(label = statusLabel, color = statusColor)

                            val netIcon = when (networkInfo.transport) {
                                NetworkTransportType.WIFI -> Icons.Default.Wifi
                                NetworkTransportType.CELLULAR -> Icons.Default.SignalCellularAlt
                                else -> Icons.Default.WifiOff
                            }
                            StatusPill(
                                label = when {
                                    networkInfo.isConnected -> "${networkInfo.transport.name} (${networkInfo.localIp})"
                                    networkInfo.hasLocalLink -> "Offline LAN (${networkInfo.localIp})"
                                    else -> "Offline"
                                },
                                color = when {
                                    networkInfo.isConnected -> semantic.cyanAccent
                                    networkInfo.hasLocalLink -> semantic.warning
                                    else -> MaterialTheme.colorScheme.error
                                },
                                leadingIcon = netIcon
                            )
                        }

                        when (tunnelState) {
                            is TunnelState.Connected -> {
                                Text(
                                    text = "${tunnelState.mode.label} • ${tunnelState.transportPath}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Peer: ${tunnelState.peerLabel}  |  Virtual IP: ${tunnelState.assignedVirtualIp}  |  RTT: ${traffic.tunnelLatencyMs} ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is TunnelState.Error -> {
                                Text(
                                    text = tunnelState.message,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = tunnelState.recoveryHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                Text(
                                    text = "Choose Host Mode to share your internet or Client Mode to connect through a remote host.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Primary Role Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onNavigateHost,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("dash_host_mode_btn"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (pendingRequestsCount > 0) "Host ($pendingRequestsCount)" else "Host Mode")
                }

                OutlinedButton(
                    onClick = onNavigateClient,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("dash_client_mode_btn"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Client Mode")
                }
            }

            if (tunnelState is TunnelState.Idle) {
                OutlinedButton(
                    onClick = onRequestOfflineInternet,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("dash_request_offline_btn"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Request Internet Offline (Nearby Host)")
                }
            }

            // Real Telemetry Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricTile(
                    title = "Upload Speed",
                    value = formatSpeed(traffic.uploadBps),
                    subtitle = "Total ${formatBytes(traffic.totalUploadedBytes)}",
                    icon = Icons.Default.ArrowUpward,
                    accentColor = semantic.cyanAccent,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Download Speed",
                    value = formatSpeed(traffic.downloadBps),
                    subtitle = "Total ${formatBytes(traffic.totalDownloadedBytes)}",
                    icon = Icons.Default.ArrowDownward,
                    accentColor = semantic.cobaltAccent,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricTile(
                    title = "Session Duration",
                    value = formatDuration(durationSec),
                    subtitle = if (tunnelState is TunnelState.Connected) "Live tunnel" else "Inactive",
                    icon = Icons.Default.Timer,
                    accentColor = semantic.success,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Connected Peers",
                    value = "$connectedPeersCount",
                    subtitle = if (pendingRequestsCount > 0) "$pendingRequestsCount pending" else "Zero pending",
                    icon = Icons.Default.Devices,
                    accentColor = semantic.warning,
                    modifier = Modifier.weight(1f)
                )
            }

            // Quick Access Tools
            Text(
                text = "Network Tools & Controls",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickToolCard("Browser", Icons.Default.Public, onNavigateBrowser, Modifier.weight(1f), "dash_browser_card")
                QuickToolCard("Downloads", Icons.Default.Download, onNavigateDownloads, Modifier.weight(1f), "dash_downloads_card")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickToolCard("Device VPN", Icons.Default.Shield, onNavigateVpn, Modifier.weight(1f), "dash_vpn_card")
                QuickToolCard("Traffic Stats", Icons.Default.BarChart, onNavigateTraffic, Modifier.weight(1f), "dash_traffic_card")
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dash_privacy_card"),
                onClick = onNavigatePrivacy
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = semantic.cyanAccent
                        )
                        Column {
                            Text("Privacy & Tunnel Security", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "ECDH + AES-256-GCM • Anti-replay window • Client isolation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickToolCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    tag: String
) {
    GlassCard(
        modifier = modifier.testTag(tag),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
