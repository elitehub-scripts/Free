# WiFiShare Direct P2P Network Architecture

WiFiShare operates **without any external backend or relay server**:

## 1. Signaling & Discovery (On-Device)
- **Local Discovery (NSD / mDNS)**: `LocalNsdDiscovery` registers `_wifishare._tcp.` on the Host's Wi-Fi/Hotspot network so Clients can discover active sharing codes automatically.
- **Direct QR / Invite URI**: `QrCodeGenerator` embeds `code`, `host`, `port`, and `pub` inside `wifishare://join?...` for zero-server pairing across reachable networks.
- **Direct Gateway Socket**: `DirectHostGatewayServer` binds a TCP `ServerSocket` on the Host device, validates codes, device limits, and permitted modes, and requires explicit Host UI authorization (`OP_JOIN_PENDING` -> `OP_JOIN_APPROVED`) before forwarding traffic.

## 2. End-to-End Tunnel Encryption
- **Key Exchange**: Ephemeral ECDH (`secp256r1`) keypairs generated per session.
- **Key Derivation**: HKDF-SHA256 produces separate Host->Client and Client->Host AES-256-GCM keys and 4-byte IV salts.
- **Anti-Replay**: Monotonic 64-bit sequence numbers verified via a 64-packet sliding bitmap (`ReplayWindow`).

## 3. Unified Three-Mode Transport
- **Mode A & Mode B**: `LocalTunnelStreamMux` exposes a local loopback SOCKS5/HTTP stream multiplexer used by WebView and `DownloadManagerEngine` (`OkHttp`), tunneling TCP streams (`FRAME_STREAM_OPEN`, `FRAME_STREAM_DATA`, `FRAME_STREAM_CLOSE`) directly to `HostGatewayForwarder`.
- **Mode C (Whole-Device VPN)**: `WiFiShareVpnService` creates a TUN interface (`10.88.0.2/24`, `fd88:5746::2/64`, DNS `10.88.0.1`), excluding the WiFiShare app itself to avoid routing loops, and forwards raw IPv4/IPv6/DNS packets through the Host's `HostGatewayForwarder` bound to the Host's active physical network (`ConnectivityManager.activeNetwork`).

## 4. Local On-Device Storage
- **Room SQLite (`wifishare_local.db`)**: Stores `local_accounts`, `saved_host_sessions`, `saved_peers`, `bookmarks`, `browsing_history`, `downloads`, `traffic_records`, and `tunnel_events`.
- **DataStore (`wifishare_settings`)**: Stores listen port, NSD discovery toggle, VPN MTU/IPv6/DNS preferences, browser home page, and theme.
- **Android KeyStore (`wifishare_secure_vault`)**: Encrypts local session tokens with AES-256-GCM.
