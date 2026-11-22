# WiFiShare Installation Guide

WiFiShare is a **100% self-contained, serverless** Android internet-sharing application. All user profiles, saved host endpoints, sharing session logs, bookmarks, browsing history, downloads, and traffic statistics are stored locally on each device using Room SQLite, DataStore, and Android KeyStore.

## 1. Build & Install the Android APK
1. Open the project in Android Studio or build with `gradle assembleDebug` (using the local Gradle toolchain).
2. Install the APK on **two Android devices** (Android 7.0 / API 24 or newer).
3. Launch WiFiShare on both devices and create a local profile (saved securely on-device).

## 2. Pair Host and Client (Zero Server Needed)
1. **On the Host device** (with active Wi-Fi or mobile data):
   - Open **Host Mode** and tap **Start Sharing Internet**.
   - WiFiShare opens a direct P2P listener socket (`51821` by default) and advertises its session on the local network via mDNS/NSD (`_wifishare._tcp.`), while also encoding its IP/port/public key into the QR code and invite link.
2. **On the Client device**:
   - Open **Client Mode** and scan the Host's QR code, tap **Scan LAN** (when on the same Wi-Fi/Hotspot), or enter the Host IP, port, and sharing code.
   - Select **Mode A (Browser)**, **Mode B (Browser & Downloads)**, or **Mode C (Whole-Device VPN)** and tap **Request Direct Connection**.
3. **Explicit Host Authorization**:
   - On the Host device, open **Approvals** and tap **Approve**.
   - Both phones establish an end-to-end ECDH + AES-256-GCM encrypted tunnel immediately.
