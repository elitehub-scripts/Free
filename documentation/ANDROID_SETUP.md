# WiFiShare Android Configuration

## Required Permissions
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `CHANGE_NETWORK_STATE`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`
- `CAMERA` (runtime-requested only when scanning a Host QR code)
- `POST_NOTIFICATIONS` (runtime-requested on Android 13+ for active Host/VPN foreground status)
- `BIND_VPN_SERVICE` (system-enforced for `WiFiShareVpnService` in Mode C)

## Local Build Verification
- Run unit and Robolectric tests: `gradle :app:testDebugUnitTest`
- Build debug APK: `gradle assembleDebug`
