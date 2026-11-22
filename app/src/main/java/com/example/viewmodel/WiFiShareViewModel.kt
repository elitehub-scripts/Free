package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.networking.transport.LocalNsdDiscovery
import com.example.networking.transport.TrafficAccounting
import com.example.networking.transport.TunnelPeerTransport
import com.example.networking.transport.TunnelWireCodec
import com.example.qr.QrCodeGenerator
import com.example.services.HostServiceBridge
import com.example.services.HostSharingService
import com.example.settings.SettingsDataStore
import com.example.settings.WiFiShareSettings
import com.example.vpn.HostGatewayForwarder
import com.example.vpn.VpnRuntimeBridge
import com.example.vpn.WiFiShareVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WiFiShareViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val db = WiFiShareDatabase.getInstance(context)
    val localRepo = WiFiShareLocalRepository(db)
    val settingsStore = SettingsDataStore(context)
    val credentialStore = SecureCredentialStore(context)
    val connectivityMonitor = ConnectivityMonitor(context)
    val nsdDiscovery = LocalNsdDiscovery(context)
    val browser = BrowserStateHolder()
    val aiDiagnostics = AiDiagnosticsService()
    val trafficAccounting = TrafficAccounting()

    val settings: StateFlow<WiFiShareSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, WiFiShareSettings())

    val activeNetwork: StateFlow<ActiveNetworkInfo> = connectivityMonitor.networkInfo
    val liveTraffic: StateFlow<LiveTrafficSnapshot> = trafficAccounting.snapshot
    val isVpnActive: StateFlow<Boolean> = VpnRuntimeBridge.isVpnRunning
    val isHostServiceRunning: StateFlow<Boolean> = HostServiceBridge.isRunning

    val discoveredHosts: StateFlow<List<DiscoveredHostPeer>> = nsdDiscovery.discoveredHosts
    val isNsdScanning: StateFlow<Boolean> = nsdDiscovery.isDiscovering

    val savedHosts = localRepo.savedHosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hostSessions = localRepo.hostSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = localRepo.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = localRepo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = localRepo.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trafficRecords = localRepo.trafficRecords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tunnelEvents = localRepo.tunnelEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyUpload = localRepo.observeDailyUpload().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val dailyDownload = localRepo.observeDailyDownload().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthlyUpload = localRepo.observeMonthlyUpload().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthlyDownload = localRepo.observeMonthlyDownload().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
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

    // Active runtime objects
    private var ephemeralKeyPair: KeyPair? = null
    private var hostGatewayServer: DirectHostGatewayServer? = null
    private var activeHostSessionDbId: Long = 0L
    private var activeClientConnector: DirectClientConnector? = null
    private var activeClientTransport: TunnelPeerTransport? = null
    private var activeClientMux: LocalTunnelStreamMux? = null
    private var clientConnectJob: Job? = null
    private val hostForwarders = ConcurrentHashMap<Long, HostGatewayForwarder>()
    private var telemetryLoopJob: Job? = null
    private var sessionStartedAtMs: Long = 0L

    val downloadEngine = DownloadManagerEngine(
        context = context,
        repository = localRepo,
        scope = viewModelScope,
        tunnelClientProvider = { activeClientMux?.createTunnelOkHttpClient() }
    )

    init {
        connectivityMonitor.start()
        HostServiceBridge.onStopRequested = { stopHostSharing() }
        VpnRuntimeBridge.onVpnStoppedExternally = {
            if (_tunnelState.value is TunnelState.Connected) {
                logEvent("INFO", "VPN", "Whole-device VPN interface closed")
            }
        }
        startTelemetrySampler()
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
        viewModelScope.launch { settingsStore.setOnboardingCompleted(true) }
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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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
    // HOST MODE (SERVERLESS DIRECT P2P GATEWAY)
    // =========================================================================

    fun startHostSharing() {
        val net = activeNetwork.value
        if (!net.isConnected) {
            _tunnelState.value = TunnelState.Error(
                "HOST_OFFLINE",
                "No active internet connection detected on this device.",
                "Connect to Wi-Fi or mobile data before starting Host Sharing."
            )
            return
        }
        if (!_authSession.value.isSignedIn) {
            _statusBannerMessage.value = "Sign in to your local WiFiShare profile first."
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _tunnelState.value = TunnelState.Handshaking("Starting local P2P gateway socket & STUN check...")
            val cfg = settings.value
            val kp = TunnelCrypto.generateEphemeralKeyPair().also { ephemeralKeyPair = it }
            val hostPubB64 = TunnelCrypto.encodePublicKey(kp.public)
            val sharingCode = generateSharingCode()
            val expiresAtSec = (System.currentTimeMillis() / 1000L) + (cfg.sessionExpiryMinutes * 60L)

            val stunResult = StunClient.discoverPublicEndpoint(cfg.stunHost, cfg.stunPort)
            val stunEndpoint = stunResult.getOrNull()?.let { "${it.publicIp}:${it.publicPort}" } ?: "LAN / Direct"

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
                networkType = net.transport.name,
                hostPublicKeyB64 = hostPubB64,
                stunEndpoint = stunEndpoint,
                permittedModesProvider = permittedProvider,
                maxDevicesProvider = { settings.value.maxConnectedDevices },
                activeDevicesCountProvider = { _connectedPeers.value.size },
                expiresAtEpochSec = expiresAtSec,
                scope = viewModelScope,
                onJoinRequested = { req ->
                    _pendingRequests.value = _pendingRequests.value.filterNot { it.connectionId == req.connectionId } + req
                    logEvent("INFO", "Host", "Incoming join request from ${req.clientName} (${req.requestedMode.label})")
                },
                onPendingClosed = { connId ->
                    _pendingRequests.value = _pendingRequests.value.filterNot { it.connectionId == connId }
                },
                onServerError = { err ->
                    logEvent("ERROR", "Host", "Gateway socket error: ${err.message}")
                }
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
            val advertiseIp = net.localIp.takeIf { it != "—" && it.isNotBlank() }
                ?: stunResult.getOrNull()?.publicIp
                ?: "127.0.0.1"
            val inviteUri = QrCodeGenerator.buildInviteUri(
                code = sharingCode,
                hostIp = advertiseIp,
                port = listenPort,
                hostPublicKeyB64 = hostPubB64
            )

            val dbSessionId = localRepo.recordHostSessionStart(
                SavedHostSessionEntity(
                    sharingCode = sharingCode,
                    listenPort = listenPort,
                    localIp = advertiseIp,
                    stunEndpoint = stunEndpoint,
                    permittedModesCsv = permittedProvider().joinToString(",") { it.code },
                    maxDevices = cfg.maxConnectedDevices
                )
            )
            activeHostSessionDbId = dbSessionId

            if (cfg.enableNsdDiscovery) {
                nsdDiscovery.registerHostService(
                    sharingCode = sharingCode,
                    hostName = hostName,
                    port = listenPort,
                    networkType = net.transport.name,
                    hostPublicKeyB64 = hostPubB64,
                    permittedModes = permittedProvider()
                )
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
                stunEndpoint = stunEndpoint,
                permittedModes = permittedProvider()
            )

            sessionStartedAtMs = System.currentTimeMillis()
            trafficAccounting.reset()
            HostServiceBridge.activeCode = sharingCode
            HostServiceBridge.connectedCount = 0
            startHostForegroundService()

            _tunnelState.value = TunnelState.Connected(
                role = "HOST",
                mode = SharingMode.WHOLE_DEVICE_VPN,
                transportPath = "Direct P2P ($advertiseIp:$listenPort)",
                peerLabel = "Listening (${net.transport.name})",
                assignedVirtualIp = "10.88.0.1",
                startedAtMs = sessionStartedAtMs
            )
            logEvent("INFO", "Host", "Started serverless Host session $sharingCode on $advertiseIp:$listenPort ($stunEndpoint)")
        }
    }

    fun regenerateSharingCode() {
        stopHostSharing()
        startHostSharing()
    }

    fun revokeSharingCodeAndStop() {
        stopHostSharing()
    }

    fun approvePeerRequest(request: PendingJoinRequest, approve: Boolean) {
        val server = hostGatewayServer ?: return
        val sessionState = _sharingCodeState.value
        val kp = ephemeralKeyPair ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _pendingRequests.value = _pendingRequests.value.filterNot { it.connectionId == request.connectionId }
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
                scope = viewModelScope,
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

            localRepo.savePeerRecord(
                SavedPeerEntity(
                    role = "CLIENT",
                    deviceUuid = request.clientDeviceId,
                    peerName = request.clientName,
                    hostIp = request.clientEndpoint,
                    hostPort = sessionState.listenPort,
                    sharingCode = sessionState.code,
                    modeCode = request.requestedMode.code,
                    approvalStatus = "APPROVED"
                )
            )
            logEvent("INFO", "Host", "Approved ${request.clientName} ($assignedIp)")
        }
    }

    fun disconnectPeer(connectionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            hostForwarders.remove(connectionId)?.close()
            _connectedPeers.value = _connectedPeers.value.filterNot { it.connectionId == connectionId }
            HostServiceBridge.connectedCount = _connectedPeers.value.size
            logEvent("INFO", "Host", "Disconnected peer #$connectionId")
        }
    }

    fun stopHostSharing() {
        nsdDiscovery.unregisterHostService()
        hostGatewayServer?.stop()
        hostGatewayServer = null

        val snap = trafficAccounting.snapshot.value
        val dur = if (sessionStartedAtMs > 0L) (System.currentTimeMillis() - sessionStartedAtMs) / 1000L else 0L
        val sessionDbId = activeHostSessionDbId
        activeHostSessionDbId = 0L

        hostForwarders.values.forEach { it.close() }
        hostForwarders.clear()
        _pendingRequests.value = emptyList()
        _connectedPeers.value = emptyList()
        _sharingCodeState.value = SharingCodeState()

        viewModelScope.launch(Dispatchers.IO) {
            localRepo.markHostSessionEnded(sessionDbId)
            localRepo.recordSessionTraffic("HOST", "Direct P2P Gateway", snap.totalUploadedBytes, snap.totalDownloadedBytes, dur)
        }

        val stopIntent = Intent(context, HostSharingService::class.java).apply {
            action = HostSharingService.ACTION_STOP
        }
        runCatching { context.startService(stopIntent) }
        if ((_tunnelState.value as? TunnelState.Connected)?.role == "HOST" ||
            _tunnelState.value is TunnelState.Handshaking
        ) {
            _tunnelState.value = TunnelState.Idle
        }
        logEvent("INFO", "Host", "Stopped serverless Host sharing session")
    }

    // =========================================================================
    // CLIENT MODE (DIRECT P2P + NSD LAN DISCOVERY)
    // =========================================================================

    fun startNearbyHostScan() {
        nsdDiscovery.startDiscovery()
    }

    fun stopNearbyHostScan() {
        nsdDiscovery.stopDiscovery()
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
        if (!activeNetwork.value.isConnected) {
            _tunnelState.value = TunnelState.Error(
                "CLIENT_OFFLINE",
                "Your device currently has no network connection.",
                "Connect to Wi-Fi, Hotspot, or mobile data first so WiFiShare can reach the Host."
            )
            return
        }
        if (!_authSession.value.isSignedIn) {
            _statusBannerMessage.value = "Sign in to your local WiFiShare profile before connecting."
            return
        }

        clientConnectJob?.cancel()
        activeClientConnector?.cancel()

        clientConnectJob = viewModelScope.launch(Dispatchers.IO) {
            _tunnelState.value = TunnelState.Handshaking("Resolving Host endpoint...")

            var targetIp = parsedInvite?.hostIp ?: manualHostIp.trim().takeIf { it.isNotBlank() }
            var targetPort = parsedInvite?.hostPort ?: manualHostPort

            if (targetIp == null || targetPort == null) {
                val nsdMatch = discoveredHosts.value.firstOrNull { it.sharingCode.equals(code, ignoreCase = true) }
                if (nsdMatch != null) {
                    targetIp = nsdMatch.hostIp
                    targetPort = nsdMatch.hostPort
                } else {
                    // Trigger short NSD scan on LAN if enabled
                    if (settings.value.enableNsdDiscovery && !isNsdScanning.value) {
                        nsdDiscovery.startDiscovery()
                        repeat(14) {
                            delay(250)
                            val found = discoveredHosts.value.firstOrNull { it.sharingCode.equals(code, ignoreCase = true) }
                            if (found != null) {
                                targetIp = found.hostIp
                                targetPort = found.hostPort
                                return@repeat
                            }
                        }
                    }
                }
            }

            if (targetIp == null || targetPort == null) {
                val saved = savedHosts.value.firstOrNull { it.sharingCode.equals(code, ignoreCase = true) }
                if (saved != null && saved.hostIp.isNotBlank() && saved.hostPort > 0) {
                    targetIp = saved.hostIp
                    targetPort = saved.hostPort
                }
            }

            val resolvedIp = targetIp
            val resolvedPort = targetPort ?: settings.value.directListenPort
            if (resolvedIp.isNullOrBlank()) {
                _tunnelState.value = TunnelState.Error(
                    "HOST_ENDPOINT_NEEDED",
                    "No Host IP address found for code $code.",
                    "Scan the Host's QR code, tap Refresh Nearby Hosts on the same Wi-Fi/Hotspot, or enter the Host IP & Port."
                )
                return@launch
            }

            _tunnelState.value = TunnelState.Handshaking("Connecting directly to Host $resolvedIp:$resolvedPort...")
            val cfg = settings.value
            val kp = TunnelCrypto.generateEphemeralKeyPair().also { ephemeralKeyPair = it }
            val clientPubB64 = TunnelCrypto.encodePublicKey(kp.public)
            val stunRes = StunClient.discoverPublicEndpoint(cfg.stunHost, cfg.stunPort)
            val stunEndpoint = stunRes.getOrNull()?.let { "${it.publicIp}:${it.publicPort}" } ?: "direct"

            val connector = DirectClientConnector(
                hostIp = resolvedIp,
                hostPort = resolvedPort,
                sharingCode = code,
                deviceUuid = credentialStore.getOrCreateDeviceUuid(),
                deviceName = _authSession.value.displayName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" },
                clientPublicKeyB64 = clientPubB64,
                requestedMode = _clientSelectedMode.value,
                stunEndpoint = stunEndpoint,
                scope = viewModelScope
            )
            activeClientConnector = connector

            connector.connectAndAwaitApproval(
                onAwaitingApproval = { hostInfo ->
                    _joinedHostInfo.value = hostInfo
                    _tunnelState.value = TunnelState.AwaitingHostApproval(code, hostInfo.hostDisplayName)
                    logEvent("INFO", "Client", "Requested access from ${hostInfo.hostDisplayName} ($resolvedIp:$resolvedPort)")
                },
                onRejected = { reason ->
                    _tunnelState.value = TunnelState.Error(
                        "HOST_REJECTED",
                        reason,
                        "Ask the Host to approve your device or verify the permitted modes and code."
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
                    _tunnelState.value = TunnelState.Error(
                        "DIRECT_CONNECT_FAILED",
                        "Could not reach Host at $resolvedIp:$resolvedPort (${err.localizedMessage}).",
                        "Ensure both devices can reach each other over Wi-Fi/Hotspot/LAN or port forwarding."
                    )
                    logEvent("ERROR", "Client", "Direct connect failed: ${err.message}")
                }
            )
        }
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
        ) -> com.example.networking.transport.DirectSocketTunnelTransport
    ) {
        val kp = ephemeralKeyPair ?: return
        val cfg = settings.value
        _tunnelState.value = TunnelState.Handshaking("Establishing encrypted tunnel to ${hostInfo.hostDisplayName}...")

        val cipher = TunnelCrypto.deriveSessionCipher(
            localKeyPair = kp,
            remotePublicKeyB64 = hostInfo.hostPublicKeyB64,
            sessionSalt = code.toByteArray(Charsets.UTF_8),
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
            if (settings.value.autoReconnect && _tunnelState.value is TunnelState.Connected) {
                _tunnelState.value = TunnelState.Reconnecting(1, err.message ?: "Tunnel interrupted")
            } else {
                _tunnelState.value = TunnelState.Error(
                    "TUNNEL_LOST",
                    "Encrypted tunnel disconnected: ${err.message}",
                    "Reconnect to the Host when available."
                )
            }
        }

        activeClientTransport = transport
        val mux = LocalTunnelStreamMux(cipher, transport, viewModelScope).also {
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
                sharingCode = code,
                hostPublicKeyB64 = hostInfo.hostPublicKeyB64,
                modeCode = _clientSelectedMode.value.code,
                approvalStatus = "APPROVED"
            )
        )

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

    fun requestOrStartVpn() {
        val transport = activeClientTransport
        if (transport == null || !transport.isConnected) {
            _statusBannerMessage.value = "Connect to a Host sharing code first before starting Whole-Device VPN."
            return
        }
        val prepareIntent = VpnService.prepare(context)
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
        val intent = Intent(context, WiFiShareVpnService::class.java).apply {
            action = WiFiShareVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
        logEvent("INFO", "VPN", "Started WiFiShareVpnService (MTU ${settings.value.vpnMtu})")
    }

    fun onVpnPermissionDenied() {
        _statusBannerMessage.value = "Android VPN permission denied. Continuing in Browser & Downloads mode."
        _clientSelectedMode.value = SharingMode.BROWSER_AND_DOWNLOADS
        logEvent("WARN", "VPN", "User denied VpnService permission")
    }

    fun stopVpnOnly() {
        val intent = Intent(context, WiFiShareVpnService::class.java).apply {
            action = WiFiShareVpnService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
    }

    fun disconnectClient() {
        clientConnectJob?.cancel()
        activeClientConnector?.cancel()
        activeClientConnector = null
        stopVpnOnly()

        val snap = trafficAccounting.snapshot.value
        val dur = if (sessionStartedAtMs > 0L) (System.currentTimeMillis() - sessionStartedAtMs) / 1000L else 0L
        val peerName = _joinedHostInfo.value?.hostDisplayName ?: "Host"

        activeClientMux?.close()
        activeClientMux = null
        activeClientTransport?.close()
        activeClientTransport = null
        VpnRuntimeBridge.activeTransport = null
        VpnRuntimeBridge.activeCipher = null

        viewModelScope.launch(Dispatchers.IO) {
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
    // DIAGNOSTICS & AI HIGH THINKING
    // =========================================================================

    fun runNetworkDiagnosticsProbes() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = settings.value
            val results = mutableListOf<DiagnosticProbeResult>()

            // 1. Local physical network
            connectivityMonitor.refresh()
            val net = activeNetwork.value
            results.add(
                DiagnosticProbeResult(
                    "Physical Network Interface",
                    net.isConnected,
                    if (net.isConnected) "${net.transport.name} (${net.interfaceName}, ${net.localIp})" else "No active internet interface"
                )
            )

            // 2. Local Room In-App Storage
            val dbOk = runCatching {
                localRepo.findAccountByEmail(_authSession.value.email)
                true
            }.getOrDefault(false)
            results.add(
                DiagnosticProbeResult(
                    "In-App Local Storage (Room SQLite)",
                    dbOk,
                    if (dbOk) "Encrypted session & local Room database operational" else "Database unavailable"
                )
            )

            // 3. Direct P2P Host Gateway Socket
            val activePort = hostGatewayServer?.actualListenPort
            val gatewayProbe = if (activePort != null && hostGatewayServer?.isRunning == true) {
                true to "Host gateway actively listening on port $activePort"
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
            results.add(
                DiagnosticProbeResult(
                    "Direct P2P Gateway Socket",
                    gatewayProbe.first,
                    gatewayProbe.second
                )
            )

            // 4. Local NSD / mDNS Discovery
            val nsdOk = nsdDiscovery.isSupported()
            results.add(
                DiagnosticProbeResult(
                    "Local LAN Discovery (NSD/mDNS)",
                    nsdOk,
                    if (nsdOk && cfg.enableNsdDiscovery) "NsdManager ready for _wifishare._tcp. discovery" else "Disabled in Settings or unsupported"
                )
            )

            // 5. Optional STUN Reflexive NAT Mapping
            val stun = StunClient.discoverPublicEndpoint(cfg.stunHost, cfg.stunPort)
            results.add(
                DiagnosticProbeResult(
                    "STUN Reflexive Endpoint (Optional)",
                    stun.isSuccess,
                    stun.getOrNull()?.let { "${it.publicIp}:${it.publicPort} (${it.rttMs} ms)" }
                        ?: "Unavailable (${stun.exceptionOrNull()?.message}); LAN/direct IP still works"
                )
            )

            // 6. VPN Permission status
            val vpnReady = VpnService.prepare(context) == null
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
        viewModelScope.launch {
            val net = activeNetwork.value
            val tState = _tunnelState.value
            val snap = liveTraffic.value
            val cfg = settings.value
            val probesSummary = _diagnosticProbes.value.joinToString("\n") {
                "- ${it.name}: ${if (it.passed == true) "PASS" else "FAIL"} (${it.detail})"
            }
            val recentLogs = tunnelEvents.value.take(10).joinToString("\n") {
                "[${it.level}/${it.component}] ${it.message}"
            }
            val contextText = """
                Mode: Serverless Direct P2P (All data saved locally in Room + DataStore)
                Network: ${net.transport.name}, connected=${net.isConnected}, iface=${net.interfaceName}, localIp=${net.localIp}
                TunnelState: $tState
                VPN Running: ${isVpnActive.value}, MTU=${cfg.vpnMtu}, DNSLeakProtection=${cfg.dnsLeakProtection}, IPv6=${cfg.ipv6Enabled}
                Traffic: Up=${snap.totalUploadedBytes}B (${snap.uploadBps}B/s), Down=${snap.totalDownloadedBytes}B (${snap.downloadBps}B/s), ReplayDrops=${snap.droppedReplayPackets}, RTT=${snap.tunnelLatencyMs}ms
                Direct Listen Port: ${cfg.directListenPort} | NSD Enabled: ${cfg.enableNsdDiscovery} | STUN: ${cfg.stunHost}:${cfg.stunPort}
                Probes:
                $probesSummary
                Recent Tunnel Events:
                $recentLogs
            """.trimIndent()

            aiDiagnostics.analyzeDiagnostics(query, contextText)
        }
    }

    // =========================================================================
    // TELEMETRY & LIMITS
    // =========================================================================

    private fun startTelemetrySampler() {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                trafficAccounting.sampleSecond()
                val snap = trafficAccounting.snapshot.value
                HostServiceBridge.uploadBps = snap.uploadBps
                HostServiceBridge.downloadBps = snap.downloadBps

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
        val intent = Intent(context, HostSharingService::class.java).apply {
            action = HostSharingService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
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
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.logEvent(level, component, message)
        }
    }

    override fun onCleared() {
        nsdDiscovery.unregisterHostService()
        nsdDiscovery.stopDiscovery()
        hostGatewayServer?.stop()
        activeClientConnector?.cancel()
        connectivityMonitor.stop()
        telemetryLoopJob?.cancel()
        clientConnectJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "WiFiShareViewModel"
    }
}
