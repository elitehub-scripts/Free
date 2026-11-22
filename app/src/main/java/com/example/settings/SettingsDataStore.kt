package com.example.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wifiShareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wifishare_settings"
)

enum class SearchEngine(val label: String, val queryTemplate: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s")
}

data class WiFiShareSettings(
    val onboardingCompleted: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val directListenPort: Int = 51821,
    val enableNsdDiscovery: Boolean = true,
    val stunHost: String = "stun.l.google.com",
    val stunPort: Int = 19302,
    val browserHomePage: String = "https://duckduckgo.com",
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val downloadSubfolder: String = "WiFiShare",
    val autoReconnect: Boolean = true,
    val allowBrowserMode: Boolean = true,
    val allowDownloadsMode: Boolean = true,
    val allowVpnMode: Boolean = true,
    val maxConnectedDevices: Int = 4,
    val dataLimitMb: Long = 2048L,
    val enforceDataLimit: Boolean = false,
    val sessionExpiryMinutes: Int = 120,
    val vpnMtu: Int = 1400,
    val dnsLeakProtection: Boolean = true,
    val ipv6Enabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val autoAcceptTrustedPeers: Boolean = true,
    val allowOpenOfflineRequests: Boolean = true
)

class SettingsDataStore(private val context: Context) {

    val settingsFlow: Flow<WiFiShareSettings> = context.wifiShareDataStore.data.map { prefs ->
        WiFiShareSettings(
            onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false,
            themeMode = runCatching {
                AppThemeMode.valueOf(prefs[KEY_THEME_MODE] ?: AppThemeMode.DARK.name)
            }.getOrDefault(AppThemeMode.DARK),
            directListenPort = prefs[KEY_DIRECT_LISTEN_PORT] ?: 51821,
            enableNsdDiscovery = prefs[KEY_ENABLE_NSD] ?: true,
            stunHost = prefs[KEY_STUN_HOST] ?: "stun.l.google.com",
            stunPort = prefs[KEY_STUN_PORT] ?: 19302,
            browserHomePage = prefs[KEY_BROWSER_HOME] ?: "https://duckduckgo.com",
            searchEngine = runCatching {
                SearchEngine.valueOf(prefs[KEY_SEARCH_ENGINE] ?: SearchEngine.DUCKDUCKGO.name)
            }.getOrDefault(SearchEngine.DUCKDUCKGO),
            downloadSubfolder = prefs[KEY_DOWNLOAD_SUBFOLDER] ?: "WiFiShare",
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
            allowBrowserMode = prefs[KEY_ALLOW_BROWSER] ?: true,
            allowDownloadsMode = prefs[KEY_ALLOW_DOWNLOADS] ?: true,
            allowVpnMode = prefs[KEY_ALLOW_VPN] ?: true,
            maxConnectedDevices = prefs[KEY_MAX_DEVICES] ?: 4,
            dataLimitMb = prefs[KEY_DATA_LIMIT_MB] ?: 2048L,
            enforceDataLimit = prefs[KEY_ENFORCE_DATA_LIMIT] ?: false,
            sessionExpiryMinutes = prefs[KEY_SESSION_EXPIRY_MIN] ?: 120,
            vpnMtu = prefs[KEY_VPN_MTU] ?: 1400,
            dnsLeakProtection = prefs[KEY_DNS_LEAK_PROTECTION] ?: true,
            ipv6Enabled = prefs[KEY_IPV6_ENABLED] ?: true,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: true,
            autoAcceptTrustedPeers = prefs[KEY_AUTO_ACCEPT_TRUSTED] ?: true,
            allowOpenOfflineRequests = prefs[KEY_ALLOW_OPEN_OFFLINE_REQUESTS] ?: true
        )
    }

