package com.example.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.database.SavedPeerEntity
import com.example.model.DiscoveredHostPeer
import com.example.model.HostPublicInfo
import com.example.model.LiveTrafficSnapshot
import com.example.model.SharingMode
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay

@Composable
fun ClientModeScreen(
    contentPadding: PaddingValues,
    initialCode: String,
    defaultPort: Int,
    selectedMode: SharingMode,
    tunnelState: TunnelState,
    hostInfo: HostPublicInfo?,
    traffic: LiveTrafficSnapshot,
    isVpnActive: Boolean,
    discoveredHosts: List<DiscoveredHostPeer>,
    isScanningNearby: Boolean,
    savedHosts: List<SavedPeerEntity>,
    onStartNearbyScan: () -> Unit,
    onStopNearbyScan: () -> Unit,
    onSelectMode: (SharingMode) -> Unit,
    onRequestJoin: (rawCodeOrUri: String, hostIp: String, hostPort: Int?) -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenVpnScreen: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val semantic = LocalWiFiShareColors.current
    val initialParsed = remember(initialCode) { QrCodeGenerator.parseShareInvite(initialCode) }
    var codeInput by remember(initialCode) {
        mutableStateOf(
            if (initialCode.startsWith("wifishare://", ignoreCase = true)) initialCode
            else initialParsed?.code ?: initialCode
        )
    }
    var hostIpInput by remember(initialCode) { mutableStateOf(initialParsed?.hostIp.orEmpty()) }
    var hostPortInput by remember(initialCode) {
        mutableStateOf((initialParsed?.hostPort ?: defaultPort).toString())
    }

    val isClientConnected = (tunnelState as? TunnelState.Connected)?.role == "CLIENT"
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isClientConnected) {
        while (isClientConnected) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onStopNearbyScan() }
    }

    val durationSec = if (isClientConnected) {
        ((nowMs - (tunnelState as TunnelState.Connected).startedAtMs) / 1000L).coerceAtLeast(0L)
    } else 0L

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Connect to a Host", onBack = onBack) }
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
                    text = stringResource(R.string.client_trust_notice),
                    icon = Icons.Default.Security,
                    tint = semantic.cyanAccent
                )

                // Sharing Code / Direct Endpoint Card
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enter Host Code or Invite Link", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { raw ->
                                    codeInput = raw
                                    if (raw.startsWith("wifishare://", ignoreCase = true)) {
                                        QrCodeGenerator.parseShareInvite(raw)?.let { parsed ->
                                            if (!parsed.hostIp.isNullOrBlank()) hostIpInput = parsed.hostIp
                                            if (parsed.hostPort != null) hostPortInput = parsed.hostPort.toString()
                                        }
                                    }
                                },
                                label = { Text("Code (WFS-XXXX-XXXX) or wifishare://...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("client_code_input")
                            )
                            OutlinedButton(
                                onClick = onOpenQrScanner,
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("client_scan_qr_btn")
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = hostIpInput,
                                onValueChange = { hostIpInput = it },
                                label = { Text("Host IP (auto on LAN/QR)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(2f)
                                    .testTag("client_host_ip_input")
                            )
                            OutlinedTextField(
                                value = hostPortInput,
                                onValueChange = { hostPortInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("client_host_port_input")
                            )
                        }

                        // Access Mode Selection
                        Text("Choose Access Mode", style = MaterialTheme.typography.titleSmall)
                        SharingMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedMode == mode,
                                    onClick = { onSelectMode(mode) },
                                    modifier = Modifier.testTag("client_mode_${mode.code}")
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (!isClientConnected) {
                            Button(
                                onClick = {
                                    onRequestJoin(codeInput, hostIpInput, hostPortInput.toIntOrNull())
                                },
                                enabled = codeInput.isNotBlank() &&
                                    tunnelState !is TunnelState.Handshaking &&
                                    tunnelState !is TunnelState.AwaitingHostApproval,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("client_request_join_btn"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Request Direct Connection")
                            }
                        } else {
                            Button(
                                onClick = onDisconnect,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("client_disconnect_btn"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Disconnect Tunnel")
                            }
                        }
                    }
                }

                // Live Connection Status / Host Info
                when (tunnelState) {
                    is TunnelState.Handshaking -> {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text(tunnelState.stage, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    is TunnelState.AwaitingHostApproval -> {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = semantic.warning.copy(alpha = 0.6f)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill("Awaiting Host Authorization", semantic.warning)
                                Text(
                                    "Direct request sent to ${tunnelState.hostLabel} (${tunnelState.sessionCode}). Waiting for the Host to tap Approve.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(
                                    onClick = onDisconnect,
                                    modifier = Modifier.testTag("client_cancel_wait_btn")
                                ) {
                                    Text("Cancel Request")
                                }
                            }
                        }
                    }
                    is TunnelState.Error -> {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusPill(tunnelState.errorCode, MaterialTheme.colorScheme.error)
                                Text(tunnelState.message, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    tunnelState.recoveryHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    is TunnelState.Connected -> if (tunnelState.role == "CLIENT") {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = semantic.success.copy(alpha = 0.65f)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusPill("Tunnel Connected", semantic.success)
                                    Text(
                                        formatDuration(durationSec),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily)
                                    )
                                }
                                Text(
                                    "Connected Host: ${tunnelState.peerLabel}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (hostInfo != null) {
                                    Text(
                                        "Host Egress: ${hostInfo.networkType} • Virtual IP: ${tunnelState.assignedVirtualIp} • Path: ${tunnelState.transportPath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "Speed: ↑ ${formatSpeed(traffic.uploadBps)}  ↓ ${formatSpeed(traffic.downloadBps)}  •  Transferred: ↑ ${formatBytes(traffic.totalUploadedBytes)} / ↓ ${formatBytes(traffic.totalDownloadedBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onOpenBrowser,
                                        modifier = Modifier.weight(1f).testTag("client_open_browser_btn")
                                    ) {
                                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text("Open Browser")
                                    }
                                    OutlinedButton(
                                        onClick = onOpenVpnScreen,
                                        modifier = Modifier.weight(1f).testTag("client_open_vpn_btn")
                                    ) {
                                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text(if (isVpnActive) "VPN Active" else "Device VPN")
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit
                }

                // Nearby LAN Hosts (Serverless NSD / mDNS Discovery)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Radar, contentDescription = null, tint = semantic.cyanAccent)
                                Text("Nearby Hosts (LAN / Hotspot)", style = MaterialTheme.typography.titleMedium)
                            }
                            OutlinedButton(
                                onClick = { if (isScanningNearby) onStopNearbyScan() else onStartNearbyScan() },
                                modifier = Modifier.testTag("client_scan_nearby_btn")
                            ) {
                                Text(if (isScanningNearby) "Stop Scan" else "Scan LAN")
                            }
                        }
                        if (discoveredHosts.isEmpty()) {
                            Text(
                                if (isScanningNearby) "Listening for nearby WiFiShare Hosts via mDNS..."
                                else "Tap Scan LAN when both phones share the same Wi-Fi or Hotspot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            discoveredHosts.forEach { peer ->
                                OutlinedButton(
                                    onClick = {
                                        codeInput = peer.sharingCode
                                        hostIpInput = peer.hostIp
                                        hostPortInput = peer.hostPort.toString()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${peer.hostName} (${peer.sharingCode}) • ${peer.hostIp}:${peer.hostPort}")
                                }
                            }
                        }
                    }
                }

                // Saved Host History (Room Local Persistence)
                if (savedHosts.isNotEmpty()) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, tint = semantic.cobaltAccent)
                                Text("Saved Hosts (In-App)", style = MaterialTheme.typography.titleMedium)
                            }
                            savedHosts.take(5).forEach { saved ->
                                OutlinedButton(
                                    onClick = {
                                        codeInput = saved.sharingCode
                                        hostIpInput = saved.hostIp
                                        hostPortInput = saved.hostPort.toString()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${saved.peerName} • ${saved.sharingCode} (${saved.hostIp}:${saved.hostPort})")
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
fun QrScannerScreen(
    onCodeScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var torchOn by remember { mutableStateOf(false) }
    var scannedHandled by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            WiFiShareTopBar(
                title = "Scan Host QR Code",
                onBack = onBack,
                actions = {
                    if (hasCameraPermission) {
                        IconButton(onClick = { torchOn = !torchOn }) {
                            Icon(
                                imageVector = if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                                contentDescription = "Toggle flashlight"
                            )
                        }
                    }
                }
            )
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner),
            contentAlignment = Alignment.Center
        ) {
            if (!hasCameraPermission) {
                GlassCard(
                    modifier = Modifier
                        .padding(24.dp)
                        .widthIn(max = 420.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Camera Permission Needed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Grant camera permission to scan a Host's WiFiShare QR code, or return and type the sharing code manually.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { launcher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth().testTag("grant_camera_btn")
                        ) {
                            Text("Allow Camera")
                        }
                    }
                }
            } else {
                val cameraController = remember {
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val scanner = BarcodeScanning.getClient(options)
                    LifecycleCameraController(context).apply {
                        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        setImageAnalysisAnalyzer(
                            ContextCompat.getMainExecutor(context)
                        ) { imageProxy ->
                            @OptIn(ExperimentalGetImage::class)
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || scannedHandled) {
                                imageProxy.close()
                                return@setImageAnalysisAnalyzer
                            }
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val raw = barcodes.firstOrNull()?.rawValue?.trim()
                                    val parsed = raw?.let { QrCodeGenerator.parseShareInvite(it) }
                                    if (raw != null && parsed != null && !scannedHandled) {
                                        scannedHandled = true
                                        val payload = if (raw.startsWith("wifishare://", ignoreCase = true)) raw else parsed.code
                                        onCodeScanned(payload)
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }
                    }
                }

                LaunchedEffect(torchOn) {
                    runCatching { cameraController.enableTorch(torchOn) }
                }
                LaunchedEffect(lifecycleOwner) {
                    cameraController.bindToLifecycle(lifecycleOwner)
                }

                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            controller = cameraController
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        "Align the Host's QR code inside the frame",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun VpnConnectionScreen(
    tunnelState: TunnelState,
    isVpnActive: Boolean,
    traffic: LiveTrafficSnapshot,
    settings: WiFiShareSettings,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    val isClientConnected = (tunnelState as? TunnelState.Connected)?.role == "CLIENT"

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Whole-Device VPN", onBack = onBack) }
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
                modifier = Modifier.widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = if (isVpnActive) semantic.success.copy(alpha = 0.6f) else semantic.glassBorder
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Android VpnService Tunnel", style = MaterialTheme.typography.titleLarge)
                            StatusPill(
                                label = if (isVpnActive) "VPN ACTIVE" else "VPN OFF",
                                color = if (isVpnActive) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = if (isClientConnected) {
                                "Captures device IPv4${if (settings.ipv6Enabled) "/IPv6" else ""} & DNS traffic through TUN interface, encrypts frames with AES-256-GCM, and forwards through the Host's NAT gateway."
                            } else {
                                "Connect as a Client to an authorized Host first before enabling Whole-Device VPN."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = if (isVpnActive) onStopVpn else onStartVpn,
                            enabled = isClientConnected || isVpnActive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("vpn_toggle_btn"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isVpnActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (isVpnActive) "Disconnect Whole-Device VPN" else "Request VPN & Connect")
                        }
                    }
                }

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tunnel Interface Parameters", style = MaterialTheme.typography.titleMedium)
                        KeyValueRow("Virtual IPv4 Address", (tunnelState as? TunnelState.Connected)?.assignedVirtualIp ?: "10.88.0.2/24")
                        KeyValueRow("Virtual IPv6 ULA", if (settings.ipv6Enabled) "fd88:5746::2/64" else "Disabled")
                        KeyValueRow("Tunneled DNS Server", if (settings.dnsLeakProtection) "10.88.0.1 (Forwarded via Host)" else "System Default")
                        KeyValueRow("Configured MTU", "${settings.vpnMtu} bytes")
                        KeyValueRow("Tunnel Latency (RTT)", "${traffic.tunnelLatencyMs} ms")
                        KeyValueRow("TX / RX Packets", "${traffic.txPackets} / ${traffic.rxPackets}")
                        KeyValueRow("Replay Guard Drops", "${traffic.droppedReplayPackets}")
                        KeyValueRow("Live Throughput", "↑ ${formatSpeed(traffic.uploadBps)}  ↓ ${formatSpeed(traffic.downloadBps)}")
                    }
                }
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
