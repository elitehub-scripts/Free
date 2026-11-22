package com.example.model

enum class SharingMode(val code: String, val label: String, val description: String) {
    BROWSER_ONLY(
        code = "browser",
        label = "Mode A · Built-in Browser",
        description = "Routes integrated WebView traffic through the Host's encrypted tunnel."
    ),
    BROWSER_AND_DOWNLOADS(
        code = "downloads",
        label = "Mode B · Browser & Downloads",
        description = "Adds encrypted file downloads and browser uploads over the Host tunnel."
    ),
    WHOLE_DEVICE_VPN(
        code = "vpn",
        label = "Mode C · Whole-Device VPN",
        description = "Captures supported device IPv4/IPv6 & DNS traffic via Android VpnService."
    )
}

enum class NetworkTransportType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OFFLINE
}

data class ActiveNetworkInfo(
    val transport: NetworkTransportType = NetworkTransportType.OFFLINE,
    val isConnected: Boolean = false,
    val isMetered: Boolean = false,
    val interfaceName: String = "Unavailable",
    val localIp: String = "—"
)

sealed interface TunnelState {
    data object Idle : TunnelState
    data class Handshaking(val stage: String) : TunnelState
    data class AwaitingHostApproval(val sessionCode: String, val hostLabel: String) : TunnelState
    data class Connected(
        val role: String, // HOST or CLIENT
        val mode: SharingMode,
        val transportPath: String, // "Direct P2P TCP"
        val peerLabel: String,
        val assignedVirtualIp: String,
        val startedAtMs: Long,
        val latencyMs: Long = 0L
    ) : TunnelState
    data class Reconnecting(val attempt: Int, val reason: String) : TunnelState
    data class Error(val errorCode: String, val message: String, val recoveryHint: String) : TunnelState
}

data class SharingCodeState(
    val sessionId: Long = 0L,
    val code: String = "",
    val qrPayload: String = "",
    val inviteUri: String = "",
    val expiresAtEpochSec: Long = 0L,
    val hostPublicKeyB64: String = "",
    val listenHost: String = "",
    val listenPort: Int = 51821,
    val stunEndpoint: String = "",
    val permittedModes: Set<SharingMode> = setOf(
        SharingMode.BROWSER_ONLY,
        SharingMode.BROWSER_AND_DOWNLOADS,
        SharingMode.WHOLE_DEVICE_VPN
    )
)

data class PendingJoinRequest(
    val connectionId: Long,
    val clientDeviceId: String,
    val clientName: String,
    val requestedMode: SharingMode,
    val clientPublicKeyB64: String,
    val clientEndpoint: String,
    val requestedAt: String
)

data class ConnectedPeer(
    val connectionId: Long,
    val clientDeviceId: String,
    val deviceName: String,
    val virtualIp: String,
    val mode: SharingMode,
    val transportPath: String,
    val connectedAtMs: Long,
    val bytesUploaded: Long,
    val bytesDownloaded: Long,
    val uploadBps: Long,
    val downloadBps: Long
)

data class LiveTrafficSnapshot(
    val uploadBps: Long = 0L,
    val downloadBps: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val txPackets: Long = 0L,
    val rxPackets: Long = 0L,
    val droppedReplayPackets: Long = 0L,
    val tunnelLatencyMs: Long = 0L,
    val speedHistoryUpload: List<Long> = emptyList(),
    val speedHistoryDownload: List<Long> = emptyList()
)

data class HostPublicInfo(
    val sessionId: Long,
    val hostDisplayName: String,
    val networkType: String,
    val hostPublicKeyB64: String,
    val hostIp: String,
    val hostPort: Int,
    val stunEndpoint: String,
    val permittedModes: Set<SharingMode>,
    val maxDevices: Int,
    val activeDevices: Int,
    val expiresAtEpochSec: Long
)

data class DiscoveredHostPeer(
    val serviceName: String,
    val sharingCode: String,
    val hostName: String,
    val hostIp: String,
    val hostPort: Int,
    val hostPublicKeyB64: String,
    val networkType: String,
    val permittedModes: Set<SharingMode>
)

data class AuthUserSession(
    val isSignedIn: Boolean = false,
    val userId: Long = -1L,
    val email: String = "",
    val displayName: String = "",
    val deviceUuid: String = ""
)

data class DiagnosticProbeResult(
    val name: String,
    val passed: Boolean?,
    val detail: String
)

sealed interface AiDiagnosticUiState {
    data object Idle : AiDiagnosticUiState
    data object Loading : AiDiagnosticUiState
    data class Streaming(val thoughts: String, val answer: String) : AiDiagnosticUiState
    data class Success(val thoughts: String, val answer: String) : AiDiagnosticUiState
    data class Error(val message: String) : AiDiagnosticUiState
}