    suspend fun setOnboardingCompleted(done: Boolean) = update { it[KEY_ONBOARDING_DONE] = done }
    suspend fun setThemeMode(mode: AppThemeMode) = update { it[KEY_THEME_MODE] = mode.name }
    suspend fun setDirectP2pSettings(listenPort: Int, enableNsd: Boolean) = update {
        it[KEY_DIRECT_LISTEN_PORT] = listenPort.coerceIn(1024, 65535)
        it[KEY_ENABLE_NSD] = enableNsd
    }
    suspend fun setStunEndpoint(host: String, port: Int) = update {
        it[KEY_STUN_HOST] = host.trim()
        it[KEY_STUN_PORT] = port.coerceIn(1, 65535)
    }
    suspend fun setBrowserHomePage(url: String) = update { it[KEY_BROWSER_HOME] = url.trim() }
    suspend fun setSearchEngine(engine: SearchEngine) = update { it[KEY_SEARCH_ENGINE] = engine.name }
    suspend fun setAutoReconnect(enabled: Boolean) = update { it[KEY_AUTO_RECONNECT] = enabled }
    suspend fun setPermittedModes(browser: Boolean, downloads: Boolean, vpn: Boolean) = update {
        it[KEY_ALLOW_BROWSER] = browser
        it[KEY_ALLOW_DOWNLOADS] = downloads
        it[KEY_ALLOW_VPN] = vpn
    }
    suspend fun setMaxConnectedDevices(max: Int) = update { it[KEY_MAX_DEVICES] = max.coerceIn(1, 16) }
    suspend fun setDataLimit(mb: Long, enforce: Boolean) = update {
        it[KEY_DATA_LIMIT_MB] = mb.coerceAtLeast(10L)
        it[KEY_ENFORCE_DATA_LIMIT] = enforce
    }
    suspend fun setSessionExpiryMinutes(minutes: Int) = update {
        it[KEY_SESSION_EXPIRY_MIN] = minutes.coerceIn(5, 1440)
    }
    suspend fun setVpnSettings(mtu: Int, dnsLeak: Boolean, ipv6: Boolean) = update {
        it[KEY_VPN_MTU] = mtu.coerceIn(1280, 1500)
        it[KEY_DNS_LEAK_PROTECTION] = dnsLeak
        it[KEY_IPV6_ENABLED] = ipv6
    }
    suspend fun setNotificationsEnabled(enabled: Boolean) = update { it[KEY_NOTIFICATIONS] = enabled }
    suspend fun setAutoAcceptTrustedPeers(enabled: Boolean) = update { it[KEY_AUTO_ACCEPT_TRUSTED] = enabled }
    suspend fun setAllowOpenOfflineRequests(enabled: Boolean) = update { it[KEY_ALLOW_OPEN_OFFLINE_REQUESTS] = enabled }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.wifiShareDataStore.edit(block)
    }

    companion object {
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DIRECT_LISTEN_PORT = intPreferencesKey("direct_listen_port")
        private val KEY_ENABLE_NSD = booleanPreferencesKey("enable_nsd_discovery")
        private val KEY_STUN_HOST = stringPreferencesKey("stun_host")
        private val KEY_STUN_PORT = intPreferencesKey("stun_port")
        private val KEY_BROWSER_HOME = stringPreferencesKey("browser_home")
        private val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        private val KEY_DOWNLOAD_SUBFOLDER = stringPreferencesKey("download_subfolder")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_ALLOW_BROWSER = booleanPreferencesKey("allow_browser")
        private val KEY_ALLOW_DOWNLOADS = booleanPreferencesKey("allow_downloads")
        private val KEY_ALLOW_VPN = booleanPreferencesKey("allow_vpn")
        private val KEY_MAX_DEVICES = intPreferencesKey("max_devices")
        private val KEY_DATA_LIMIT_MB = longPreferencesKey("data_limit_mb")
        private val KEY_ENFORCE_DATA_LIMIT = booleanPreferencesKey("enforce_data_limit")
        private val KEY_SESSION_EXPIRY_MIN = intPreferencesKey("session_expiry_min")
        private val KEY_VPN_MTU = intPreferencesKey("vpn_mtu")
        private val KEY_DNS_LEAK_PROTECTION = booleanPreferencesKey("dns_leak_protection")
        private val KEY_IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val KEY_AUTO_ACCEPT_TRUSTED = booleanPreferencesKey("auto_accept_trusted_peers")
        private val KEY_ALLOW_OPEN_OFFLINE_REQUESTS = booleanPreferencesKey("allow_open_offline_requests")
    }
}
