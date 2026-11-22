package com.example.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.database.TrafficRecordEntity
import com.example.database.TunnelEventEntity
import com.example.model.AiDiagnosticUiState
import com.example.model.DiagnosticProbeResult
import com.example.model.LiveTrafficSnapshot
import com.example.settings.SearchEngine
import com.example.settings.WiFiShareSettings
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.components.TrustNoticeBanner
import com.example.ui.components.WiFiShareTopBar
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatSpeed
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalWiFiShareColors

@Composable
fun TrafficStatsScreen(
    liveTraffic: LiveTrafficSnapshot,
    dailyUpload: Long,
    dailyDownload: Long,
    monthlyUpload: Long,
    monthlyDownload: Long,
    records: List<TrafficRecordEntity>,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Traffic & Bandwidth", onBack = onBack) }
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
                modifier = Modifier.widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TrustNoticeBanner(
                    text = stringResource(R.string.measurement_overhead_notice),
                    icon = Icons.Default.Info,
                    tint = semantic.cyanAccent
                )

                // Live Throughput Chart
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Real-Time Speed Monitor", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "↑ Upload: ${formatSpeed(liveTraffic.uploadBps)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = semantic.cyanAccent
                            )
                            Text(
                                "↓ Download: ${formatSpeed(liveTraffic.downloadBps)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = semantic.cobaltAccent
                            )
                        }

                        val upSeries = liveTraffic.speedHistoryUpload
                        val downSeries = liveTraffic.speedHistoryDownload
                        val maxVal = (upSeries + downSeries).maxOrNull()?.coerceAtLeast(1024L) ?: 1024L

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                        ) {
                            val w = size.width
                            val h = size.height
                            drawLine(
                                color = semantic.glassBorder,
                                start = Offset(0f, h / 2f),
                                end = Offset(w, h / 2f),
                                strokeWidth = 1.dp.toPx()
                            )
                            fun drawSeries(series: List<Long>, color: androidx.compose.ui.graphics.Color) {
                                if (series.size < 2) return
                                val path = Path()
                                val stepX = w / (series.size - 1).coerceAtLeast(1)
                                series.forEachIndexed { idx, v ->
                                    val x = idx * stepX
                                    val y = h - ((v.toFloat() / maxVal.toFloat()) * (h - 8.dp.toPx())) - 4.dp.toPx()
                                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, color = color, style = Stroke(width = 2.5.dp.toPx()))
                            }
                            drawSeries(upSeries, semantic.cyanAccent)
                            drawSeries(downSeries, semantic.cobaltAccent)
                        }
                    }
                }

                // Daily & Monthly Accounting
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recorded Usage Summaries", style = MaterialTheme.typography.titleMedium)
                        KeyValueRow("Active Session Upload", formatBytes(liveTraffic.totalUploadedBytes))
                        KeyValueRow("Active Session Download", formatBytes(liveTraffic.totalDownloadedBytes))
                        KeyValueRow("Today's Upload / Download", "${formatBytes(dailyUpload)} / ${formatBytes(dailyDownload)}")
                        KeyValueRow("Monthly Upload / Download", "${formatBytes(monthlyUpload)} / ${formatBytes(monthlyDownload)}")
                    }
                }

                Text("Completed Session Logs", style = MaterialTheme.typography.titleMedium)
                if (records.isEmpty()) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Completed Host and Client sessions are saved here automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    records.take(15).forEach { rec ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("${rec.role} · ${rec.peerLabel}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${rec.dateKey} • ${formatDuration(rec.durationSeconds)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "↑ ${formatBytes(rec.bytesUploaded)}\n↓ ${formatBytes(rec.bytesDownloaded)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
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
fun SettingsScreen(
    settings: WiFiShareSettings,
    onUpdateTheme: (AppThemeMode) -> Unit,
    onUpdateDirectP2p: (Int, Boolean) -> Unit,
    onUpdateStun: (String, Int) -> Unit,
    onUpdateBrowserHome: (String) -> Unit,
    onUpdateSearchEngine: (SearchEngine) -> Unit,
    onUpdateAutoReconnect: (Boolean) -> Unit,
    onUpdateDataLimit: (Long, Boolean) -> Unit,
    onUpdateSessionExpiry: (Int) -> Unit,
    onUpdateVpnSettings: (Int, Boolean, Boolean) -> Unit,
    onUpdateNotifications: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClearCookies: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    var listenPort by remember(settings.directListenPort) { mutableStateOf(settings.directListenPort.toString()) }
    var nsdEnabled by remember(settings.enableNsdDiscovery) { mutableStateOf(settings.enableNsdDiscovery) }
    var stunHost by remember(settings.stunHost) { mutableStateOf(settings.stunHost) }
    var homeUrl by remember(settings.browserHomePage) { mutableStateOf(settings.browserHomePage) }

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Settings", onBack = onBack) }
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
                modifier = Modifier.widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Appearance
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateTheme(mode) },
                                    label = { Text(mode.name) }
                                )
                            }
                        }
                    }
                }

                // Direct P2P & Discovery Settings (In-App, No Server)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Direct P2P & Discovery", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = listenPort,
                            onValueChange = { listenPort = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Direct Gateway Listen Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SettingSwitch("Local LAN Discovery (NSD / mDNS)", nsdEnabled) {
                            nsdEnabled = it
                        }
                        OutlinedTextField(
                            value = stunHost,
                            onValueChange = { stunHost = it },
                            label = { Text("STUN Server Host (Optional NAT Check)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onUpdateDirectP2p(listenPort.toIntOrNull() ?: 51821, nsdEnabled)
                                onUpdateStun(stunHost, settings.stunPort)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("save_server_settings_btn")
                        ) {
                            Text("Save Network Settings")
                        }
                    }
                }

                // VPN & Limits
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("VPN & Quota Controls", style = MaterialTheme.typography.titleMedium)
                        Text("VPN MTU: ${settings.vpnMtu} bytes", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = settings.vpnMtu.toFloat(),
                            onValueChange = { onUpdateVpnSettings(it.toInt(), settings.dnsLeakProtection, settings.ipv6Enabled) },
                            valueRange = 1280f..1500f
                        )
                        SettingSwitch("DNS Leak Protection (10.88.0.1)", settings.dnsLeakProtection) {
                            onUpdateVpnSettings(settings.vpnMtu, it, settings.ipv6Enabled)
                        }
                        SettingSwitch("IPv6 ULA Tunnel Support", settings.ipv6Enabled) {
                            onUpdateVpnSettings(settings.vpnMtu, settings.dnsLeakProtection, it)
                        }
                        SettingSwitch("Automatic Reconnection", settings.autoReconnect, onUpdateAutoReconnect)
                        SettingSwitch("Foreground Notifications", settings.notificationsEnabled, onUpdateNotifications)
                        SettingSwitch("Enforce Data Limit (${settings.dataLimitMb} MB)", settings.enforceDataLimit) {
                            onUpdateDataLimit(settings.dataLimitMb, it)
                        }
                        Text("Session Expiration: ${settings.sessionExpiryMinutes} min", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = settings.sessionExpiryMinutes.toFloat(),
                            onValueChange = { onUpdateSessionExpiry(it.toInt()) },
                            valueRange = 15f..360f
                        )
                    }
                }

                // Browser Preferences & Privacy Cleanup
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Built-in Browser & Privacy", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = homeUrl,
                            onValueChange = { homeUrl = it },
                            label = { Text("Home Page URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = { onUpdateBrowserHome(homeUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Save Home Page")
                        }
                        Text("Search Engine", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SearchEngine.entries.forEach { engine ->
                                FilterChip(
                                    selected = settings.searchEngine == engine,
                                    onClick = { onUpdateSearchEngine(engine) },
                                    label = { Text(engine.label) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onClearHistory,
                                modifier = Modifier.weight(1f).testTag("settings_clear_history_btn")
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Clear History")
                            }
                            OutlinedButton(
                                onClick = onClearCookies,
                                modifier = Modifier.weight(1f).testTag("settings_clear_cookies_btn")
                            ) {
                                Text("Clear Cookies")
                            }
                        }
                    }
                }

                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("settings_sign_out_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Sign Out & Wipe Local Token")
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun PrivacySecurityScreen(
    droppedReplayPackets: Long,
    dnsLeakProtection: Boolean,
    deviceUuid: String,
    onClearHistory: () -> Unit,
    onClearCookies: () -> Unit,
    onRevokeAndSignOut: () -> Unit,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    Scaffold(
        topBar = { WiFiShareTopBar(title = "Privacy & Security", onBack = onBack) }
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
                modifier = Modifier.widthIn(max = 660.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TrustNoticeBanner(
                    text = stringResource(R.string.host_trust_warning),
                    icon = Icons.Default.Security,
                    tint = semantic.warning
                )

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = semantic.cyanAccent)
                            Text("Cryptographic Guarantees", style = MaterialTheme.typography.titleMedium)
                        }
                        KeyValueRow("Key Exchange", "Ephemeral ECDH (secp256r1)")
                        KeyValueRow("Session Key Derivation", "HKDF-SHA256 (Directional TX/RX)")
                        KeyValueRow("Packet AEAD Cipher", "AES-256-GCM (128-bit Tag + AAD)")
                        KeyValueRow("Replay Window Protection", "64-packet sliding bitmap ($droppedReplayPackets dropped)")
                        KeyValueRow("Client Isolation", "Enforced (10.88.0.0/24 peer-to-peer blocked)")
                        KeyValueRow("DNS Leak Prevention", if (dnsLeakProtection) "Active (10.88.0.1 tunneled)" else "Off")
                        KeyValueRow("Credential Storage", "Android KeyStore + PBKDF2-SHA256")
                        KeyValueRow("App Data Persistence", "100% On-Device (Room SQLite + DataStore)")
                        KeyValueRow("Device UUID", deviceUuid.take(18) + "…")
                    }
                }

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Local Data Hygiene", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onClearHistory, modifier = Modifier.weight(1f)) {
                                Text("Clear History")
                            }
                            OutlinedButton(onClick = onClearCookies, modifier = Modifier.weight(1f)) {
                                Text("Clear Cookies")
                            }
                        }
                        Button(
                            onClick = onRevokeAndSignOut,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Revoke Active Sessions & Sign Out")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TroubleshootingScreen(
    probes: List<DiagnosticProbeResult>,
    events: List<TunnelEventEntity>,
    aiState: AiDiagnosticUiState,
    onRunProbes: () -> Unit,
    onAskAi: (String) -> Unit,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    var diagnosticPrompt by remember {
        mutableStateOf("Why might direct peer-to-peer sockets or Whole-Device VPN fail across NAT/firewalls, and how should I verify mDNS, DNS, and MTU routing?")
    }

    LaunchedEffect(Unit) {
        onRunProbes()
    }

    Scaffold(
        topBar = {
            WiFiShareTopBar(
                title = "Diagnostics & Troubleshooting",
                onBack = onBack,
                actions = {
                    OutlinedButton(
                        onClick = onRunProbes,
                        modifier = Modifier.testTag("run_probes_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Re-test")
                    }
                }
            )
        }
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
                modifier = Modifier.widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Live Connectivity Probes
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Live Network & Tunnel Probes", style = MaterialTheme.typography.titleMedium)
                        if (probes.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            probes.forEach { probe ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (probe.passed == true) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (probe.passed == true) semantic.success else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(probe.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            probe.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Gemini 4 Carbon High-Thinking Network Diagnostics Advisor
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = semantic.cyanAccent.copy(alpha = 0.5f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = semantic.cyanAccent)
                            Text("Deep Tunnel Diagnostics Advisor", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "Analyzes your live network probes, NAT traversal state, MTU, and tunnel event logs with deep reasoning.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = diagnosticPrompt,
                            onValueChange = { diagnosticPrompt = it },
                            label = { Text("Describe connection issue or question") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("ai_diag_input")
                        )
                        Button(
                            onClick = { onAskAi(diagnosticPrompt) },
                            enabled = aiState !is AiDiagnosticUiState.Loading && diagnosticPrompt.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("ai_diag_submit_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Analyze Live Tunnel State")
                        }

                        when (aiState) {
                            is AiDiagnosticUiState.Loading -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("Analyzing tunnel telemetry...", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            is AiDiagnosticUiState.Streaming -> {
                                if (aiState.thoughts.isNotBlank()) {
                                    Text(
                                        "Reasoning:\n${aiState.thoughts}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = semantic.cyanAccent
                                    )
                                }
                                Text(aiState.answer, style = MaterialTheme.typography.bodyMedium)
                            }
                            is AiDiagnosticUiState.Success -> {
                                if (aiState.thoughts.isNotBlank()) {
                                    Text(
                                        "Reasoning:\n${aiState.thoughts}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = semantic.cyanAccent
                                    )
                                }
                                Text(aiState.answer, style = MaterialTheme.typography.bodyMedium)
                            }
                            is AiDiagnosticUiState.Error -> {
                                StatusPill("Diagnostics Error", MaterialTheme.colorScheme.error)
                                Text(aiState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            AiDiagnosticUiState.Idle -> Unit
                        }
                    }
                }

                // Local Tunnel Event Log
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Recent Tunnel & Socket Events", style = MaterialTheme.typography.titleMedium)
                        if (events.isEmpty()) {
                            Text("No events recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            events.take(20).forEach { ev ->
                                Text(
                                    "[${ev.level}] ${ev.component}: ${ev.message}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = when (ev.level) {
                                        "ERROR" -> MaterialTheme.colorScheme.error
                                        "WARN" -> semantic.warning
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
