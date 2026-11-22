package com.example.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.example.settings.SearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "https://duckduckgo.com",
    val isPrivate: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecureHttps: Boolean = true
)

class BrowserStateHolder {

    private val _tabs = MutableStateFlow(listOf(BrowserTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullScreen: StateFlow<Boolean> = _isFullScreen.asStateFlow()

    private val _reloadTrigger = MutableStateFlow(0L)
    val reloadTrigger: StateFlow<Long> = _reloadTrigger.asStateFlow()

    private val _navCommand = MutableStateFlow<Pair<String, Long>?>(null) // "BACK"|"FORWARD"|"HOME"|url
    val navCommand: StateFlow<Pair<String, Long>?> = _navCommand.asStateFlow()

    fun activeTab(): BrowserTab =
        _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: _tabs.value.first()

    fun resolveInputToUrl(input: String, searchEngine: SearchEngine): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://duckduckgo.com"
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val looksLikeDomain = !trimmed.contains(" ") && trimmed.contains(".") && trimmed.length >= 4
        return if (looksLikeDomain) {
            "https://$trimmed"
        } else {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            String.format(searchEngine.queryTemplate, encoded)
        }
    }

    fun openNewTab(url: String = "https://duckduckgo.com", isPrivate: Boolean = false): BrowserTab {
        val tab = BrowserTab(
            title = if (isPrivate) "Private Tab" else "New Tab",
            url = url,
            isPrivate = isPrivate
        )
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
        return tab
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    fun closeTab(tabId: String) {
        val remaining = _tabs.value.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val fresh = BrowserTab()
            _tabs.value = listOf(fresh)
            _activeTabId.value = fresh.id
        } else {
            _tabs.value = remaining
            if (_activeTabId.value == tabId) {
                _activeTabId.value = remaining.last().id
            }
        }
    }

    fun closeAllTabs(defaultHome: String) {
        val fresh = BrowserTab(url = defaultHome)
        _tabs.value = listOf(fresh)
        _activeTabId.value = fresh.id
    }

    fun updateTabState(
        tabId: String,
        url: String? = null,
        title: String? = null,
        isLoading: Boolean? = null,
        progress: Int? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null
    ) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id != tabId) tab
            else tab.copy(
                url = url ?: tab.url,
                title = title?.takeIf { it.isNotBlank() } ?: tab.title,
                isLoading = isLoading ?: tab.isLoading,
                progress = progress ?: tab.progress,
                canGoBack = canGoBack ?: tab.canGoBack,
                canGoForward = canGoForward ?: tab.canGoForward,
                isSecureHttps = (url ?: tab.url).startsWith("https://", ignoreCase = true)
            )
        }
    }

    fun toggleDesktopMode(tabId: String) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(isDesktopMode = !it.isDesktopMode) else it
        }
        triggerReload()
    }

    fun toggleFullScreen() {
        _isFullScreen.value = !_isFullScreen.value
    }

    fun navigateTo(url: String) {
        _navCommand.value = url to System.nanoTime()
    }

    fun goBack() {
        _navCommand.value = "CMD_BACK" to System.nanoTime()
    }

    fun goForward() {
        _navCommand.value = "CMD_FORWARD" to System.nanoTime()
    }

    fun triggerReload() {
        _reloadTrigger.value = System.nanoTime()
    }

    fun clearCookiesAndStorage() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}
