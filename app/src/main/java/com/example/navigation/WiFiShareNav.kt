package com.example.navigation

import kotlinx.serialization.Serializable

sealed interface WiFiShareRoute {
    @Serializable data object Splash : WiFiShareRoute
    @Serializable data object Welcome : WiFiShareRoute
    @Serializable data object Auth : WiFiShareRoute
    @Serializable data object Home : WiFiShareRoute
    @Serializable data object Host : WiFiShareRoute
    @Serializable data class Client(val initialCode: String = "") : WiFiShareRoute
    @Serializable data object SharingCode : WiFiShareRoute
    @Serializable data object QrScanner : WiFiShareRoute
    @Serializable data object Approvals : WiFiShareRoute
    @Serializable data object Browser : WiFiShareRoute
    @Serializable data object Tabs : WiFiShareRoute
    @Serializable data object Downloads : WiFiShareRoute
    @Serializable data object ConnectedDevices : WiFiShareRoute
    @Serializable data object TrafficStats : WiFiShareRoute
    @Serializable data object VpnConnection : WiFiShareRoute
    @Serializable data object Settings : WiFiShareRoute
    @Serializable data object PrivacySecurity : WiFiShareRoute
    @Serializable data object Troubleshooting : WiFiShareRoute
}
