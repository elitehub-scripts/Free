package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.diagnostics.configureAppCheck
import com.example.model.TunnelState
import com.example.navigation.WiFiShareRoute
import com.example.screens.ApprovalScreen
import com.example.screens.AuthScreen
import com.example.screens.BrowserScreen
import com.example.screens.ClientModeScreen
import com.example.screens.ConnectedDevicesScreen
import com.example.screens.DownloadsScreen
import com.example.screens.HomeDashboardScreen
import com.example.screens.HostModeScreen
import com.example.screens.PrivacySecurityScreen
import com.example.screens.QrScannerScreen
import com.example.screens.SettingsScreen
import com.example.screens.SharingCodeScreen
import com.example.screens.SplashScreen
import com.example.screens.TabManagerScreen
import com.example.screens.TrafficStatsScreen
import com.example.screens.TroubleshootingScreen
import com.example.screens.VpnConnectionScreen
import com.example.screens.WelcomeScreen
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.WiFiShareTheme
import com.example.viewmodel.WiFiShareViewModel
import kotlinx.coroutines.launch

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val _pendingNotificationRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory Step 1: Configure Firebase App Check with launch intent before Compose UI or ViewModels
        configureAppCheck(this, intent)
        handleNotificationIntent(intent)

        enableEdgeToEdge()
        setContent {
            val viewModel: WiFiShareViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val pendingRoute by _pendingNotificationRoute.asStateFlow().collectAsStateWithLifecycle()
            val isDark = when (settings.themeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            WiFiShareTheme(darkTheme = isDark) {
                WiFiShareAppShell(
                    viewModel = viewModel,
                    pendingNotificationRoute = pendingRoute,
                    onConsumeNotificationRoute = { _pendingNotificationRoute.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        if (!route.isNullOrBlank()) {
            _pendingNotificationRoute.value = route
        }
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "extra_open_route"
        const val ROUTE_ARG_HOST = "host"
        const val ROUTE_ARG_APPROVALS = "approvals"
        const val ROUTE_ARG_CLIENT = "client"
    }
}

private data class TopLevelNavItem(
    val label: String,
    val icon: ImageVector,
    val route: WiFiShareRoute,
    val tag: String
)

@Composable
private fun WiFiShareAppShell(
    viewModel: WiFiShareViewModel,
    pendingNotificationRoute: String?,
    onConsumeNotificationRoute: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val authSession by viewModel.authSession.collectAsStateWithLifecycle()
    val authBusy by viewModel.authBusy.collectAsStateWithLifecycle()
    val authMessage by viewModel.authMessage.collectAsStateWithLifecycle()
    val activeNetwork by viewModel.activeNetwork.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val sharingCode by viewModel.sharingCodeState.collectAsStateWithLifecycle()
    val pendingRequests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val clientMode by viewModel.clientSelectedMode.collectAsStateWithLifecycle()
    val joinedHost by viewModel.joinedHostInfo.collectAsStateWithLifecycle()
    val traffic by viewModel.liveTraffic.collectAsStateWithLifecycle()
    val isVpnActive by viewModel.isVpnActive.collectAsStateWithLifecycle()
    val isHostServiceRunning by viewModel.isHostServiceRunning.collectAsStateWithLifecycle()
    val isClientServiceRunning by viewModel.isClientServiceRunning.collectAsStateWithLifecycle()
    val offlineP2pState by viewModel.offlineP2pState.collectAsStateWithLifecycle()
    val pendingVpnIntent by viewModel.pendingVpnPermissionEvent.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    val tabs by viewModel.browser.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.browser.activeTabId.collectAsStateWithLifecycle()
    val isFullScreen by viewModel.browser.isFullScreen.collectAsStateWithLifecycle()
    val reloadTrigger by viewModel.browser.reloadTrigger.collectAsStateWithLifecycle()
    val navCommand by viewModel.browser.navCommand.collectAsStateWithLifecycle()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val trafficRecords by viewModel.trafficRecords.collectAsStateWithLifecycle()
    val tunnelEvents by viewModel.tunnelEvents.collectAsStateWithLifecycle()
    val dailyUp by viewModel.dailyUpload.collectAsStateWithLifecycle()
    val dailyDown by viewModel.dailyDownload.collectAsStateWithLifecycle()
    val monthlyUp by viewModel.monthlyUpload.collectAsStateWithLifecycle()
    val monthlyDown by viewModel.monthlyDownload.collectAsStateWithLifecycle()
    val probes by viewModel.diagnosticProbes.collectAsStateWithLifecycle()
    val aiUiState by viewModel.aiUiState.collectAsStateWithLifecycle()
    val discoveredHosts by viewModel.discoveredHosts.collectAsStateWithLifecycle()
    val isNsdScanning by viewModel.isNsdScanning.collectAsStateWithLifecycle()
    val nearbyOfflineNeeds by viewModel.nearbyOfflineNeeds.collectAsStateWithLifecycle()
    val savedHosts by viewModel.savedHosts.collectAsStateWithLifecycle()
    val savedClients by viewModel.savedClients.collectAsStateWithLifecycle()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.consumeVpnPermissionEvent()
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    LaunchedEffect(pendingVpnIntent) {
        pendingVpnIntent?.let { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    LaunchedEffect(authSession.isSignedIn) {
        val currentEntry = navController.currentBackStackEntry
        if (authSession.isSignedIn && currentEntry?.destination?.hasRoute<WiFiShareRoute.Auth>() == true) {
            navController.navigate(WiFiShareRoute.Home) {
                popUpTo(WiFiShareRoute.Auth) { inclusive = true }
            }
        }
    }

    LaunchedEffect(pendingNotificationRoute, authSession.isSignedIn) {
        val target = pendingNotificationRoute
        if (target != null && authSession.isSignedIn) {
            val dest: WiFiShareRoute = when (target) {
                MainActivity.ROUTE_ARG_APPROVALS -> WiFiShareRoute.Approvals
                MainActivity.ROUTE_ARG_HOST -> WiFiShareRoute.Host
                MainActivity.ROUTE_ARG_CLIENT -> WiFiShareRoute.Client()
                else -> WiFiShareRoute.Home
            }
            navController.navigate(dest) { launchSingleTop = true }
            onConsumeNotificationRoute()
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val topItems = remember {
        listOf(
            TopLevelNavItem("Home", Icons.Default.Dashboard, WiFiShareRoute.Home, "nav_home"),
            TopLevelNavItem("Host", Icons.Default.Router, WiFiShareRoute.Host, "nav_host"),
            TopLevelNavItem("Client", Icons.Default.VpnKey, WiFiShareRoute.Client(), "nav_client"),
            TopLevelNavItem("Browser", Icons.Default.Public, WiFiShareRoute.Browser, "nav_browser")
        )
    }

    val showTopLevelNav = !isFullScreen && currentDest != null && (
        currentDest.hasRoute<WiFiShareRoute.Home>() ||
            currentDest.hasRoute<WiFiShareRoute.Host>() ||
            currentDest.hasRoute<WiFiShareRoute.Client>() ||
            currentDest.hasRoute<WiFiShareRoute.Browser>()
        )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showTopLevelNav && !useRail) {
                    NavigationBar(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        topItems.forEach { item ->
                            val selected = when (item.route) {
                                is WiFiShareRoute.Home -> currentDest?.hasRoute<WiFiShareRoute.Home>() == true
                                is WiFiShareRoute.Host -> currentDest?.hasRoute<WiFiShareRoute.Host>() == true
                                is WiFiShareRoute.Client -> currentDest?.hasRoute<WiFiShareRoute.Client>() == true
                                is WiFiShareRoute.Browser -> currentDest?.hasRoute<WiFiShareRoute.Browser>() == true
                                else -> false
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(WiFiShareRoute.Home) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }
            }
        ) { scaffoldPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (showTopLevelNav && useRail) {
                    NavigationRail {
                        topItems.forEach { item ->
                            val selected = when (item.route) {
                                is WiFiShareRoute.Home -> currentDest?.hasRoute<WiFiShareRoute.Home>() == true
                                is WiFiShareRoute.Host -> currentDest?.hasRoute<WiFiShareRoute.Host>() == true
                                is WiFiShareRoute.Client -> currentDest?.hasRoute<WiFiShareRoute.Client>() == true
                                is WiFiShareRoute.Browser -> currentDest?.hasRoute<WiFiShareRoute.Browser>() == true
                                else -> false
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(WiFiShareRoute.Home) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }

                val contentPad = if (useRail) PaddingValues(0.dp) else scaffoldPadding

                NavHost(
                    navController = navController,
                    startDestination = WiFiShareRoute.Splash,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (useRail) it.padding(scaffoldPadding) else it }
                ) {
                    composable<WiFiShareRoute.Splash> {
                        SplashScreen(
                            onFinished = {
                                val next: WiFiShareRoute = when {
                                    !settings.onboardingCompleted -> WiFiShareRoute.Welcome
                                    !authSession.isSignedIn -> WiFiShareRoute.Auth
                                    else -> WiFiShareRoute.Home
                                }
                                navController.navigate(next) {
                                    popUpTo(WiFiShareRoute.Splash) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable<WiFiShareRoute.Welcome> {
                        WelcomeScreen(
                            onContinue = {
                                viewModel.completeOnboarding()
                                val next = if (authSession.isSignedIn) WiFiShareRoute.Home else WiFiShareRoute.Auth
                                navController.navigate(next) {
                                    popUpTo(WiFiShareRoute.Welcome) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable<WiFiShareRoute.Auth> {
                        AuthScreen(
                            isBusy = authBusy,
                            errorMessage = authMessage,
                            onLogin = viewModel::login,
                            onRegister = viewModel::register,
                            onClearError = viewModel::clearAuthMessage
                        )
                    }

                    composable<WiFiShareRoute.Home> {
                        HomeDashboardScreen(
                            contentPadding = contentPad,
                            authSession = authSession,
                            networkInfo = activeNetwork,
                            tunnelState = tunnelState,
                            traffic = traffic,
                            connectedPeersCount = connectedPeers.size,
                            pendingRequestsCount = pendingRequests.size,
                            isBackgroundServiceActive = isHostServiceRunning || isClientServiceRunning,
                            onNavigateHost = { navController.navigate(WiFiShareRoute.Host) },
                            onNavigateClient = { navController.navigate(WiFiShareRoute.Client()) },
                            onRequestOfflineInternet = {
                                viewModel.requestInternetOffline()
                                navController.navigate(WiFiShareRoute.Client())
                            },
                            onNavigateBrowser = { navController.navigate(WiFiShareRoute.Browser) },
                            onNavigateDownloads = { navController.navigate(WiFiShareRoute.Downloads) },
                            onNavigateVpn = { navController.navigate(WiFiShareRoute.VpnConnection) },
                            onNavigateTraffic = { navController.navigate(WiFiShareRoute.TrafficStats) },
                            onNavigatePrivacy = { navController.navigate(WiFiShareRoute.PrivacySecurity) },
                            onNavigateSettings = { navController.navigate(WiFiShareRoute.Settings) },
                            onNavigateDiagnostics = { navController.navigate(WiFiShareRoute.Troubleshooting) }
                        )
                    }

                    composable<WiFiShareRoute.Host> {
                        HostModeScreen(
                            contentPadding = contentPad,
                            networkInfo = activeNetwork,
                            tunnelState = tunnelState,
                            sharingCode = sharingCode,
                            traffic = traffic,
                            settings = settings,
                            connectedPeers = connectedPeers,
                            pendingRequests = pendingRequests,
                            nearbyOfflineNeeds = nearbyOfflineNeeds,
                            isHostServiceRunning = isHostServiceRunning,
                            offlineP2pState = offlineP2pState,
                            onStartSharing = viewModel::startHostSharing,
                            onStopSharing = viewModel::stopHostSharing,
                            onStartOfflineWifiDirectGroup = viewModel::startHostOfflineWifiDirectGroup,
                            onStopOfflineWifiDirect = viewModel::stopOfflineWifiDirect,
                            onUpdatePermittedModes = { b, d, v ->
                                uiScope.launch { viewModel.settingsStore.setPermittedModes(b, d, v) }
                            },
                            onUpdateMaxDevices = { max ->
                                uiScope.launch { viewModel.settingsStore.setMaxConnectedDevices(max) }
                            },
                            onUpdateAutoAcceptTrusted = { autoAccept ->
                                uiScope.launch { viewModel.settingsStore.setAutoAcceptTrustedPeers(autoAccept) }
                            },
                            onUpdateAllowOpenOfflineRequests = { allowOpen ->
                                uiScope.launch { viewModel.settingsStore.setAllowOpenOfflineRequests(allowOpen) }
                            },
                            onUpdateAutoAcceptOpenRequests = { autoOpen ->
                                uiScope.launch { viewModel.settingsStore.setAutoAcceptOpenRequests(autoOpen) }
                            },
                            onApproveRequest = { req, trust ->
                                viewModel.approvePeerRequest(req, true, trust)
                            },
                            onRejectRequest = { req ->
                                viewModel.approvePeerRequest(req, false, false)
                            },
                            onOpenSharingCode = { navController.navigate(WiFiShareRoute.SharingCode) },
                            onOpenApprovals = { navController.navigate(WiFiShareRoute.Approvals) },
                            onOpenConnectedDevices = { navController.navigate(WiFiShareRoute.ConnectedDevices) }
                        )
                    }

                    composable<WiFiShareRoute.Client> { backStackEntry ->
                        val args = backStackEntry.toRoute<WiFiShareRoute.Client>()
                        ClientModeScreen(
                            contentPadding = contentPad,
                            initialCode = args.initialCode,
                            defaultPort = settings.directListenPort,
                            selectedMode = clientMode,
                            tunnelState = tunnelState,
                            hostInfo = joinedHost,
                            traffic = traffic,
                            isVpnActive = isVpnActive,
                            isClientServiceRunning = isClientServiceRunning,
                            discoveredHosts = discoveredHosts,
                            isScanningNearby = isNsdScanning,
                            offlineP2pState = offlineP2pState,
                            savedHosts = savedHosts,
                            onStartNearbyScan = viewModel::startNearbyHostScan,
                            onStopNearbyScan = viewModel::stopNearbyHostScan,
                            onScanOfflineWifiDirect = viewModel::scanOfflineWifiDirectPeers,
                            onConnectOfflineWifiDirectPeer = viewModel::connectOfflineWifiDirectPeer,
                            onSelectMode = viewModel::selectClientMode,
                            onRequestJoin = viewModel::requestClientJoin,
                            onRequestOfflineInternet = viewModel::requestInternetOffline,
                            onOpenQrScanner = { navController.navigate(WiFiShareRoute.QrScanner) },
                            onOpenBrowser = { navController.navigate(WiFiShareRoute.Browser) },
                            onOpenVpnScreen = { navController.navigate(WiFiShareRoute.VpnConnection) },
                            onDisconnect = viewModel::disconnectClient
                        )
                    }

                    composable<WiFiShareRoute.SharingCode> {
                        SharingCodeScreen(
                            sharingCode = sharingCode,
                            onRegenerate = viewModel::regenerateSharingCode,
                            onRevoke = viewModel::revokeSharingCodeAndStop,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.QrScanner> {
                        QrScannerScreen(
                            onCodeScanned = { code ->
                                navController.navigate(WiFiShareRoute.Client(initialCode = code)) {
                                    popUpTo(WiFiShareRoute.QrScanner) { inclusive = true }
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.Approvals> {
                        ApprovalScreen(
                            pendingRequests = pendingRequests,
                            nearbyOfflineNeeds = nearbyOfflineNeeds,
                            savedClients = savedClients,
                            autoAcceptTrusted = settings.autoAcceptTrustedPeers,
                            allowOpenOfflineRequests = settings.allowOpenOfflineRequests,
                            onToggleAutoAcceptTrusted = { enabled ->
                                uiScope.launch { viewModel.settingsStore.setAutoAcceptTrustedPeers(enabled) }
                            },
                            onToggleAllowOpenOfflineRequests = { enabled ->
                                uiScope.launch { viewModel.settingsStore.setAllowOpenOfflineRequests(enabled) }
                            },
                            onApprove = { req, trust -> viewModel.approvePeerRequest(req, true, trust) },
                            onReject = { req -> viewModel.approvePeerRequest(req, false, false) },
                            onSetClientTrusted = viewModel::setSavedPeerTrusted,
                            onRemoveSavedClient = viewModel::removeSavedPeer,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.ConnectedDevices> {
                        ConnectedDevicesScreen(
                            peers = connectedPeers,
                            onDisconnectPeer = viewModel::disconnectPeer,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.Browser> {
                        BrowserScreen(
                            contentPadding = contentPad,
                            browser = viewModel.browser,
                            tabs = tabs,
                            activeTabId = activeTabId,
                            isFullScreen = isFullScreen,
                            reloadTrigger = reloadTrigger,
                            navCommand = navCommand,
                            settings = settings,
                            tunnelState = tunnelState,
                            bookmarks = bookmarks,
                            history = history,
                            tunnelClientProvider = viewModel::getTunnelOkHttpClientOrNull,
                            canDownloadViaHost = viewModel.isDownloadAllowedBySession(),
                            onEnqueueDownload = { url, name, mime ->
                                val routed = (tunnelState as? TunnelState.Connected)?.role == "CLIENT"
                                viewModel.downloadEngine.enqueueDownload(
                                    url = url,
                                    suggestedFilename = name,
                                    mimeType = mime,
                                    routedViaHost = routed,
                                    subfolder = settings.downloadSubfolder
                                )
                                uiScope.launch { snackbarHostState.showSnackbar("Download queued: ${name ?: "file"}") }
                            },
                            onToggleBookmark = { title, url, current ->
                                uiScope.launch { viewModel.localRepo.toggleBookmark(title, url, current) }
                            },
                            onRecordHistory = { title, url ->
                                uiScope.launch { viewModel.localRepo.addHistory(title, url) }
                            },
                            onOpenTabs = { navController.navigate(WiFiShareRoute.Tabs) },
                            onOpenDownloads = { navController.navigate(WiFiShareRoute.Downloads) }
                        )
                    }

                    composable<WiFiShareRoute.Tabs> {
                        TabManagerScreen(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            homeUrl = settings.browserHomePage,
                            onSelectTab = viewModel.browser::selectTab,
                            onCloseTab = viewModel.browser::closeTab,
                            onNewTab = { isPriv -> viewModel.browser.openNewTab(settings.browserHomePage, isPriv) },
                            onCloseAll = { viewModel.browser.closeAllTabs(settings.browserHomePage) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.Downloads> {
                        val routed = (tunnelState as? TunnelState.Connected)?.role == "CLIENT"
                        DownloadsScreen(
                            downloads = downloads,
                            routedViaHost = routed,
                            canDownload = viewModel.isDownloadAllowedBySession(),
                            onEnqueueUrl = { url ->
                                viewModel.downloadEngine.enqueueDownload(
                                    url = url,
                                    routedViaHost = routed,
                                    subfolder = settings.downloadSubfolder
                                )
                            },
                            onPause = viewModel.downloadEngine::pauseDownload,
                            onResumeOrRetry = viewModel.downloadEngine::resumeOrRetry,
                            onCancel = viewModel.downloadEngine::cancelDownload,
                            onOpenFile = { entity ->
                                viewModel.downloadEngine.openCompletedFile(entity).onFailure {
                                    uiScope.launch { snackbarHostState.showSnackbar(it.message ?: "No app found to open file") }
                                }
                            },
                            onDelete = viewModel.downloadEngine::deleteRecord,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.TrafficStats> {
                        TrafficStatsScreen(
                            liveTraffic = traffic,
                            dailyUpload = dailyUp,
                            dailyDownload = dailyDown,
                            monthlyUpload = monthlyUp,
                            monthlyDownload = monthlyDown,
                            records = trafficRecords,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.VpnConnection> {
                        VpnConnectionScreen(
                            tunnelState = tunnelState,
                            isVpnActive = isVpnActive,
                            traffic = traffic,
                            settings = settings,
                            onStartVpn = viewModel::requestOrStartVpn,
                            onStopVpn = viewModel::stopVpnOnly,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.Settings> {
                        SettingsScreen(
                            settings = settings,
                            onUpdateTheme = { m -> uiScope.launch { viewModel.settingsStore.setThemeMode(m) } },
                            onUpdateDirectP2p = { port, nsd ->
                                uiScope.launch { viewModel.settingsStore.setDirectP2pSettings(port, nsd) }
                            },
                            onUpdateStun = { h, p -> uiScope.launch { viewModel.settingsStore.setStunEndpoint(h, p) } },
                            onUpdateBrowserHome = { u -> uiScope.launch { viewModel.settingsStore.setBrowserHomePage(u) } },
                            onUpdateSearchEngine = { e -> uiScope.launch { viewModel.settingsStore.setSearchEngine(e) } },
                            onUpdateAutoReconnect = { a -> uiScope.launch { viewModel.settingsStore.setAutoReconnect(a) } },
                            onUpdateDataLimit = { mb, e -> uiScope.launch { viewModel.settingsStore.setDataLimit(mb, e) } },
                            onUpdateSessionExpiry = { min -> uiScope.launch { viewModel.settingsStore.setSessionExpiryMinutes(min) } },
                            onUpdateVpnSettings = { mtu, dns, v6 ->
                                uiScope.launch { viewModel.settingsStore.setVpnSettings(mtu, dns, v6) }
                            },
                            onUpdateNotifications = { n -> uiScope.launch { viewModel.settingsStore.setNotificationsEnabled(n) } },
                            onClearHistory = {
                                uiScope.launch {
                                    viewModel.localRepo.clearHistory()
                                    snackbarHostState.showSnackbar("Browsing history cleared")
                                }
                            },
                            onClearCookies = {
                                viewModel.browser.clearCookiesAndStorage()
                                uiScope.launch { snackbarHostState.showSnackbar("Cookies and WebStorage cleared") }
                            },
                            onSignOut = {
                                viewModel.signOut()
                                navController.navigate(WiFiShareRoute.Auth) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.PrivacySecurity> {
                        PrivacySecurityScreen(
                            droppedReplayPackets = traffic.droppedReplayPackets,
                            dnsLeakProtection = settings.dnsLeakProtection,
                            deviceUuid = authSession.deviceUuid,
                            onClearHistory = { uiScope.launch { viewModel.localRepo.clearHistory() } },
                            onClearCookies = { viewModel.browser.clearCookiesAndStorage() },
                            onRevokeAndSignOut = {
                                viewModel.signOut()
                                navController.navigate(WiFiShareRoute.Auth) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WiFiShareRoute.Troubleshooting> {
                        TroubleshootingScreen(
                            probes = probes,
                            events = tunnelEvents,
                            aiState = aiUiState,
                            onRunProbes = viewModel::runNetworkDiagnosticsProbes,
                            onAskAi = viewModel::askAiDiagnostics,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
