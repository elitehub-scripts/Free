package com.example.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.SavedPeerEntity
import com.example.model.ActiveNetworkInfo
import com.example.model.AiDiagnosticUiState
import com.example.model.AuthUserSession
import com.example.model.ConnectedPeer
import com.example.model.DiagnosticProbeResult
import com.example.model.DiscoveredHostPeer
import com.example.model.HostPublicInfo
import com.example.model.LiveTrafficSnapshot
import com.example.model.NearbyOfflineNeed
import com.example.model.OfflineP2pState
import com.example.model.PendingJoinRequest
import com.example.model.SharingCodeState
import com.example.model.SharingMode
import com.example.model.TunnelState
import com.example.services.WiFiShareRuntime
import com.example.settings.WiFiShareSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient

/**
 * UI ViewModel delegating directly to the process-scoped WiFiShareRuntime.
 * Clearing this ViewModel (e.g. configuration change or backgrounding) never interrupts
 * active Host sharing, Client connections, or offline peer discovery.
 */
class WiFiShareViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = WiFiShareRuntime.getInstance(application)

    val localRepo = runtime.localRepo
    val settingsStore = runtime.settingsStore
    val browser = runtime.browser
    val downloadEngine = runtime.downloadEngine

    val settings: StateFlow<WiFiShareSettings> = runtime.settings
    val activeNetwork: StateFlow<ActiveNetworkInfo> = runtime.activeNetwork
    val liveTraffic: StateFlow<LiveTrafficSnapshot> = runtime.liveTraffic
    val isVpnActive: StateFlow<Boolean> = runtime.isVpnActive
    val isHostServiceRunning: StateFlow<Boolean> = runtime.isHostServiceRunning
    val isClientServiceRunning: StateFlow<Boolean> = runtime.isClientServiceRunning
    val offlineP2pState: StateFlow<OfflineP2pState> = runtime.offlineP2pState

    val discoveredHosts: StateFlow<List<DiscoveredHostPeer>> = runtime.discoveredHosts
    val isNsdScanning: StateFlow<Boolean> = runtime.isNsdScanning
    val nearbyOfflineNeeds: StateFlow<List<NearbyOfflineNeed>> = runtime.nearbyOfflineNeeds

    val savedHosts = runtime.savedHosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savedClients = runtime.savedClients.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hostSessions = runtime.hostSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = runtime.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = runtime.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = runtime.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trafficRecords = runtime.trafficRecords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tunnelEvents = runtime.tunnelEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyUpload = runtime.dailyUpload.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val dailyDownload = runtime.dailyDownload.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthlyUpload = runtime.monthlyUpload.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthlyDownload = runtime.monthlyDownload.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val aiUiState: StateFlow<AiDiagnosticUiState> = runtime.aiUiState

    val authSession: StateFlow<AuthUserSession> = runtime.authSession
    val authBusy: StateFlow<Boolean> = runtime.authBusy
    val authMessage: StateFlow<String?> = runtime.authMessage
    val tunnelState: StateFlow<TunnelState> = runtime.tunnelState
    val sharingCodeState: StateFlow<SharingCodeState> = runtime.sharingCodeState
    val pendingRequests: StateFlow<List<PendingJoinRequest>> = runtime.pendingRequests
    val connectedPeers: StateFlow<List<ConnectedPeer>> = runtime.connectedPeers
    val clientSelectedMode: StateFlow<SharingMode> = runtime.clientSelectedMode
    val joinedHostInfo: StateFlow<HostPublicInfo?> = runtime.joinedHostInfo
    val pendingVpnPermissionEvent: StateFlow<Intent?> = runtime.pendingVpnPermissionEvent
    val diagnosticProbes: StateFlow<List<DiagnosticProbeResult>> = runtime.diagnosticProbes
    val statusBannerMessage: StateFlow<String?> = runtime.statusBannerMessage

    fun clearAuthMessage() = runtime.clearAuthMessage()
    fun clearStatusBanner() = runtime.clearStatusBanner()
    fun completeOnboarding() = runtime.completeOnboarding()

    fun login(email: String, password: String) = runtime.login(email, password)
    fun register(displayName: String, email: String, password: String) = runtime.register(displayName, email, password)
    fun signOut() = runtime.signOut()

    fun startHostSharing() = runtime.startHostSharing()
    fun regenerateSharingCode() = runtime.regenerateSharingCode()
    fun revokeSharingCodeAndStop() = runtime.revokeSharingCodeAndStop()

    fun startHostOfflineWifiDirectGroup() = runtime.startHostOfflineWifiDirectGroup()
    fun scanOfflineWifiDirectPeers() = runtime.scanOfflineWifiDirectPeers()
    fun connectOfflineWifiDirectPeer(deviceAddress: String) = runtime.connectOfflineWifiDirectPeer(deviceAddress)
    fun stopOfflineWifiDirect() = runtime.stopOfflineWifiDirect()

    fun approvePeerRequest(
        request: PendingJoinRequest,
        approve: Boolean,
        markTrusted: Boolean = false
    ) = runtime.approvePeerRequest(request, approve, markTrusted)

    fun setSavedPeerTrusted(peer: SavedPeerEntity, trusted: Boolean) =
        runtime.setSavedPeerTrusted(peer, trusted)

    fun removeSavedPeer(peerId: Long) = runtime.removeSavedPeer(peerId)
    fun disconnectPeer(connectionId: Long) = runtime.disconnectPeer(connectionId)
    fun stopHostSharing() = runtime.stopHostSharing()

    fun startNearbyHostScan() = runtime.startNearbyHostScan()
    fun stopNearbyHostScan() = runtime.stopNearbyHostScan()
    fun selectClientMode(mode: SharingMode) = runtime.selectClientMode(mode)

    fun requestClientJoin(
        rawCodeOrQr: String,
        manualHostIp: String = "",
        manualHostPort: Int? = null
    ) = runtime.requestClientJoin(rawCodeOrQr, manualHostIp, manualHostPort)

    fun requestInternetOffline(
        note: String = "",
        manualHostIp: String = "",
        manualHostPort: Int? = null
    ) = runtime.requestInternetOffline(note, manualHostIp, manualHostPort)

    fun requestOrStartVpn() = runtime.requestOrStartVpn()
    fun consumeVpnPermissionEvent() = runtime.consumeVpnPermissionEvent()
    fun onVpnPermissionGranted() = runtime.onVpnPermissionGranted()
    fun onVpnPermissionDenied() = runtime.onVpnPermissionDenied()
    fun stopVpnOnly() = runtime.stopVpnOnly()
    fun disconnectClient() = runtime.disconnectClient()

    fun getTunnelOkHttpClientOrNull(): OkHttpClient? = runtime.getTunnelOkHttpClientOrNull()
    fun isDownloadAllowedBySession(): Boolean = runtime.isDownloadAllowedBySession()

    fun runNetworkDiagnosticsProbes() = runtime.runNetworkDiagnosticsProbes()
    fun askAiDiagnostics(query: String) = runtime.askAiDiagnostics(query)
}
