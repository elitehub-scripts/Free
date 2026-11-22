package com.example.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.browser.BrowserStateHolder
import com.example.browser.BrowserTab
import com.example.database.BookmarkEntity
import com.example.database.DownloadEntity
import com.example.database.HistoryEntity
import com.example.model.TunnelState
import com.example.settings.WiFiShareSettings
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.components.WiFiShareTopBar
import com.example.ui.components.formatBytes
import com.example.ui.components.formatSpeed
import com.example.ui.theme.LocalWiFiShareColors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    contentPadding: PaddingValues,
    browser: BrowserStateHolder,
    tabs: List<BrowserTab>,
    activeTabId: String,
    isFullScreen: Boolean,
    reloadTrigger: Long,
    navCommand: Pair<String, Long>?,
    settings: WiFiShareSettings,
    tunnelState: TunnelState,
    bookmarks: List<BookmarkEntity>,
    history: List<HistoryEntity>,
    tunnelClientProvider: () -> OkHttpClient?,
    canDownloadViaHost: Boolean,
    onEnqueueDownload: (url: String, filename: String?, mimeType: String?) -> Unit,
    onToggleBookmark: (title: String, url: String, isCurrentlyBookmarked: Boolean) -> Unit,
    onRecordHistory: (title: String, url: String) -> Unit,
    onOpenTabs: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var addressInput by remember(activeTab.id, activeTab.url) { mutableStateOf(activeTab.url) }
    var showOverflow by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var pendingSitePermission by remember { mutableStateOf<PermissionRequest?>(null) }
    var downloadBlockedAlert by remember { mutableStateOf<String?>(null) }

    val isBookmarked = bookmarks.any { it.url == activeTab.url }
    val isRoutedThroughHost = (tunnelState as? TunnelState.Connected)?.role == "CLIENT"

    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(reloadTrigger) {
        if (reloadTrigger > 0L) {
            webViewRef?.settings?.userAgentString = if (activeTab.isDesktopMode) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            } else {
                WebSettings.getDefaultUserAgent(webViewRef?.context)
            }
            webViewRef?.reload()
        }
    }

    LaunchedEffect(navCommand) {
        val cmd = navCommand?.first ?: return@LaunchedEffect
        when (cmd) {
            "CMD_BACK" -> if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
            "CMD_FORWARD" -> if (webViewRef?.canGoForward() == true) webViewRef?.goForward()
            else -> webViewRef?.loadUrl(cmd)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = if (isFullScreen) 0.dp else contentPadding.calculateBottomPadding())
    ) {
        if (!isFullScreen) {
            // Address Bar & Tunnel Indicator
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = if (isRoutedThroughHost) "Tunneled via Host" else "Direct Network",
                        color = if (isRoutedThroughHost) semantic.success else semantic.cyanAccent
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (activeTab.isPrivate) {
                            StatusPill("Private", semantic.warning, leadingIcon = Icons.Default.VisibilityOff)
                        }
                        if (activeTab.isDesktopMode) {
                            StatusPill("Desktop", semantic.cobaltAccent, leadingIcon = Icons.Default.DesktopWindows)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        singleLine = true,
                        placeholder = { Text("Search or enter URL") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = if (activeTab.isSecureHttps) "HTTPS" else "HTTP",
                                tint = if (activeTab.isSecureHttps) semantic.success else semantic.warning,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = browser.resolveInputToUrl(addressInput, settings.searchEngine)
                                addressInput = target
                                webViewRef?.loadUrl(target)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browser_address_input")
                    )

                    IconButton(
                        onClick = onOpenTabs,
                        modifier = Modifier.testTag("browser_tabs_btn")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tab, contentDescription = "Tabs (${tabs.size})")
                            Text("${tabs.size}", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { showOverflow = true },
                            modifier = Modifier.testTag("browser_menu_btn")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Browser menu")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isBookmarked) "Remove Bookmark" else "Bookmark Page") },
                                leadingIcon = {
                                    Icon(
                                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    onToggleBookmark(activeTab.title, activeTab.url, isBookmarked)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Bookmarks") },
                                leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                                onClick = { showOverflow = false; showBookmarksSheet = true }
                            )
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                onClick = { showOverflow = false; showHistorySheet = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Downloads") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = { showOverflow = false; onOpenDownloads() }
                            )
                            DropdownMenuItem(
                                text = { Text(if (activeTab.isDesktopMode) "Mobile Site" else "Desktop Site") },
                                leadingIcon = { Icon(Icons.Default.DesktopWindows, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    browser.toggleDesktopMode(activeTab.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Full Screen") },
                                leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                                onClick = { showOverflow = false; browser.toggleFullScreen() }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Cookies") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    browser.clearCookiesAndStorage()
                                }
                            )
                        }
                    }
                }
            }
        }

        if (activeTab.isLoading) {
            LinearProgressIndicator(
                progress = { (activeTab.progress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Real Android WebView
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        this.getSettings().apply {
                            javaScriptEnabled = true
                            domStorageEnabled = !activeTab.isPrivate
                            allowFileAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }

                        CookieManager.getInstance().setAcceptCookie(!activeTab.isPrivate)

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                val urlStr = req.url?.toString() ?: return null
                                val tunnelClient = tunnelClientProvider()
                                // Route GET HTTP/HTTPS requests over active encrypted Host tunnel when connected in Mode A/B
                                if (tunnelClient != null && req.method.equals("GET", ignoreCase = true) &&
                                    (urlStr.startsWith("https://") || urlStr.startsWith("http://"))
                                ) {
                                    return runCatching {
                                        val okReqBuilder = Request.Builder().url(urlStr)
                                        req.requestHeaders.forEach { (k, v) ->
                                            if (!k.equals("Host", ignoreCase = true)) {
                                                okReqBuilder.addHeader(k, v)
                                            }
                                        }
                                        val resp = tunnelClient.newCall(okReqBuilder.build()).execute()
                                        val contentType = resp.header("Content-Type", "text/html; charset=utf-8") ?: "text/html"
                                        val mime = contentType.substringBefore(";").trim()
                                        val charset = contentType.substringAfter("charset=", "utf-8").trim()
                                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                                        WebResourceResponse(mime, charset, ByteArrayInputStream(bytes))
                                    }.getOrElse { err ->
                                        val html = """
                                            <html><body style="background:#060D1A;color:#F1F6FF;font-family:sans-serif;padding:24px;">
                                            <h3>Host Tunnel Request Failed</h3>
                                            <p>${err.localizedMessage ?: "Unable to fetch through Host tunnel."}</p>
                                            </body></html>
                                        """.trimIndent()
                                        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val finalUrl = url ?: return
                                addressInput = finalUrl
                                val pageTitle = view?.title ?: finalUrl
                                browser.updateTabState(
                                    tabId = activeTab.id,
                                    url = finalUrl,
                                    title = pageTitle,
                                    isLoading = false,
                                    progress = 100,
                                    canGoBack = view?.canGoBack() ?: false,
                                    canGoForward = view?.canGoForward() ?: false
                                )
                                if (!activeTab.isPrivate) {
                                    onRecordHistory(pageTitle, finalUrl)
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                browser.updateTabState(
                                    tabId = activeTab.id,
                                    isLoading = newProgress < 100,
                                    progress = newProgress
                                )
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                browser.updateTabState(tabId = activeTab.id, title = title)
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = filePathCallback
                                uploadLauncher.launch(arrayOf("*/*"))
                                return true
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                pendingSitePermission = request
                            }
                        }

                        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                            if (!canDownloadViaHost) {
                                downloadBlockedAlert = "The Host only permitted Mode A (Browser Only). Downloads are blocked for this session."
                            } else {
                                val guessed = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                                onEnqueueDownload(url, guessed, mimeType)
                            }
                        }

                        loadUrl(activeTab.url)
                        webViewRef = this
                    }
                },
                update = { wv ->
                    webViewRef = wv
                    if (wv.url != null && wv.url != activeTab.url && !activeTab.isLoading) {
                        // Keep tab switches synced
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isFullScreen) {
                IconButton(
                    onClick = { browser.toggleFullScreen() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit full screen")
                }
            }
        }

        if (!isFullScreen) {
            // Bottom Browser Controls Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { browser.goBack() },
                    enabled = activeTab.canGoBack,
                    modifier = Modifier.testTag("browser_back_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(
                    onClick = { browser.goForward() },
                    enabled = activeTab.canGoForward,
                    modifier = Modifier.testTag("browser_forward_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.testTag("browser_refresh_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload")
                }
                IconButton(
                    onClick = { webViewRef?.loadUrl(settings.browserHomePage) },
                    modifier = Modifier.testTag("browser_home_btn")
                ) {
                    Icon(Icons.Default.Home, contentDescription = "Home Page")
                }
                IconButton(
                    onClick = onOpenDownloads,
                    modifier = Modifier.testTag("browser_downloads_btn")
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Downloads Manager")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fileChooserCallback?.onReceiveValue(null)
            webViewRef?.destroy()
        }
    }

    if (showBookmarksSheet) {
        ModalBottomSheet(onDismissRequest = { showBookmarksSheet = false }) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
                if (bookmarks.isEmpty()) {
                    Text("No saved bookmarks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    bookmarks.forEach { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showBookmarksSheet = false
                                    webViewRef?.loadUrl(bm.url)
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(bm.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = { onToggleBookmark(bm.title, bm.url, true) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove bookmark")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showHistorySheet) {
        ModalBottomSheet(onDismissRequest = { showHistorySheet = false }) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Browsing History", style = MaterialTheme.typography.titleLarge)
                if (history.isEmpty()) {
                    Text("No local browsing history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    history.take(25).forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showHistorySheet = false
                                    webViewRef?.loadUrl(item.url)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    pendingSitePermission?.let { req ->
        AlertDialog(
            onDismissRequest = { req.deny(); pendingSitePermission = null },
            title = { Text("Website Permission Request") },
            text = { Text("${req.origin} requests access to: ${req.resources.joinToString()}") },
            confirmButton = {
                TextButton(onClick = { req.grant(req.resources); pendingSitePermission = null }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { req.deny(); pendingSitePermission = null }) {
                    Text("Deny")
                }
            }
        )
    }

    downloadBlockedAlert?.let { msg ->
        AlertDialog(
            onDismissRequest = { downloadBlockedAlert = null },
            title = { Text("Download Restricted") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { downloadBlockedAlert = null }) { Text("OK") }
            }
        )
    }
}

@Composable
fun TabManagerScreen(
    tabs: List<BrowserTab>,
    activeTabId: String,
    homeUrl: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onCloseAll: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            WiFiShareTopBar(
                title = "Tabs (${tabs.size})",
                onBack = onBack,
                actions = {
                    TextButton(onClick = onCloseAll) { Text("Close All") }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onNewTab(false); onBack() },
                    modifier = Modifier.weight(1f).testTag("new_normal_tab_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("New Tab")
                }
                OutlinedButton(
                    onClick = { onNewTab(true); onBack() },
                    modifier = Modifier.weight(1f).testTag("new_private_tab_btn")
                ) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Private Tab")
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (tab.id == activeTabId) MaterialTheme.colorScheme.primary else LocalWiFiShareColors.current.glassBorder,
                        onClick = { onSelectTab(tab.id); onBack() }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (tab.isPrivate) {
                                        Icon(Icons.Default.VisibilityOff, contentDescription = "Private", modifier = Modifier.size(14.dp))
                                    }
                                    Text(tab.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    tab.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onCloseTab(tab.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close tab")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    routedViaHost: Boolean,
    canDownload: Boolean,
    onEnqueueUrl: (String) -> Unit,
    onPause: (String) -> Unit,
    onResumeOrRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    val semantic = LocalWiFiShareColors.current
    var manualUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = { WiFiShareTopBar(title = "Downloads Manager", onBack = onBack) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        label = if (routedViaHost) "Downloading via Host Tunnel" else "Direct Download",
                        color = if (routedViaHost) semantic.success else semantic.cyanAccent
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualUrl,
                            onValueChange = { manualUrl = it },
                            label = { Text("Direct File URL (HTTPS)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("download_url_input")
                        )
                        OutlinedButton(
                            onClick = {
                                if (manualUrl.isNotBlank()) {
                                    onEnqueueUrl(manualUrl.trim())
                                    manualUrl = ""
                                }
                            },
                            enabled = canDownload && manualUrl.isNotBlank(),
                            modifier = Modifier.height(54.dp).testTag("download_start_btn")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Start download")
                        }
                    }
                }
            }

            if (downloads.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No downloads yet. Tap a download link in the Built-in Browser or paste an HTTPS file URL above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(downloads, key = { it.id }) { item ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        item.filename,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val statusColor = when (item.status) {
                                        "COMPLETED" -> semantic.success
                                        "DOWNLOADING" -> semantic.cyanAccent
                                        "PAUSED" -> semantic.warning
                                        "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    StatusPill(item.status, statusColor)
                                }

                                val progress = if (item.totalBytes > 0L) {
                                    (item.bytesDownloaded.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else 0f

                                if (item.status == "DOWNLOADING" || item.status == "PAUSED") {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                val sizeText = if (item.totalBytes > 0L) {
                                    "${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}"
                                } else {
                                    formatBytes(item.bytesDownloaded)
                                }
                                Text(
                                    text = "$sizeText${if (item.speedBps > 0L) " • ${formatSpeed(item.speedBps)}" else ""}${if (item.routedViaHost) " • Via Host" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (!item.errorMessage.isNullOrBlank()) {
                                    Text(
                                        item.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    when (item.status) {
                                        "DOWNLOADING" -> {
                                            IconButton(onClick = { onPause(item.id) }) {
                                                Icon(Icons.Default.Pause, contentDescription = "Pause")
                                            }
                                            IconButton(onClick = { onCancel(item.id) }) {
                                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                                            }
                                        }
                                        "PAUSED", "FAILED", "CANCELLED" -> {
                                            IconButton(onClick = { onResumeOrRetry(item.id) }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume or Retry")
                                            }
                                            IconButton(onClick = { onDelete(item.id) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                                            }
                                        }
                                        "COMPLETED" -> {
                                            OutlinedButton(onClick = { onOpenFile(item) }) {
                                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.size(6.dp))
                                                Text("Open")
                                            }
                                            IconButton(onClick = { onDelete(item.id) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete")
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
    }
}
