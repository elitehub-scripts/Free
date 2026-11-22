# WiFiShare Android Setup & Troubleshooting

## Direct P2P Connectivity Notes
- **Same Wi-Fi or Hotspot**: Enable **Local LAN Discovery (NSD / mDNS)** in Settings, or scan the Host's QR code. Both phones can discover and connect directly over the local subnet.
- **Across Different Networks**: Because there is no remote relay server, direct peer-to-peer connections across separate networks require at least one reachable endpoint (e.g., port forwarding TCP port `51821` on the Host router, IPv6 reachability, or an existing overlay IP).
- **Whole-Device VPN Permission**: Mode C prompts for Android's system VPN consent via `VpnService.prepare()`. WiFiShare automatically excludes its own package from the TUN routes so the encrypted peer socket never loops back into the VPN.
- **In-App Diagnostics**: Open **Diagnostics & Troubleshooting** from the Home Dashboard to run live probes (Physical Interface, Room Storage, Direct Gateway Socket, NSD/mDNS, Optional STUN, and VPN Consent) or consult the AI Network Diagnostics Advisor.
