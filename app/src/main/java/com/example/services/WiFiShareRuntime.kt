package com.example.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.authentication.LocalPasswordHasher
import com.example.authentication.SecureCredentialStore
import com.example.browser.BrowserStateHolder
import com.example.database.LocalAccountEntity
import com.example.database.SavedHostSessionEntity
import com.example.database.SavedPeerEntity
import com.example.database.WiFiShareDatabase
import com.example.database.WiFiShareLocalRepository
import com.example.diagnostics.AiDiagnosticsService
import com.example.downloads.DownloadManagerEngine
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
import com.example.networking.ConnectivityMonitor
import com.example.networking.crypto.DecryptedTunnelFrame
import com.example.networking.crypto.TunnelCrypto
import com.example.networking.proxy.LocalTunnelStreamMux
import com.example.networking.stun.StunClient
import com.example.networking.transport.DirectClientConnector
import com.example.networking.transport.DirectHostGatewayServer
import com.example.networking.transport.DirectSocketTunnelTransport
import com.example.networking.transport.DirectTcpProbe
import com.example.networking.transport.LocalBeaconDiscovery
import com.example.networking.transport.LocalNsdDiscovery
import com.example.networking.transport.OfflineWifiDirectManager
import com.example.networking.transport.TrafficAccounting
import com.example.networking.transport.TunnelPeerTransport
import com.example.networking.transport.TunnelWireCodec
import com.example.qr.QrCodeGenerator
import com.example.settings.SettingsDataStore
import com.example.settings.WiFiShareSettings
import com.example.vpn.HostGatewayForwarder
import com.example.vpn.VpnRuntimeBridge
import com.example.vpn.WiFiShareVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * Application-scoped runtime engine for WiFiShare.
 * Lives outside Activity/ViewModel lifecycles so both Host sharing ("give connection")
 * and Client tunnels ("get connection") continue running smoothly in the background and offline.
 */
class WiFiShareRuntime private constructor(context: Context) {

    private val appContext = context.applicationContext
    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db = WiFiShareDatabase.getInstance(appContext)
    val localRepo = WiFiShareLocalRepository(db)
    val settingsStore = SettingsDataStore(appContext)
    val credentialStore = SecureCredentialStore(appContext)
    val connectivityMonitor = ConnectivityMonitor(appContext)
    val nsdDiscovery = LocalNsdDiscovery(appContext)
    val beaconDiscovery = LocalBeaconDiscovery(runtimeScope, appContext)
    val browser = BrowserStateHolder()
    val aiDiagnostics = AiDiagnosticsService()
    val trafficAccounting = TrafficAccounting()

    val wifiDirectManager: OfflineWifiDirectManager = OfflineWifiDirectManager(appContext) { isOwner, ownerIp ->
        onWifiDirectGroupUpdated(isOwner, ownerIp)
    }

    val settings: StateFlow<WiFiShareSettings> = settingsStore.settingsFlow
        .stateIn(runtimeScope, SharingStarted.Eagerly, WiFiShareSettings())

    val activeNetwork: StateFlow<ActiveNetworkInfo> = connectivityMonitor.networkInfo
    val liveTraffic: StateFlow<LiveTrafficSnapshot> = trafficAccounting.snapshot
    val isVpnActive: StateFlow<Boolean> = VpnRuntimeBridge.isVpnRunning
    val isHostServiceRunning: StateFlow<Boolean> = HostServiceBridge.isRunning
    val isClientServiceRunning: StateFlow<Boolean> = ClientServiceBridge.isRunning
    val offlineP2pState: StateFlow<OfflineP2pState> = wifiDirectManager.state

    private val _tcpDiscoveredHosts = MutableStateFlow<List<DiscoveredHostPeer>>(emptyList())

    val discoveredHosts: StateFlow<List<DiscoveredHostPeer>> = combine(
        nsdDiscovery.discoveredHosts,
        beaconDiscovery.discoveredPeers,
        _tcpDiscoveredHosts
    ) { nsd, beacon, tcp ->
        val merged = LinkedHashMap<String, DiscoveredHostPeer>()
        (nsd + beacon + tcp).forEach { peer ->
            val key = "${peer.sharingCode}|${peer.hostIp}:${peer.hostPort}"
            merged[key] = peer
        }
        merged.values.toList()
    }.stateIn(runtimeScope, SharingStarted.Eagerly, emptyList())

    val isNsdScanning: StateFlow<Boolean> = nsdDiscovery.isDiscovering
    val nearbyOfflineNeeds: StateFlow<List<NearbyOfflineNeed>> = beaconDiscovery.nearbyOfflineNeeds

    val savedHosts = localRepo.savedHosts
    val savedClients = localRepo.savedClients
    val hostSessions = localRepo.hostSessions
    val bookmarks = localRepo.bookmarks
    val history = localRepo.history
    val downloads = localRepo.downloads
    val trafficRecords = localRepo.trafficRecords
    val tunnelEvents = localRepo.tunnelEvents

    val dailyUpload = localRepo.observeDailyUpload()
    val dailyDownload = localRepo.observeDailyDownload()
    val monthlyUpload = localRepo.observeMonthlyUpload()
    val monthlyDownload = localRepo.observeMonthlyDownload()
    val aiUiState: StateFlow<AiDiagnosticUiState> = aiDiagnostics.uiState

    private val _authSession = MutableStateFlow(loadStoredAuthSession())
    val authSession: StateFlow<AuthUserSession> = _authSession.asStateFlow()

    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy.asStateFlow()

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _sharingCodeState = MutableStateFlow(SharingCodeState())
    val sharingCodeState: StateFlow<SharingCodeState> = _sharingCodeState.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<PendingJoinRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PendingJoinRequest>> = _pendingRequests.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val connectedPeers: StateFlow<List<ConnectedPeer>> = _connectedPeers.asStateFlow()

    private val _clientSelectedMode = MutableStateFlow(SharingMode.BROWSER_ONLY)
    val clientSelectedMode: StateFlow<SharingMode> = _clientSelectedMode.asStateFlow()

    private val _joinedHostInfo = MutableStateFlow<HostPublicInfo?>(null)
    val joinedHostInfo: StateFlow<HostPublicInfo?> = _joinedHostInfo.asStateFlow()

    private val _pendingVpnPermissionEvent = MutableStateFlow<Intent?>(null)
    val pendingVpnPermissionEvent: StateFlow<Intent?> = _pendingVpnPermissionEvent.asStateFlow()

    private val _diagnosticProbes = MutableStateFlow<List<DiagnosticProbeResult>>(emptyList())
    val diagnosticProbes: StateFlow<List<DiagnosticProbeResult>> = _diagnosticProbes.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    // Active runtime objects (retained across UI backgrounding & configuration changes)
    private var ephemeralKeyPair: KeyPair? = null
    private var hostGatewayServer: DirectHostGatewayServer? = null
    private var activeHostSessionDbId: Long = 0L
    private var activeClientConnector: DirectClientConnector? = null
    private var activeClientTransport: TunnelPeerTransport? = null
    private var activeClientMux: LocalTunnelStreamMux? = null
    private var hostStartJob: Job? = null
    private var nearbyTcpProbeJob: Job? = null
    private var clientConnectJob: Job? = null
    private var clientReconnectJob: Job? = null
    private var lastClientAttempt: ClientJoinAttempt? = null
    @Volatile
    private var manualClientDisconnect = false
    @Volatile
    private var trustedClientUuids: Set<String> = emptySet()

    private val hostForwarders = ConcurrentHashMap<Long, HostGatewayForwarder>()
    private var telemetryLoopJob: Job? = null
    private var sessionStartedAtMs: Long = 0L

    private data class ClientJoinAttempt(
        val rawCodeOrQr: String,
        val manualHostIp: String,
        val manualHostPort: Int?,
        val isOfflineRequest: Boolean = false,
        val requestNote: String = ""
    )

    val downloadEngine = DownloadManagerEngine(
        context = appContext,
        repository = localRepo,
        scope = runtimeScope,
        tunnelClientProvider = { activeClientMux?.createTunnelOkHttpClient() }
    )

    init {
        connectivityMonitor.start()
        HostServiceBridge.onStopRequested = { stopHostSharing() }
        HostServiceBridge.onApprovePeerRequested = { connId -> approvePeerById(connId, approve = true) }
        HostServiceBridge.onRejectPeerRequested = { connId -> approvePeerById(connId, approve = false) }
        ClientServiceBridge.onDisconnectRequested = { disconnectClient() }
        VpnRuntimeBridge.onVpnStoppedExternally = {
            if ((_tunnelState.value as? TunnelState.Connected)?.role == "CLIENT") {
                logEvent("INFO", "VPN", "Whole-device VPN interface closed")
            }
        }
        runtimeScope.launch {
            refreshTrustedClientUuids()
        }
        runtimeScope.launch {
            activeNetwork.collect { net ->
                syncHostNetworkChanges(net)
            }
        }
        runtimeScope.launch {
            offlineP2pState.collect { p2p ->
                if ((_tunnelState.value as? TunnelState.Connected)?.role == "HOST") {
                    refreshHostInviteAndP2pService(p2p)
                }
            }
        }
        runtimeScope.launch {
            settings.collect {
                if ((_tunnelState.value as? TunnelState.Connected)?.role == "HOST") {
                    refreshHostInviteAndP2pService(offlineP2pState.value)
                }
            }
        }
        startTelemetrySampler()
    }

    private suspend fun refreshTrustedClientUuids() {
        trustedClientUuids = runCatching { localRepo.getTrustedClientUuids() }.getOrDefault(emptySet())
    }

