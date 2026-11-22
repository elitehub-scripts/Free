# WiFiShare Security Model

- **Zero External Server Exposure**: All accounts, session logs, peer history, bookmarks, downloads, and traffic counters stay strictly inside the app's private storage on your device.
- **Local Password Protection**: Local account passwords are never stored in plaintext; they are salted per account and hashed with **PBKDF2-HMAC-SHA256** (`120,000` iterations) and verified in constant time.
- **Hardware-Backed Session Vault**: Active session tokens are encrypted using **Android KeyStore AES-256-GCM**.
- **End-to-End Tunnel Cryptography**: Each sharing session uses fresh ephemeral **ECDH (`secp256r1`) + HKDF-SHA256 + AES-256-GCM** with AAD binding and a 64-packet sliding anti-replay window.
- **Explicit Host Authorization**: No Client can forward packets or streams until the Host explicitly taps **Approve**.
- **Client Isolation**: `HostGatewayForwarder` blocks packets targeting loopback (`127.0.0.0/8`), link-local, or virtual subnet peers (`10.88.0.0/24`), except tunneled DNS (`10.88.0.1`).