    private fun loadStoredAuthSession(): AuthUserSession {
        val token = credentialStore.getAuthToken()
        val uuid = credentialStore.getOrCreateDeviceUuid()
        return if (!token.isNullOrBlank() && credentialStore.getUserId() > 0L) {
            AuthUserSession(
                isSignedIn = true,
                userId = credentialStore.getUserId(),
                email = credentialStore.getUserEmail().orEmpty(),
                displayName = credentialStore.getDisplayName().orEmpty(),
                deviceUuid = uuid
            )
        } else {
            AuthUserSession(isSignedIn = false, deviceUuid = uuid)
        }
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun completeOnboarding() {
        runtimeScope.launch { settingsStore.setOnboardingCompleted(true) }
    }

    // =========================================================================
    // IN-APP LOCAL AUTHENTICATION (ROOM + KEYSTORE)
    // =========================================================================

    fun login(email: String, password: String) {
        val cleanEmail = email.trim().lowercase(Locale.US)
        if (cleanEmail.isBlank() || !cleanEmail.contains("@") || password.length < 6) {
            _authMessage.value = "Enter a valid email and a password of at least 6 characters."
            return
        }
        runtimeScope.launch {
            _authBusy.value = true
            _authMessage.value = null
            try {
                val account = localRepo.findAccountByEmail(cleanEmail)
                if (account == null || !LocalPasswordHasher.verifyPassword(password, account.passwordSaltHex, account.passwordHashHex)) {
                    _authMessage.value = "Invalid email or password for local account on this device."
                    return@launch
                }
                localRepo.touchAccountLogin(account.id)
                credentialStore.saveAuthToken("local-${UUID.randomUUID()}")
                credentialStore.saveUserProfile(account.id, account.email, account.displayName)
                _authSession.value = loadStoredAuthSession()
                logEvent("INFO", "Auth", "Signed in locally as ${account.email}")
            } catch (e: Exception) {
                Log.e(TAG, "Local login failed", e)
                _authMessage.value = "Failed to unlock local profile: ${e.localizedMessage}"
            } finally {
                _authBusy.value = false
            }
        }
    }

    fun register(displayName: String, email: String, password: String) {
        val cleanName = displayName.trim()
        val cleanEmail = email.trim().lowercase(Locale.US)
        if (cleanName.isBlank() || !cleanEmail.contains("@") || password.length < 8) {
            _authMessage.value = "Provide display name, valid email, and a password of at least 8 characters."
            return
        }
        runtimeScope.launch {
            _authBusy.value = true
            _authMessage.value = null
            try {
                if (localRepo.findAccountByEmail(cleanEmail) != null) {
                    _authMessage.value = "An account with $cleanEmail is already saved on this device. Please sign in."
                    return@launch
                }
                val hashed = LocalPasswordHasher.hashPassword(password)
                val id = localRepo.createLocalAccount(
                    LocalAccountEntity(
                        email = cleanEmail,
                        displayName = cleanName,
                        passwordSaltHex = hashed.saltHex,
                        passwordHashHex = hashed.hashHex
                    )
                )
                credentialStore.saveAuthToken("local-${UUID.randomUUID()}")
                credentialStore.saveUserProfile(id, cleanEmail, cleanName)
                _authSession.value = loadStoredAuthSession()
                logEvent("INFO", "Auth", "Created local account $cleanEmail saved in app database")
            } catch (e: Exception) {
                Log.e(TAG, "Local registration failed", e)
                _authMessage.value = "Could not save local account: ${e.localizedMessage}"
            } finally {
                _authBusy.value = false
            }
        }
    }

    fun signOut() {
        stopHostSharing()
        disconnectClient()
        credentialStore.clearSession()
        _authSession.value = loadStoredAuthSession()
        logEvent("INFO", "Auth", "Signed out and cleared local session token")
    }

    // =========================================================================
    // HOST MODE (BACKGROUND + OFFLINE ACCEPTANCE READY)
    // =========================================================================

    fun startHostSharing() {
        if (!_authSession.value.isSignedIn) {
            _statusBannerMessage.value = "Sign in to your local WiFiShare profile first."
            return
        }
        if (hostStartJob?.isActive == true) return

        hostStartJob = runtimeScope.launch {
            try {
                hostGatewayServer?.stop()
                hostGatewayServer = null
                hostForwarders.values.forEach { it.close() }
                hostForwarders.clear()
                _pendingRequests.value = emptyList()
                _connectedPeers.value = emptyList()

                connectivityMonitor.refresh()
                refreshTrustedClientUuids()
                val net = activeNetwork.value
                val p2p = wifiDirectManager.state.value

                _tunnelState.value = TunnelState.Handshaking(
                    if (net.isConnected) "Opening background Host gateway..."
                    else "Opening Host gateway in Offline Local Accept mode..."
                )

                val cfg = settings.value
                val kp = TunnelCrypto.generateEphemeralKeyPair().also { ephemeralKeyPair = it }
                val hostPubB64 = TunnelCrypto.encodePublicKey(kp.public)
                val sharingCode = generateSharingCode()
                val expiresAtSec = (System.currentTimeMillis() / 1000L) + (cfg.sessionExpiryMinutes * 60L)

                val initialStunEndpoint = if (net.hasLocalLink || p2p.isGroupActive) {
                    "LAN / Direct P2P Ready"
                } else {
                    "Offline Standby"
                }

                val permittedProvider: () -> Set<SharingMode> = {
                    val cur = settings.value
                    buildSet {
                        if (cur.allowBrowserMode) add(SharingMode.BROWSER_ONLY)
                        if (cur.allowDownloadsMode) add(SharingMode.BROWSER_AND_DOWNLOADS)
                        if (cur.allowVpnMode) add(SharingMode.WHOLE_DEVICE_VPN)
                    }.ifEmpty { setOf(SharingMode.BROWSER_ONLY) }
                }

                val hostName = _authSession.value.displayName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }

                val server = DirectHostGatewayServer(
                    sharingCode = sharingCode,
                    hostDisplayName = hostName,
                    networkType = if (net.isConnected) net.transport.name else "OFFLINE_LOCAL",
                    hostPublicKeyB64 = hostPubB64,
                    stunEndpoint = initialStunEndpoint,
                    permittedModesProvider = permittedProvider,
                    maxDevicesProvider = { settings.value.maxConnectedDevices },
                    activeDevicesCountProvider = { _connectedPeers.value.size },
                    expiresAtEpochSec = expiresAtSec,
                    scope = runtimeScope,
                    onJoinRequested = { req ->
                        _pendingRequests.value = _pendingRequests.value.filterNot { it.connectionId == req.connectionId } + req
                        HostServiceBridge.pendingCount = _pendingRequests.value.size
                        HostServiceBridge.notifyIncomingRequest(appContext, req)
                        logEvent("INFO", "Host", "Incoming join request from ${req.clientName} (${req.requestedMode.label})")
                    },
                    onPendingClosed = { connId ->
                        _pendingRequests.value = _pendingRequests.value.filterNot { it.connectionId == connId }
                        HostServiceBridge.pendingCount = _pendingRequests.value.size
                        HostServiceBridge.dismissRequestNotification(appContext, connId)
                    },
                    onServerError = { err ->
                        logEvent("ERROR", "Host", "Gateway socket error: ${err.message}")
                    },
                    autoAcceptTrustedProvider = { settings.value.autoAcceptTrustedPeers },
                    trustedDeviceUuidsProvider = { trustedClientUuids },
                    onAutoApproveRequested = { req ->
                        val isTrusted = req.clientDeviceId in trustedClientUuids
                        val label = if (settings.value.autoAcceptOpenRequests && !isTrusted) {
                            "Auto-accepting nearby connection from ${req.clientName}"
                        } else {
                            "Auto-accepting trusted offline peer ${req.clientName}"
                        }
                        logEvent("INFO", "Host", label)
                        approvePeerRequest(req, approve = true, markTrusted = isTrusted && settings.value.autoAcceptTrustedPeers)
                    },
                    networkTypeProvider = {
                        val cur = activeNetwork.value
                        if (cur.isConnected) cur.transport.name else "OFFLINE_LOCAL"
                    },
                    allowOpenOfflineRequestsProvider = { settings.value.allowOpenOfflineRequests },
                    autoAcceptOpenRequestsProvider = { settings.value.autoAcceptOpenRequests }
                )

                val portRes = server.start(cfg.directListenPort)
                if (portRes.isFailure) {
                    val err = portRes.exceptionOrNull()?.localizedMessage ?: "Port bind failed"
                    _tunnelState.value = TunnelState.Error(
                        "HOST_BIND_FAILED",
                        "Could not open local Host gateway port: $err",
                        "Try changing the Direct Gateway Listen Port in Settings."
                    )
                    return@launch
                }

                val listenPort = portRes.getOrThrow()
                hostGatewayServer = server
                val preferredLanIp = net.localAddresses
                    .firstOrNull {
                        val lower = it.substringBefore(':').lowercase(Locale.US)
                        lower.startsWith("p2p") || lower.startsWith("ap") || lower.startsWith("swlan") || lower.startsWith("wlan")
                    }?.substringAfter(':')
                val advertiseIp = preferredLanIp
                    ?: p2p.groupOwnerIp.takeIf { it.isNotBlank() }
                    ?: net.localIp.takeIf { it != "—" && it.isNotBlank() }
                    ?: "127.0.0.1"
                val inviteUri = QrCodeGenerator.buildInviteUri(
                    code = sharingCode,
                    hostIp = advertiseIp,
                    port = listenPort,
                    hostPublicKeyB64 = hostPubB64,
                    p2pIp = p2p.groupOwnerIp.ifBlank { if (p2p.isGroupActive) "192.168.49.1" else "" },
                    p2pSsid = p2p.groupSsid,
                    p2pPassphrase = p2p.groupPassphrase
                )

                val dbSessionId = localRepo.recordHostSessionStart(
                    SavedHostSessionEntity(
                        sharingCode = sharingCode,
                        listenPort = listenPort,
                        localIp = advertiseIp,
                        stunEndpoint = initialStunEndpoint,
                        permittedModesCsv = permittedProvider().joinToString(",") { it.code },
                        maxDevices = cfg.maxConnectedDevices
                    )
                )
                activeHostSessionDbId = dbSessionId

                if (cfg.enableNsdDiscovery) {
                    runCatching {
                        nsdDiscovery.registerHostService(
                            sharingCode = sharingCode,
                            hostName = hostName,
                            port = listenPort,
                            networkType = if (net.isConnected) net.transport.name else "OFFLINE",
                            hostPublicKeyB64 = hostPubB64,
                            permittedModes = permittedProvider()
                        )
                    }
                }
                beaconDiscovery.startAdvertising(
                    sharingCode = sharingCode,
                    hostName = hostName,
                    port = listenPort,
                    networkTypeProvider = {
                        val cur = activeNetwork.value
                        if (cur.isConnected) cur.transport.name else "OFFLINE"
                    },
                    hostPublicKeyB64 = hostPubB64,
                    permittedModesProvider = permittedProvider
                )
                val seenOfflineNeedIds = ConcurrentHashMap.newKeySet<String>()
                beaconDiscovery.startListeningForOfflineNeeds { need, senderAddr ->
                    val curNet = activeNetwork.value
                    beaconDiscovery.sendDirectHostReply(
                        targetAddress = senderAddr,
                        sharingCode = sharingCode,
                        hostName = hostName,
                        port = listenPort,
                        networkType = if (curNet.isConnected) curNet.transport.name else "OFFLINE",
                        hostPublicKeyB64 = hostPubB64,
                        permittedModes = permittedProvider()
                    )
                    if (seenOfflineNeedIds.add(need.deviceUuid)) {
                        logEvent(
                            "INFO",
                            "Host",
                            "Nearby offline internet request from ${need.deviceName} (${need.senderIp}): ${need.note.ifBlank { need.requestedMode.label }}"
                        )
                    }
                }

                wifiDirectManager.registerHostP2pService(
                    sharingCode = sharingCode,
                    hostPort = listenPort,
                    p2pSsid = p2p.groupSsid,
                    p2pPassphrase = p2p.groupPassphrase,
                    allowOpenOffline = cfg.allowOpenOfflineRequests,
                    autoAcceptOpen = cfg.autoAcceptOpenRequests
                )
                // If completely offline without a router/Hotspot, auto-start an Offline Wi-Fi Direct group if permitted
                if (!net.isConnected && !p2p.isGroupActive &&
                    OfflineWifiDirectManager.hasRequiredPermission(appContext) &&
                    wifiDirectManager.isWifiEnabled()
                ) {
                    wifiDirectManager.createOfflineGroup()
                }

                _sharingCodeState.value = SharingCodeState(
                    sessionId = dbSessionId,
                    code = sharingCode,
                    qrPayload = inviteUri,
                    inviteUri = inviteUri,
                    expiresAtEpochSec = expiresAtSec,
                    hostPublicKeyB64 = hostPubB64,
                    listenHost = advertiseIp,
                    listenPort = listenPort,
                    stunEndpoint = initialStunEndpoint,
                    permittedModes = permittedProvider()
                )

                sessionStartedAtMs = System.currentTimeMillis()
                trafficAccounting.reset()
                HostServiceBridge.activeCode = sharingCode
                HostServiceBridge.connectedCount = 0
                HostServiceBridge.pendingCount = 0
                HostServiceBridge.egressStatusLabel = if (net.isConnected) net.transport.name else "Offline Local"
                runCatching { startHostForegroundService() }

                _tunnelState.value = TunnelState.Connected(
                    role = "HOST",
                    mode = SharingMode.WHOLE_DEVICE_VPN,
                    transportPath = "Direct P2P ($advertiseIp:$listenPort)",
                    peerLabel = if (net.isConnected) "Background (${net.transport.name})" else "Offline Accept Ready",
                    assignedVirtualIp = "10.88.0.1",
                    startedAtMs = sessionStartedAtMs
                )
                logEvent(
                    "INFO",
                    "Host",
                    if (net.isConnected) {
                        "Started background Host session $sharingCode on $advertiseIp:$listenPort"
                    } else {
                        "Started offline Host session $sharingCode on $advertiseIp:$listenPort (ready to accept local/offline connections)"
                    }
                )

                // Run optional STUN reflexive endpoint discovery asynchronously so Host UI starts immediately
                if (net.isConnected) {
                    runtimeScope.launch {
                        val stunRes = runCatching {
                            StunClient.discoverPublicEndpoint(cfg.stunHost, cfg.stunPort)
                        }.getOrNull()
                        val ep = stunRes?.getOrNull()?.let { "${it.publicIp}:${it.publicPort}" } ?: return@launch
                        if (hostGatewayServer === server && _sharingCodeState.value.code == sharingCode) {
                            _sharingCodeState.value = _sharingCodeState.value.copy(stunEndpoint = ep)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Host sharing", e)
                _tunnelState.value = TunnelState.Error(
                    "HOST_START_FAILED",
                    "Could not start Host gateway: ${e.localizedMessage ?: "Unexpected error"}",
                    "Check permissions and network status, then tap Start again."
                )
            }
        }
    }

    private fun refreshHostInviteAndP2pService(p2p: OfflineP2pState) {
        val curCode = _sharingCodeState.value
        if (curCode.code.isBlank() || hostGatewayServer?.isRunning != true) return
        val cfg = settings.value
        val p2pIp = p2p.groupOwnerIp.ifBlank { if (p2p.isGroupActive) "192.168.49.1" else "" }
        val updatedInvite = QrCodeGenerator.buildInviteUri(
            code = curCode.code,
            hostIp = curCode.listenHost.ifBlank { p2pIp.ifBlank { "127.0.0.1" } },
            port = curCode.listenPort,
            hostPublicKeyB64 = curCode.hostPublicKeyB64,
            p2pIp = p2pIp,
            p2pSsid = p2p.groupSsid,
            p2pPassphrase = p2p.groupPassphrase
        )
        if (updatedInvite != curCode.inviteUri) {
            _sharingCodeState.value = curCode.copy(
                inviteUri = updatedInvite,
                qrPayload = updatedInvite
            )
        }
        wifiDirectManager.registerHostP2pService(
            sharingCode = curCode.code,
            hostPort = curCode.listenPort,
            p2pSsid = p2p.groupSsid,
            p2pPassphrase = p2p.groupPassphrase,
            allowOpenOffline = cfg.allowOpenOfflineRequests,
            autoAcceptOpen = cfg.autoAcceptOpenRequests
        )
    }

    private fun syncHostNetworkChanges(net: ActiveNetworkInfo) {
        val currentTunnel = _tunnelState.value as? TunnelState.Connected ?: return
        if (currentTunnel.role != "HOST") return

        HostServiceBridge.egressStatusLabel = if (net.isConnected) net.transport.name else "Offline Local"
        val curCode = _sharingCodeState.value
        val p2p = wifiDirectManager.state.value
        val p2pIp = p2p.groupOwnerIp.takeIf { it.isNotBlank() }
        val updatedIp = net.localIp.takeIf { it != "—" && it.isNotBlank() } ?: p2pIp

        if (curCode.code.isNotBlank() && updatedIp != null && curCode.listenHost == "127.0.0.1") {
            val invite = QrCodeGenerator.buildInviteUri(
                code = curCode.code,
                hostIp = updatedIp,
                port = curCode.listenPort,
                hostPublicKeyB64 = curCode.hostPublicKeyB64,
                p2pIp = p2p.groupOwnerIp.ifBlank { if (p2p.isGroupActive) "192.168.49.1" else "" },
                p2pSsid = p2p.groupSsid,
                p2pPassphrase = p2p.groupPassphrase
            )
            _sharingCodeState.value = curCode.copy(
                listenHost = updatedIp,
                inviteUri = invite,
                qrPayload = invite
            )
            _tunnelState.value = currentTunnel.copy(
                transportPath = "Direct P2P ($updatedIp:${curCode.listenPort})",
                peerLabel = if (net.isConnected) "Background (${net.transport.name})" else "Offline Accept Ready"
            )
        } else {
            _tunnelState.value = currentTunnel.copy(
                peerLabel = if (net.isConnected) "Background (${net.transport.name})" else "Offline Accept Ready"
            )
        }
    }

    private fun onWifiDirectGroupUpdated(isOwner: Boolean, ownerIp: String) {
        if (ownerIp.isBlank()) return
        connectivityMonitor.refresh()
        if (isOwner) {
            val curCode = _sharingCodeState.value
            val p2p = wifiDirectManager.state.value
            val currentTunnel = _tunnelState.value as? TunnelState.Connected
            if (currentTunnel?.role == "HOST" && curCode.code.isNotBlank()) {
                val hostIpToUse = if (curCode.listenHost == "127.0.0.1" || curCode.listenHost.isBlank()) {
                    ownerIp
                } else {
                    curCode.listenHost
                }
                val invite = QrCodeGenerator.buildInviteUri(
                    code = curCode.code,
                    hostIp = hostIpToUse,
                    port = curCode.listenPort,
                    hostPublicKeyB64 = curCode.hostPublicKeyB64,
                    p2pIp = ownerIp,
                    p2pSsid = p2p.groupSsid,
                    p2pPassphrase = p2p.groupPassphrase
                )
                _sharingCodeState.value = curCode.copy(
                    listenHost = hostIpToUse,
                    inviteUri = invite,
                    qrPayload = invite,
                    stunEndpoint = "Offline Wi-Fi Direct ($ownerIp)"
                )
                _tunnelState.value = currentTunnel.copy(
                    transportPath = "Wi-Fi Direct ($ownerIp:${curCode.listenPort})"
                )
                refreshHostInviteAndP2pService(p2p)
            }
            logEvent("INFO", "WiFiDirect", "Offline Wi-Fi Direct Group Owner active at $ownerIp")
        } else {
            logEvent("INFO", "WiFiDirect", "Connected offline to Wi-Fi Direct Group Owner at $ownerIp")
            _statusBannerMessage.value = "Offline Wi-Fi Direct linked to Host ($ownerIp)"
        }
    }

    fun startHostOfflineWifiDirectGroup() {
        wifiDirectManager.createOfflineGroup().onFailure { err ->
            _statusBannerMessage.value = err.message ?: "Could not start Wi-Fi Direct group."
        }
    }

    fun scanOfflineWifiDirectPeers() {
        nsdDiscovery.startDiscovery()
        beaconDiscovery.startListening()
        wifiDirectManager.startPeerDiscovery().onFailure { err ->
            _statusBannerMessage.value = err.message ?: "Wi-Fi Direct unavailable; scanning LAN beacons instead."
        }
    }

    fun connectOfflineWifiDirectPeer(deviceAddress: String) {
        wifiDirectManager.connectToPeer(deviceAddress).onFailure { err ->
            _statusBannerMessage.value = err.message ?: "Could not connect to Wi-Fi Direct peer."
        }
    }

    fun stopOfflineWifiDirect() {
        wifiDirectManager.stopAll()
    }

    fun regenerateSharingCode() {
        stopHostSharing()
        startHostSharing()
    }

    fun revokeSharingCodeAndStop() {
        stopHostSharing()
    }

    fun approvePeerById(connectionId: Long, approve: Boolean, markTrusted: Boolean = false) {
        val req = _pendingRequests.value.firstOrNull { it.connectionId == connectionId } ?: return
        approvePeerRequest(req, approve, markTrusted)
    }

    fun approvePeerRequest(
        request: PendingJoinRequest,
        approve: Boolean,
        markTrusted: Boolean = false
    ) {
        val server = hostGatewayServer ?: return
        val sessionState = _sharingCodeState.value
        val kp = ephemeralKeyPair ?: return

        HostServiceBridge.dismissRequestNotification(appContext, request.connectionId)

        runtimeScope.launch {
            _pendingRequests.value = _pendingRequests.value.filterNot { it.connectionId == request.connectionId }
            HostServiceBridge.pendingCount = _pendingRequests.value.size

            if (!approve) {
                server.rejectPendingClient(request.connectionId, "Host declined your connection request.")
                localRepo.savePeerRecord(
                    SavedPeerEntity(
                        role = "CLIENT",
                        deviceUuid = request.clientDeviceId,
                        peerName = request.clientName,
                        hostIp = request.clientEndpoint,
                        hostPort = sessionState.listenPort,
                        sharingCode = sessionState.code,
                        modeCode = request.requestedMode.code,
                        approvalStatus = "REJECTED"
                    )
                )
                refreshTrustedClientUuids()
                logEvent("INFO", "Host", "Rejected connection from ${request.clientName}")
                return@launch
            }

            val nextSuffix = (_connectedPeers.value.size + 2).coerceAtMost(254)
            val assignedIp = "10.88.0.$nextSuffix"
            val cipher = TunnelCrypto.deriveSessionCipher(
                localKeyPair = kp,
                remotePublicKeyB64 = request.clientPublicKeyB64,
                sessionSalt = sessionState.code.toByteArray(Charsets.UTF_8),
                isHost = true
            )

            var forwarderRef: HostGatewayForwarder? = null
            val transportRes = server.approvePendingClient(
                connectionId = request.connectionId,
                assignedIp = assignedIp,
                cipher = cipher,
                accounting = trafficAccounting,
                onFrameReceived = { frame -> forwarderRef?.handleIncomingFrame(frame) },
                onTransportError = { err ->
                    logEvent("WARN", "HostTunnel", "Peer ${request.clientName} disconnected: ${err.message}")
                    hostForwarders.remove(request.connectionId)?.close()
                    _connectedPeers.value = _connectedPeers.value.filterNot { it.connectionId == request.connectionId }
                    HostServiceBridge.connectedCount = _connectedPeers.value.size
                }
            )

            val transport = transportRes.getOrNull()
            if (transport == null) {
                _statusBannerMessage.value = transportRes.exceptionOrNull()?.localizedMessage
                    ?: "Client disconnected before approval completed."
                return@launch
            }

            val forwarder = HostGatewayForwarder(
                clientLabel = request.clientName,
                cipher = cipher,
                transport = transport,
                accounting = trafficAccounting,
                activeNetworkProvider = { connectivityMonitor.getActivePhysicalNetwork() },
                scope = runtimeScope,
                onClientDisconnected = {
                    hostForwarders.remove(request.connectionId)
                    _connectedPeers.value = _connectedPeers.value.filterNot { it.connectionId == request.connectionId }
                    HostServiceBridge.connectedCount = _connectedPeers.value.size
                }
            )
            forwarderRef = forwarder
            hostForwarders[request.connectionId] = forwarder

            val peer = ConnectedPeer(
                connectionId = request.connectionId,
                clientDeviceId = request.clientDeviceId,
                deviceName = request.clientName,
                virtualIp = assignedIp,
                mode = request.requestedMode,
                transportPath = transport.transportLabel,
                connectedAtMs = System.currentTimeMillis(),
                bytesUploaded = 0L,
                bytesDownloaded = 0L,
                uploadBps = 0L,
                downloadBps = 0L
            )
            _connectedPeers.value = _connectedPeers.value.filterNot { it.connectionId == request.connectionId } + peer
            HostServiceBridge.connectedCount = _connectedPeers.value.size

            val approvalState = if (markTrusted) "TRUSTED" else "APPROVED"
            localRepo.savePeerRecord(
                SavedPeerEntity(
                    role = "CLIENT",
                    deviceUuid = request.clientDeviceId,
                    peerName = request.clientName,
                    hostIp = request.clientEndpoint,
                    hostPort = sessionState.listenPort,
                    sharingCode = sessionState.code,
                    modeCode = request.requestedMode.code,
                    approvalStatus = approvalState
                )
            )
            refreshTrustedClientUuids()
            logEvent("INFO", "Host", "Accepted ${request.clientName} ($assignedIp) [$approvalState]")
        }
    }

    fun setSavedPeerTrusted(peer: SavedPeerEntity, trusted: Boolean) {
        runtimeScope.launch {
            localRepo.updatePeerApprovalStatus(peer.id, if (trusted) "TRUSTED" else "APPROVED")
            refreshTrustedClientUuids()
        }
    }

    fun removeSavedPeer(peerId: Long) {
        runtimeScope.launch {
            localRepo.deletePeerRecord(peerId)
            refreshTrustedClientUuids()
        }
    }

    fun disconnectPeer(connectionId: Long) {
        runtimeScope.launch {
            hostForwarders.remove(connectionId)?.close()
            _connectedPeers.value = _connectedPeers.value.filterNot { it.connectionId == connectionId }
            HostServiceBridge.connectedCount = _connectedPeers.value.size
            logEvent("INFO", "Host", "Disconnected peer #$connectionId")
        }
    }

    fun stopHostSharing() {
        hostStartJob?.cancel()
        hostStartJob = null
        nsdDiscovery.unregisterHostService()
        beaconDiscovery.stopAdvertising()
        beaconDiscovery.stopListeningForOfflineNeeds()
        wifiDirectManager.unregisterHostP2pService()
        hostGatewayServer?.stop()
        hostGatewayServer = null

        val snap = trafficAccounting.snapshot.value
        val dur = if (sessionStartedAtMs > 0L) (System.currentTimeMillis() - sessionStartedAtMs) / 1000L else 0L
        val sessionDbId = activeHostSessionDbId
        activeHostSessionDbId = 0L

        _pendingRequests.value.forEach { req ->
            HostServiceBridge.dismissRequestNotification(appContext, req.connectionId)
        }
        hostForwarders.values.forEach { it.close() }
        hostForwarders.clear()
        _pendingRequests.value = emptyList()
        _connectedPeers.value = emptyList()
        HostServiceBridge.connectedCount = 0
        HostServiceBridge.pendingCount = 0
        _sharingCodeState.value = SharingCodeState()

        runtimeScope.launch {
            localRepo.markHostSessionEnded(sessionDbId)
            localRepo.recordSessionTraffic("HOST", "Direct P2P Gateway", snap.totalUploadedBytes, snap.totalDownloadedBytes, dur)
        }

        val stopIntent = Intent(appContext, HostSharingService::class.java).apply {
            action = HostSharingService.ACTION_STOP
        }
        runCatching { appContext.startService(stopIntent) }
        if ((_tunnelState.value as? TunnelState.Connected)?.role == "HOST" ||
            _tunnelState.value is TunnelState.Handshaking
        ) {
            _tunnelState.value = TunnelState.Idle
        }
        logEvent("INFO", "Host", "Stopped Host sharing session")
    }

    // =========================================================================
    // CLIENT MODE (BACKGROUND SERVICE + OFFLINE LAN / WI-FI DIRECT + RECONNECT)
    // =========================================================================

    private fun addDiscoveredHost(peer: DiscoveredHostPeer) {
        _tcpDiscoveredHosts.value = _tcpDiscoveredHosts.value
            .filterNot { it.sharingCode == peer.sharingCode && it.hostIp == peer.hostIp } + peer
    }

    private suspend fun probeCandidateAndLanHosts(
        extraIp: String = "",
        portOverride: Int? = null
    ): DiscoveredHostPeer? {
        connectivityMonitor.refresh()
        val defaultPort = portOverride ?: settings.value.directListenPort
        val net = activeNetwork.value
        val ownIps = net.localAddresses.mapNotNull {
            it.substringAfter(':', "").takeIf { ip -> ip.isNotBlank() }
        }.toSet()
        val gwIp = connectivityMonitor.getDefaultGatewayIp()
        val p2pIp = wifiDirectManager.state.value.groupOwnerIp.takeIf { it.isNotBlank() }
        val savedList = localRepo.savedHosts.firstOrNull().orEmpty()

        val priorityTargets = linkedMapOf<String, Int>()
        fun addTarget(ip: String?, port: Int) {
            val clean = ip?.trim().orEmpty()
            if (clean.isNotBlank() && clean != "—" && clean !in ownIps && !clean.startsWith("127.")) {
                priorityTargets.putIfAbsent(clean, port)
            }
        }
        addTarget(extraIp, defaultPort)
        addTarget(p2pIp, defaultPort)
        addTarget(gwIp, defaultPort)
        addTarget("192.168.43.1", defaultPort)
        addTarget("192.168.49.1", defaultPort)
        savedList.forEach { saved ->
            addTarget(saved.hostIp, saved.hostPort.takeIf { it > 0 } ?: defaultPort)
        }

        val priorityFactory = wifiDirectManager.getBoundP2pNetwork()?.socketFactory
        val priorityMatch = coroutineScope {
            priorityTargets.entries.map { (ip, port) ->
                async {
                    DirectTcpProbe.probeHost(ip, port, timeoutMs = 450, socketFactory = null)
                        ?: priorityFactory?.let { DirectTcpProbe.probeHost(ip, port, timeoutMs = 450, socketFactory = it) }
                }
            }.awaitAll().filterNotNull().also { list ->
                list.forEach { addDiscoveredHost(it) }
            }.firstOrNull()
        }
        if (priorityMatch != null) return priorityMatch

        val lanPrefixes = net.localAddresses.mapNotNull { entry ->
            val iface = entry.substringBefore(':', "")
            val ip = entry.substringAfter(':', "")
            val lower = iface.lowercase(Locale.US)
            val isLocalIface = lower.startsWith("wlan") || lower.startsWith("eth") ||
                lower.startsWith("p2p") || lower.startsWith("ap") || lower.startsWith("swlan") ||
                ip.startsWith("192.168.")
            if (!isLocalIface) return@mapNotNull null
            val parts = ip.split('.')
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
        }.distinct().take(2)

        if (lanPrefixes.isEmpty()) return null

        val candidateIps = buildList {
            for (prefix in lanPrefixes) {
                for (hostOctet in 1..254) {
                    val ip = "$prefix.$hostOctet"
                    if (ip !in ownIps && !priorityTargets.containsKey(ip)) {
                        add(ip)
                    }
                }
            }
        }

        val sem = Semaphore(32)
        return coroutineScope {
            candidateIps.map { ip ->
                async {
                    sem.withPermit {
                        DirectTcpProbe.probeHost(ip, defaultPort, timeoutMs = 220)
                    }
                }
            }.awaitAll().filterNotNull().also { foundList ->
                foundList.forEach { addDiscoveredHost(it) }
            }.firstOrNull()
        }
    }

    fun startNearbyHostScan() {
        _tcpDiscoveredHosts.value = emptyList()
        nsdDiscovery.startDiscovery()
        beaconDiscovery.startListening()
        nearbyTcpProbeJob?.cancel()
        nearbyTcpProbeJob = runtimeScope.launch {
            probeCandidateAndLanHosts()
        }
    }

    fun stopNearbyHostScan() {
        nearbyTcpProbeJob?.cancel()
        nearbyTcpProbeJob = null
        nsdDiscovery.stopDiscovery()
        beaconDiscovery.stopListening()
    }

    fun selectClientMode(mode: SharingMode) {
        _clientSelectedMode.value = mode
    }

    fun requestClientJoin(
        rawCodeOrQr: String,
        manualHostIp: String = "",
        manualHostPort: Int? = null
    ) {
        val parsedInvite = QrCodeGenerator.parseShareInvite(rawCodeOrQr)
        val code = parsedInvite?.code ?: rawCodeOrQr.trim().uppercase()
        if (code.length < 6) {
            _tunnelState.value = TunnelState.Error(
                "INVALID_SHARING_CODE",
                "The sharing code \"$rawCodeOrQr\" is invalid.",
                "Enter the full code shown on the Host device or scan its QR code."
            )
            return
        }
        if (!_authSession.value.isSignedIn) {
            _statusBannerMessage.value = "Sign in to your local WiFiShare profile before connecting."
            return
        }

        manualClientDisconnect = false
        lastClientAttempt = ClientJoinAttempt(rawCodeOrQr, manualHostIp, manualHostPort, isOfflineRequest = false)
        clientReconnectJob?.cancel()
        clientConnectJob?.cancel()
        activeClientConnector?.cancel()
        beaconDiscovery.stopBroadcastingOfflineNeed()

        ClientServiceBridge.statusTitle = "Resolving Host ($code)..."
        ClientServiceBridge.peerLabel = code
        ClientServiceBridge.modeLabel = _clientSelectedMode.value.label
        startClientForegroundService()

        clientConnectJob = runtimeScope.launch {
            _tunnelState.value = TunnelState.Handshaking("Resolving Host endpoint (LAN / Offline P2P)...")

            var targetIp = parsedInvite?.hostIp ?: manualHostIp.trim().takeIf { it.isNotBlank() }
            var targetPort = parsedInvite?.hostPort ?: manualHostPort
            var targetSocketFactory: SocketFactory? = wifiDirectManager.getBoundP2pNetwork()?.socketFactory

            // If invite has a LAN host IP AND P2P SoftAP credentials, test if LAN IP is actually reachable first;
            // if client is offline (not on Host's router), automatically join Host's Wi-Fi Direct SoftAP via QR creds!
            if (targetIp != null && !parsedInvite?.p2pSsid.isNullOrBlank() && !parsedInvite?.p2pPassphrase.isNullOrBlank()) {
                val directReachable = DirectTcpProbe.probeHost(
                    hostIp = targetIp,
                    hostPort = targetPort ?: settings.value.directListenPort,
                    timeoutMs = 1_100,
                    socketFactory = targetSocketFactory
                )
                if (directReachable == null) {
                    if (!wifiDirectManager.isWifiEnabled()) {
                        stopClientForegroundService()
                        _tunnelState.value = TunnelState.Error(
                            "WIFI_OFF",
                            "Wi-Fi is turned OFF on this phone.",
                            "Turn ON Wi-Fi so this phone can join Host network \"${parsedInvite?.p2pSsid}\" offline."
                        )
                        return@launch
                    }
                    _tunnelState.value = TunnelState.Handshaking(
                        "Joining Host's offline Wi-Fi Direct network (${parsedInvite?.p2pSsid})..."
                    )
                    val joinedNet = wifiDirectManager.joinWifiDirectAp(
                        ssid = parsedInvite!!.p2pSsid!!,
                        passphrase = parsedInvite.p2pPassphrase!!
                    )
                    if (joinedNet != null) {
                        targetIp = parsedInvite.p2pIp?.ifBlank { "192.168.49.1" } ?: "192.168.49.1"
                        targetSocketFactory = joinedNet.socketFactory
                    }
                }
            }

            if (targetIp == null || targetPort == null) {
                val match = discoveredHosts.value.firstOrNull { it.sharingCode.equals(code, ignoreCase = true) }
                if (match != null) {
                    targetIp = match.hostIp
                    targetPort = match.hostPort
                } else {
                    if (settings.value.enableNsdDiscovery && !isNsdScanning.value) {
                        nsdDiscovery.startDiscovery()
                    }
                    beaconDiscovery.startListening()
                    if (OfflineWifiDirectManager.hasRequiredPermission(appContext) && wifiDirectManager.isWifiEnabled()) {
                        wifiDirectManager.startPeerDiscovery()
                    }
                    val tcpProbeDeferred = async { probeCandidateAndLanHosts(manualHostIp, manualHostPort) }
                    repeat(14) {
                        delay(250)
                        val found = discoveredHosts.value.firstOrNull { it.sharingCode.equals(code, ignoreCase = true) }
                        if (found != null) {
                            targetIp = found.hostIp
                            targetPort = found.hostPort
                            return@repeat
                        }
                        val p2pSvc = wifiDirectManager.state.value.services
                            .firstOrNull { it.sharingCode.equals(code, ignoreCase = true) }
                        if (p2pSvc != null && p2pSvc.p2pSsid.isNotBlank() && p2pSvc.p2pPassphrase.isNotBlank()) {
                            _tunnelState.value = TunnelState.Handshaking("Found Host over P2P (${p2pSvc.deviceName}). Joining offline Wi-Fi...")
                            val joinedNet = wifiDirectManager.joinWifiDirectAp(p2pSvc.p2pSsid, p2pSvc.p2pPassphrase)
                            if (joinedNet != null) {
                                targetIp = p2pSvc.p2pIp.ifBlank { "192.168.49.1" }
                                targetPort = p2pSvc.hostPort
                                targetSocketFactory = joinedNet.socketFactory
                                return@repeat
                            }
                        }
                    }
                    if (targetIp == null || targetPort == null) {
                        tcpProbeDeferred.await()?.let { probed ->
                            val exact = discoveredHosts.value.firstOrNull { it.sharingCode.equals(code, ignoreCase = true) } ?: probed
                            if (exact.sharingCode.equals(code, ignoreCase = true)) {
                                targetIp = exact.hostIp
                                targetPort = exact.hostPort
                            }
                        }
                    } else {
                        tcpProbeDeferred.cancel()
                    }
                }
            }

            if (targetIp == null) {
                val p2pOwnerIp = wifiDirectManager.state.value.groupOwnerIp.takeIf { it.isNotBlank() }
                if (p2pOwnerIp != null) {
                    targetIp = p2pOwnerIp
                    targetSocketFactory = wifiDirectManager.getBoundP2pNetwork()?.socketFactory ?: targetSocketFactory
                }
            }

            if (targetIp == null || targetPort == null) {
                val saved = localRepo.getTrustedOrRecentHostByCode(code)
                if (saved != null && saved.hostIp.isNotBlank() && saved.hostPort > 0) {
                    targetIp = saved.hostIp
                    targetPort = saved.hostPort
                }
            }

            val resolvedIp = targetIp
            val resolvedPort = targetPort ?: settings.value.directListenPort
            if (resolvedIp.isNullOrBlank()) {
                stopClientForegroundService()
                _tunnelState.value = TunnelState.Error(
                    "HOST_ENDPOINT_NEEDED",
                    "No Host IP address found for code $code.",
                    "Turn on Wi-Fi and scan Host's QR code, or connect to Host's Hotspot / Wi-Fi Direct first."
                )
                return@launch
            }

            connectClientToEndpoint(
                resolvedIp = resolvedIp,
                resolvedPort = resolvedPort,
                code = code,
                isOfflineRequest = false,
                requestNote = "",
                socketFactory = targetSocketFactory
            )
        }
    }

    /**
     * Allows an offline Client with no sharing code to broadcast an offline internet request beacon,
     * discover any nearby Host (via UDP Beacon, mDNS, Wi-Fi Direct DNS-SD/Peers, gateway/LAN TCP probe, or manual IP),
     * auto-join the Host's offline Wi-Fi Direct AP when needed, and submit an open join request directly for authorization.
     */
    fun requestInternetOffline(
        note: String = "",
        manualHostIp: String = "",
        manualHostPort: Int? = null
    ) {
        if (!_authSession.value.isSignedIn) {
            _statusBannerMessage.value = "Sign in to your local WiFiShare profile before requesting internet."
            return
        }

        val cleanNote = note.trim().take(120)
        val deviceUuid = credentialStore.getOrCreateDeviceUuid()
        val deviceName = _authSession.value.displayName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }

        manualClientDisconnect = false
        lastClientAttempt = ClientJoinAttempt(
            rawCodeOrQr = "",
            manualHostIp = manualHostIp,
            manualHostPort = manualHostPort,
            isOfflineRequest = true,
            requestNote = cleanNote
        )
        clientReconnectJob?.cancel()
        clientConnectJob?.cancel()
        activeClientConnector?.cancel()
        beaconDiscovery.stopBroadcastingOfflineNeed()

        ClientServiceBridge.statusTitle = "Requesting Internet Offline..."
        ClientServiceBridge.peerLabel = "Nearby Host"
        ClientServiceBridge.modeLabel = _clientSelectedMode.value.label
        startClientForegroundService()

        clientConnectJob = runtimeScope.launch {
            connectivityMonitor.refresh()
            val curNet = activeNetwork.value
            if (!curNet.isConnected && !curNet.hasLocalLink &&
                !wifiDirectManager.isWifiEnabled() && manualHostIp.isBlank()
            ) {
                stopClientForegroundService()
                _tunnelState.value = TunnelState.Error(
                    "WIFI_OFF",
                    "Wi-Fi is turned OFF on this phone.",
                    "Turn ON Wi-Fi (no internet or router required) so this phone can discover & join a nearby Host offline."
                )
                return@launch
            }

            _tunnelState.value = TunnelState.Handshaking("Searching Wi-Fi, Hotspot & Wi-Fi Direct for nearby Hosts...")

            if (settings.value.enableNsdDiscovery && !isNsdScanning.value) {
                nsdDiscovery.startDiscovery()
            }
            beaconDiscovery.startListening()
            if (OfflineWifiDirectManager.hasRequiredPermission(appContext) && wifiDirectManager.isWifiEnabled()) {
                wifiDirectManager.startPeerDiscovery()
            }
            beaconDiscovery.startBroadcastingOfflineNeed(
                deviceUuid = deviceUuid,
                deviceName = deviceName,
                requestedMode = _clientSelectedMode.value,
                note = cleanNote,
                unicastTargetsProvider = {
                    listOfNotNull(
                        manualHostIp.trim().takeIf { it.isNotBlank() },
                        wifiDirectManager.state.value.groupOwnerIp.takeIf { it.isNotBlank() },
                        connectivityMonitor.getDefaultGatewayIp(),
                        "192.168.43.1",
                        "192.168.49.1"
                    ).distinct()
                }
            )

            logEvent("INFO", "Client", "Started offline internet request (${cleanNote.ifBlank { _clientSelectedMode.value.label }})")

            var targetIp: String? = null
            var targetPort: Int? = null
            var targetCode: String = DirectHostGatewayServer.OFFLINE_REQUEST_CODE
            var targetSocketFactory: SocketFactory? = wifiDirectManager.getBoundP2pNetwork()?.socketFactory
            var attemptedSoftApSsid = ""
            var attemptedP2pPeerConnect = false

            for (attempt in 0 until 60) {
                if (!isActive || manualClientDisconnect) {
                    beaconDiscovery.stopBroadcastingOfflineNeed()
                    return@launch
                }

                val manualClean = manualHostIp.trim()
                if (manualClean.isNotBlank()) {
                    targetIp = manualClean
                    targetPort = manualHostPort ?: settings.value.directListenPort
                    targetCode = DirectHostGatewayServer.OFFLINE_REQUEST_CODE
                    break
                }

                val discovered = discoveredHosts.value.firstOrNull()
                if (discovered != null) {
                    targetIp = discovered.hostIp
                    targetPort = discovered.hostPort
                    targetCode = discovered.sharingCode.ifBlank { DirectHostGatewayServer.OFFLINE_REQUEST_CODE }
                    break
                }

                val p2pState = wifiDirectManager.state.value
                val p2pIp = p2pState.groupOwnerIp.takeIf { it.isNotBlank() }
                if (p2pIp != null && p2pState.isGroupActive && !p2pState.isGroupOwner) {
                    val p2pNet = wifiDirectManager.getBoundP2pNetwork()
                    val probedP2p = DirectTcpProbe.probeHost(
                        hostIp = p2pIp,
                        hostPort = manualHostPort ?: settings.value.directListenPort,
                        timeoutMs = 700,
                        socketFactory = p2pNet?.socketFactory
                    ) ?: DirectTcpProbe.probeHost(
                        hostIp = p2pIp,
                        hostPort = manualHostPort ?: settings.value.directListenPort,
                        timeoutMs = 700
                    )
                    if (probedP2p != null) {
                        targetIp = probedP2p.hostIp
                        targetPort = probedP2p.hostPort
                        targetCode = probedP2p.sharingCode.ifBlank { DirectHostGatewayServer.OFFLINE_REQUEST_CODE }
                        targetSocketFactory = p2pNet?.socketFactory
                        break
                    }
                }

                // Check discovered Wi-Fi Direct WiFiShare services with SoftAP credentials
                val p2pSvc = p2pState.services.firstOrNull {
                    it.allowOpenOffline && it.p2pSsid.isNotBlank() && it.p2pPassphrase.isNotBlank()
                }
                if (p2pSvc != null && attemptedSoftApSsid != p2pSvc.p2pSsid) {
                    attemptedSoftApSsid = p2pSvc.p2pSsid
                    _tunnelState.value = TunnelState.Handshaking(
                        "Found Host ${p2pSvc.deviceName}. Joining offline Wi-Fi Direct AP (${p2pSvc.p2pSsid})..."
                    )
                    val joinedNet = wifiDirectManager.joinWifiDirectAp(p2pSvc.p2pSsid, p2pSvc.p2pPassphrase)
                    if (joinedNet != null) {
                        targetIp = p2pSvc.p2pIp.ifBlank { "192.168.49.1" }
                        targetPort = p2pSvc.hostPort
                        targetCode = p2pSvc.sharingCode.ifBlank { DirectHostGatewayServer.OFFLINE_REQUEST_CODE }
                        targetSocketFactory = joinedNet.socketFactory
                        break
                    }
                }

                // If a Wi-Fi Direct service or peer was discovered without pre-shared SoftAP creds, connect P2P directly
                if (!attemptedP2pPeerConnect && !p2pState.isGroupActive && attempt >= 2) {
                    val targetPeerAddr = p2pState.services.firstOrNull { it.allowOpenOffline }?.deviceAddress
                        ?: p2pState.peers.firstOrNull { it.statusLabel.contains("Available", ignoreCase = true) }?.deviceAddress
                    if (!targetPeerAddr.isNullOrBlank()) {
                        attemptedP2pPeerConnect = true
                        _tunnelState.value = TunnelState.Handshaking("Connecting via Offline Wi-Fi Direct to nearby Host...")
                        wifiDirectManager.connectToPeer(targetPeerAddr)
                    }
                }

                // Immediately on attempt 0 and periodically thereafter, probe gateway, Hotspot/P2P IPs, and LAN subnet over TCP
                if (attempt % 3 == 0) {
                    val tcpFound = probeCandidateAndLanHosts(manualHostIp, manualHostPort)
                    if (tcpFound != null) {
                        targetIp = tcpFound.hostIp
                        targetPort = tcpFound.hostPort
                        targetCode = tcpFound.sharingCode.ifBlank { DirectHostGatewayServer.OFFLINE_REQUEST_CODE }
                        targetSocketFactory = wifiDirectManager.getBoundP2pNetwork()?.socketFactory ?: targetSocketFactory
                        break
                    }
                }

                delay(1200)
            }

            beaconDiscovery.stopBroadcastingOfflineNeed()

            val resolvedIp = targetIp
            val resolvedPort = targetPort ?: settings.value.directListenPort
            if (resolvedIp.isNullOrBlank()) {
                stopClientForegroundService()
                _tunnelState.value = TunnelState.Error(
                    "NO_OFFLINE_HOST_FOUND",
                    "No nearby WiFiShare Host responded to your offline request.",
                    "Ensure Wi-Fi is ON and the Host has Host Sharing active (with P2P Group, Hotspot, or same Wi-Fi)."
                )
                return@launch
            }

            connectClientToEndpoint(
                resolvedIp = resolvedIp,
                resolvedPort = resolvedPort,
                code = targetCode,
                isOfflineRequest = true,
                requestNote = cleanNote,
                socketFactory = targetSocketFactory
            )
        }
    }

    private suspend fun connectClientToEndpoint(
        resolvedIp: String,
        resolvedPort: Int,
        code: String,
        isOfflineRequest: Boolean,
        requestNote: String,
        socketFactory: SocketFactory? = wifiDirectManager.getBoundP2pNetwork()?.socketFactory
    ) {
        ClientServiceBridge.statusTitle = "Connecting to $resolvedIp:$resolvedPort..."
        _tunnelState.value = TunnelState.Handshaking(
            if (isOfflineRequest) "Found nearby Host $resolvedIp:$resolvedPort · sending offline request..."
            else "Connecting directly to Host $resolvedIp:$resolvedPort..."
        )

        val cfg = settings.value
        val kp = TunnelCrypto.generateEphemeralKeyPair().also { ephemeralKeyPair = it }
        val clientPubB64 = TunnelCrypto.encodePublicKey(kp.public)
        val net = activeNetwork.value
        val isPrivateLocalIp = resolvedIp.startsWith("192.168.") || resolvedIp.startsWith("10.") || resolvedIp.startsWith("172.")
        val stunEndpoint = if (net.isConnected && !isPrivateLocalIp) {
            StunClient.discoverPublicEndpoint(cfg.stunHost, cfg.stunPort)
                .getOrNull()?.let { "${it.publicIp}:${it.publicPort}" } ?: "direct"
        } else {
            "offline-local"
        }

        val connector = DirectClientConnector(
            hostIp = resolvedIp,
            hostPort = resolvedPort,
            sharingCode = code,
            deviceUuid = credentialStore.getOrCreateDeviceUuid(),
            deviceName = _authSession.value.displayName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" },
            clientPublicKeyB64 = clientPubB64,
            requestedMode = _clientSelectedMode.value,
            stunEndpoint = stunEndpoint,
            scope = runtimeScope,
            isOfflineRequest = isOfflineRequest,
            requestNote = requestNote,
            socketFactory = socketFactory
        )
        activeClientConnector = connector

        connector.connectAndAwaitApproval(
            onAwaitingApproval = { hostInfo ->
                _joinedHostInfo.value = hostInfo
                val displayCode = hostInfo.resolvedSharingCode.ifBlank { code }
                ClientServiceBridge.peerLabel = hostInfo.hostDisplayName
                ClientServiceBridge.statusTitle = "Awaiting Host Approval"
                _tunnelState.value = TunnelState.AwaitingHostApproval(displayCode, hostInfo.hostDisplayName)
                logEvent("INFO", "Client", "Requested access from ${hostInfo.hostDisplayName} ($resolvedIp:$resolvedPort)")
            },
            onRejected = { reason ->
                stopClientForegroundService()
                _tunnelState.value = TunnelState.Error(
                    "HOST_REJECTED",
                    reason,
                    "Ask the Host to approve your device or verify the permitted modes and open-request setting."
                )
                logEvent("WARN", "Client", "Host rejected join: $reason")
            },
            onApproved = { hostInfo, assignedIp, createTransport ->
                establishClientEncryptedTunnel(
                    code = code,
                    hostInfo = hostInfo,
                    assignedIp = assignedIp,
                    createTransport = createTransport
                )
            },
            onError = { err ->
                if (!manualClientDisconnect && settings.value.autoReconnect &&
                    _tunnelState.value is TunnelState.Reconnecting
                ) {
                    scheduleClientReconnect(err.message ?: "Host unreachable")
                } else {
                    stopClientForegroundService()
                    _tunnelState.value = TunnelState.Error(
                        "DIRECT_CONNECT_FAILED",
                        "Could not reach Host at $resolvedIp:$resolvedPort (${err.localizedMessage}).",
                        "Ensure both devices share a Wi-Fi/Hotspot/Wi-Fi Direct link or reachable IP."
                    )
                    logEvent("ERROR", "Client", "Direct connect failed: ${err.message}")
                }
            }
        )
    }

    private suspend fun establishClientEncryptedTunnel(
        code: String,
        hostInfo: HostPublicInfo,
        assignedIp: String,
        createTransport: (
            cipher: com.example.networking.crypto.TunnelSessionCipher,
            accounting: TrafficAccounting,
            onFrame: suspend (DecryptedTunnelFrame) -> Unit,
            onError: (Throwable) -> Unit
        ) -> DirectSocketTunnelTransport
    ) {
        val kp = ephemeralKeyPair ?: return
        val cfg = settings.value
        beaconDiscovery.stopBroadcastingOfflineNeed()
        val effectiveCode = hostInfo.resolvedSharingCode.ifBlank { code }
        ClientServiceBridge.statusTitle = "Establishing Encrypted Tunnel"
        _tunnelState.value = TunnelState.Handshaking("Establishing encrypted tunnel to ${hostInfo.hostDisplayName}...")

        val cipher = TunnelCrypto.deriveSessionCipher(
            localKeyPair = kp,
            remotePublicKeyB64 = hostInfo.hostPublicKeyB64,
            sessionSalt = effectiveCode.toByteArray(Charsets.UTF_8),
            isHost = false
        )

        trafficAccounting.reset()
        sessionStartedAtMs = System.currentTimeMillis()
        var muxRef: LocalTunnelStreamMux? = null

        val onFrame: suspend (DecryptedTunnelFrame) -> Unit = { frame ->
            when (frame.type) {
                TunnelCrypto.FRAME_IP_PACKET -> VpnRuntimeBridge.deliverDecryptedIpPacket(frame)
                TunnelCrypto.FRAME_STREAM_DATA, TunnelCrypto.FRAME_STREAM_CLOSE -> muxRef?.onFrameReceived(frame)
                TunnelCrypto.FRAME_PONG -> {
                    TunnelWireCodec.decodePingTimestamp(frame.payload)?.let { sent ->
                        trafficAccounting.updateLatency(System.currentTimeMillis() - sent)
                    }
                }
                TunnelCrypto.FRAME_DISCONNECT -> {
                    manualClientDisconnect = true
                    disconnectClient()
                    _tunnelState.value = TunnelState.Error(
                        "HOST_STOPPED",
                        "Host closed the encrypted tunnel.",
                        "Reconnect when the Host is sharing again."
                    )
                }
            }
        }

        val transport = createTransport(cipher, trafficAccounting, onFrame) { err ->
            if (!manualClientDisconnect && settings.value.autoReconnect &&
                (_tunnelState.value is TunnelState.Connected || _tunnelState.value is TunnelState.Reconnecting)
            ) {
                scheduleClientReconnect(err.message ?: "Tunnel interrupted")
            } else if (!manualClientDisconnect) {
                stopClientForegroundService()
                _tunnelState.value = TunnelState.Error(
                    "TUNNEL_LOST",
                    "Encrypted tunnel disconnected: ${err.message}",
                    "Reconnect to the Host when available."
                )
            }
        }

        activeClientTransport = transport
        val mux = LocalTunnelStreamMux(cipher, transport, runtimeScope).also {
            it.startLocalProxy()
            muxRef = it
            activeClientMux = it
        }

        VpnRuntimeBridge.activeCipher = cipher
        VpnRuntimeBridge.activeTransport = transport
        VpnRuntimeBridge.activeAccounting = trafficAccounting
        VpnRuntimeBridge.assignedIp = assignedIp
        VpnRuntimeBridge.mtu = cfg.vpnMtu
        VpnRuntimeBridge.ipv6Enabled = cfg.ipv6Enabled
        VpnRuntimeBridge.dnsLeakProtection = cfg.dnsLeakProtection

        transport.sendEncrypted(cipher.seal(TunnelCrypto.FRAME_PING, 0, TunnelWireCodec.encodePingTimestamp()))

        localRepo.savePeerRecord(
            SavedPeerEntity(
                role = "HOST",
                deviceUuid = "${hostInfo.hostIp}:${hostInfo.hostPort}",
                peerName = hostInfo.hostDisplayName,
                hostIp = hostInfo.hostIp,
                hostPort = hostInfo.hostPort,
                sharingCode = effectiveCode,
                hostPublicKeyB64 = hostInfo.hostPublicKeyB64,
                modeCode = _clientSelectedMode.value.code,
                approvalStatus = "APPROVED"
            )
        )

        ClientServiceBridge.peerLabel = hostInfo.hostDisplayName
        ClientServiceBridge.modeLabel = _clientSelectedMode.value.label
        ClientServiceBridge.statusTitle = "Connected (Background)"

        _tunnelState.value = TunnelState.Connected(
            role = "CLIENT",
            mode = _clientSelectedMode.value,
            transportPath = transport.transportLabel,
            peerLabel = hostInfo.hostDisplayName,
            assignedVirtualIp = assignedIp,
            startedAtMs = sessionStartedAtMs
        )
        logEvent("INFO", "ClientTunnel", "Connected directly to ${hostInfo.hostDisplayName} ($assignedIp)")

        if (_clientSelectedMode.value == SharingMode.WHOLE_DEVICE_VPN) {
            requestOrStartVpn()
        }
    }

    private fun scheduleClientReconnect(reason: String) {
        val attemptInfo = lastClientAttempt ?: return
        if (clientReconnectJob?.isActive == true) return

        activeClientMux?.close()
        activeClientMux = null
        activeClientTransport?.close()
        activeClientTransport = null

        clientReconnectJob = runtimeScope.launch {
            for (attempt in 1..4) {
                if (manualClientDisconnect) return@launch
                ClientServiceBridge.statusTitle = "Reconnecting ($attempt/4)..."
                _tunnelState.value = TunnelState.Reconnecting(attempt, reason)
                logEvent("WARN", "ClientTunnel", "Auto-reconnect attempt $attempt: $reason")
                delay(attempt * 2500L)
                if (manualClientDisconnect) return@launch
                if (attemptInfo.isOfflineRequest) {
                    requestInternetOffline(attemptInfo.requestNote, attemptInfo.manualHostIp, attemptInfo.manualHostPort)
                } else {
                    requestClientJoin(attemptInfo.rawCodeOrQr, attemptInfo.manualHostIp, attemptInfo.manualHostPort)
                }
                return@launch
            }
        }
    }

    fun requestOrStartVpn() {
        val transport = activeClientTransport
        if (transport == null || !transport.isConnected) {
            _statusBannerMessage.value = "Connect to a Host sharing code first before starting Whole-Device VPN."
            return
        }
        val prepareIntent = VpnService.prepare(appContext)
        if (prepareIntent != null) {
            _pendingVpnPermissionEvent.value = prepareIntent
        } else {
            onVpnPermissionGranted()
        }
    }

    fun consumeVpnPermissionEvent() {
        _pendingVpnPermissionEvent.value = null
    }

    fun onVpnPermissionGranted() {
        _clientSelectedMode.value = SharingMode.WHOLE_DEVICE_VPN
        ClientServiceBridge.modeLabel = SharingMode.WHOLE_DEVICE_VPN.label
        val intent = Intent(appContext, WiFiShareVpnService::class.java).apply {
            action = WiFiShareVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(appContext, intent)
        logEvent("INFO", "VPN", "Started WiFiShareVpnService (MTU ${settings.value.vpnMtu})")
    }

    fun onVpnPermissionDenied() {
        _statusBannerMessage.value = "Android VPN permission denied. Continuing in Browser & Downloads mode."
        _clientSelectedMode.value = SharingMode.BROWSER_AND_DOWNLOADS
        ClientServiceBridge.modeLabel = SharingMode.BROWSER_AND_DOWNLOADS.label
        logEvent("WARN", "VPN", "User denied VpnService permission")
    }

    fun stopVpnOnly() {
        val intent = Intent(appContext, WiFiShareVpnService::class.java).apply {
            action = WiFiShareVpnService.ACTION_STOP
        }
        runCatching { appContext.startService(intent) }
    }

    fun disconnectClient() {
        manualClientDisconnect = true
        clientReconnectJob?.cancel()
        clientConnectJob?.cancel()
        activeClientConnector?.cancel()
        activeClientConnector = null
        beaconDiscovery.stopBroadcastingOfflineNeed()
        wifiDirectManager.releaseSoftApNetwork()
        stopVpnOnly()
        stopClientForegroundService()

        val snap = trafficAccounting.snapshot.value
        val dur = if (sessionStartedAtMs > 0L) (System.currentTimeMillis() - sessionStartedAtMs) / 1000L else 0L
        val peerName = _joinedHostInfo.value?.hostDisplayName ?: "Host"

        activeClientMux?.close()
        activeClientMux = null
        activeClientTransport?.close()
        activeClientTransport = null
        VpnRuntimeBridge.activeTransport = null
        VpnRuntimeBridge.activeCipher = null

        runtimeScope.launch {
            localRepo.recordSessionTraffic("CLIENT", peerName, snap.totalUploadedBytes, snap.totalDownloadedBytes, dur)
        }

        _joinedHostInfo.value = null
        _tunnelState.value = TunnelState.Idle
        logEvent("INFO", "Client", "Disconnected from Host")
    }

    fun getTunnelOkHttpClientOrNull(): OkHttpClient? {
        val connected = _tunnelState.value as? TunnelState.Connected ?: return null
        if (connected.role != "CLIENT") return null
        return activeClientMux?.createTunnelOkHttpClient()
    }

    fun isDownloadAllowedBySession(): Boolean {
        val connected = _tunnelState.value as? TunnelState.Connected ?: return true
        if (connected.role == "HOST") return true
        val hostPermitted = _joinedHostInfo.value?.permittedModes ?: return true
        return connected.mode != SharingMode.BROWSER_ONLY &&
            (SharingMode.BROWSER_AND_DOWNLOADS in hostPermitted || SharingMode.WHOLE_DEVICE_VPN in hostPermitted)
    }

    // =========================================================================
    // DIAGNOSTICS & TELEMETRY
    // =========================================================================

    fun runNetworkDiagnosticsProbes() {
        runtimeScope.launch {
            val cfg = settings.value
            val results = mutableListOf<DiagnosticProbeResult>()

            connectivityMonitor.refresh()
            val net = activeNetwork.value
            results.add(
                DiagnosticProbeResult(
                    "Physical Network & Local Link",
                    net.isConnected || net.hasLocalLink,
                    when {
                        net.isConnected -> "${net.transport.name} (${net.interfaceName}, ${net.localIp})"
                        net.hasLocalLink -> "Offline Local Link Ready (${net.interfaceName}, ${net.localIp})"
                        else -> "Offline (can still start Host standby or Wi-Fi Direct)"
                    }
                )
            )

            val dbOk = runCatching {
                localRepo.findAccountByEmail(_authSession.value.email)
                true
            }.getOrDefault(false)
            results.add(
                DiagnosticProbeResult(
                    "In-App Local Storage (Room SQLite)",
                    dbOk,
                    if (dbOk) "Encrypted session, trusted peers & local Room database operational" else "Database unavailable"
                )
            )

            val activePort = hostGatewayServer?.actualListenPort
            val gatewayProbe = if (activePort != null && hostGatewayServer?.isRunning == true) {
                true to "Host gateway actively listening in background on port $activePort"
            } else {
                runCatching {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(cfg.directListenPort))
                        true to "Port ${cfg.directListenPort} available for direct Host listening"
                    }
                }.getOrElse {
                    true to "Preferred port ${cfg.directListenPort} busy; automatic fallback port ready"
                }
            }
            results.add(DiagnosticProbeResult("Direct P2P Gateway Socket", gatewayProbe.first, gatewayProbe.second))

            val nsdOk = nsdDiscovery.isSupported()
            results.add(
                DiagnosticProbeResult(
                    "Offline Discovery (NSD + UDP Beacon + Wi-Fi Direct)",
                    nsdOk || wifiDirectManager.state.value.isSupported,
                    "NSD=${if (nsdOk) "ready" else "n/a"}, UDP Beacon=port ${LocalBeaconDiscovery.BEACON_PORT}, Wi-Fi Direct=${if (wifiDirectManager.state.value.isSupported) "supported" else "n/a"}"
                )
            )

            val stun = if (net.isConnected) StunClient.discoverPublicEndpoint(cfg.stunHost, cfg.stunPort) else null
            results.add(
                DiagnosticProbeResult(
                    "STUN Reflexive Endpoint (Online Optional)",
                    stun?.isSuccess ?: false,
                    stun?.getOrNull()?.let { "${it.publicIp}:${it.publicPort} (${it.rttMs} ms)" }
                        ?: "Offline / skipped — offline LAN, Hotspot, and Wi-Fi Direct still work"
                )
            )

            val vpnReady = VpnService.prepare(appContext) == null
            results.add(
                DiagnosticProbeResult(
                    "Android VpnService Consent",
                    vpnReady,
                    if (vpnReady) "Granted by system" else "Not yet granted (required for Mode C)"
                )
            )

            _diagnosticProbes.value = results
        }
    }

    fun askAiDiagnostics(query: String) {
        if (query.isBlank()) return
        runtimeScope.launch {
            val net = activeNetwork.value
            val tState = _tunnelState.value
            val snap = liveTraffic.value
            val cfg = settings.value
            val p2p = offlineP2pState.value
            val probesSummary = _diagnosticProbes.value.joinToString("\n") {
                "- ${it.name}: ${if (it.passed == true) "PASS" else "FAIL"} (${it.detail})"
            }
            val recentLogs = tunnelEvents.let { "" }
            val contextText = """
                Mode: Background Foreground Services (Host + Client + VPN) & Offline Local Accept
                Network: ${net.transport.name}, internet=${net.isConnected}, hasLocalLink=${net.hasLocalLink}, iface=${net.interfaceName}, localIp=${net.localIp}
                Host Service Running: ${isHostServiceRunning.value} | Client Service Running: ${isClientServiceRunning.value} | VPN Running: ${isVpnActive.value}
                Wi-Fi Direct Group Active: ${p2p.isGroupActive} (owner=${p2p.isGroupOwner}, ip=${p2p.groupOwnerIp})
                TunnelState: $tState
                Traffic: Up=${snap.totalUploadedBytes}B (${snap.uploadBps}B/s), Down=${snap.totalDownloadedBytes}B (${snap.downloadBps}B/s), RTT=${snap.tunnelLatencyMs}ms
                Auto-Accept Trusted Peers: ${cfg.autoAcceptTrustedPeers}
                Probes:
                $probesSummary
                $recentLogs
            """.trimIndent()

            aiDiagnostics.analyzeDiagnostics(query, contextText)
        }
    }

    private fun startTelemetrySampler() {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = runtimeScope.launch {
            while (isActive) {
                delay(1000)
                trafficAccounting.sampleSecond()
                val snap = trafficAccounting.snapshot.value
                HostServiceBridge.uploadBps = snap.uploadBps
                HostServiceBridge.downloadBps = snap.downloadBps
                ClientServiceBridge.uploadBps = snap.uploadBps
                ClientServiceBridge.downloadBps = snap.downloadBps

                val cfg = settings.value
                if (cfg.enforceDataLimit && _tunnelState.value is TunnelState.Connected) {
                    val totalMb = (snap.totalUploadedBytes + snap.totalDownloadedBytes) / (1024L * 1024L)
                    if (totalMb >= cfg.dataLimitMb) {
                        val role = (_tunnelState.value as TunnelState.Connected).role
                        logEvent("WARN", "Quota", "Configured data limit (${cfg.dataLimitMb} MB) reached; disconnecting.")
                        if (role == "HOST") stopHostSharing() else disconnectClient()
                        _statusBannerMessage.value = "Session disconnected: ${cfg.dataLimitMb} MB data limit reached."
                    }
                }
            }
        }
    }

    private fun startHostForegroundService() {
        val intent = Intent(appContext, HostSharingService::class.java).apply {
            action = HostSharingService.ACTION_START
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun startClientForegroundService() {
        val intent = Intent(appContext, ClientTunnelService::class.java).apply {
            action = ClientTunnelService.ACTION_START
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun stopClientForegroundService() {
        val intent = Intent(appContext, ClientTunnelService::class.java).apply {
            action = ClientTunnelService.ACTION_STOP
        }
        runCatching { appContext.startService(intent) }
    }

    private fun generateSharingCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        fun group(): String = buildString(4) {
            repeat(4) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
        return "WFS-${group()}-${group()}"
    }

    private fun logEvent(level: String, component: String, message: String) {
        runtimeScope.launch {
            localRepo.logEvent(level, component, message)
        }
    }

    companion object {
        private const val TAG = "WiFiShareRuntime"

        @Volatile
        private var INSTANCE: WiFiShareRuntime? = null

        fun getInstance(context: Context): WiFiShareRuntime {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WiFiShareRuntime(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
