# AIOPE Network Scanner & File Server - Context for Next Session

## Branch: `feat/network-scanner` (PR #120)
**Last working commit:** `12a4f7d`
**Base:** `main` at v4.6.2

---

## What Works

- Host discovery on WiFi/WG (TCP port 7 flood → ip neigh / /proc/net/arp)
- Cellular detection (skips CGNAT subnet scan)
- WAN IP fetch (ipify + amazonaws fallback)
- DNS lookup (forward + reverse) in UI
- Wake-on-LAN (magic packet)
- OUI vendor lookup (bundled oui.txt asset)
- Scanner icon in chat toolbar (NetworkCheck icon)
- Auto-start scan on screen open

## Bugs to Fix (Rewrite Required)

### 1. No MAC Addresses
- **Confirmed via ADB:** `/proc/net/arp` IS readable and shows 4 hosts with MACs
- **Confirmed via ADB:** `ip neigh` works at `/system/bin/ip`
- **Problem:** App code's `readNeighborTable()` returns empty — likely SELinux blocking `File("/proc/net/arp").bufferedReader()` from app context even though file permissions allow it
- **Fix:** Try `Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/arp"))` as primary, and `Runtime.getRuntime().exec(arrayOf("/system/bin/ip", "neigh"))` as fallback

### 2. IP Shown Twice in UI
- `HostRow` headline: `host.hostname ?: host.ip` → shows IP
- `HostRow` supporting: `host.ip` → shows IP again
- **Fix:** Headline = IP always. Supporting = MAC + vendor + hostname (if any)

### 3. Port Scan Only Sees Port 22
- Was scanning 1039 ports (1-1024 + extras) all at once → socket exhaustion on Android
- **Fix:** Default to 33 common ports. Offer "Full Scan" (1-1024) button. Batch 100 parallel. Timeout 500ms.

### 4. 5-Second Hang After Discovery
- `InetAddress.getByName(ip).canonicalHostName` does reverse DNS → 5s timeout per host with no PTR
- **Fix:** Don't do reverse DNS during discovery. Only on-demand in host detail sheet.

### 5. File Server HTTPS + PIN Auth (Not Yet Implemented)
- `bcpkix-jdk18on:1.85` dependency already added to `feature-chat/build.gradle.kts`
- Need: HTTPS toggle with self-signed cert (BouncyCastle X509v3CertificateBuilder)
- Need: PIN field in settings, auth check in handleClient (cookie/bearer/query param)
- Need: Login page HTML when PIN set but not provided

---

## Files to Rewrite

### `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/scanner/NetworkScanner.kt`

```
class NetworkScanner(context: Context) {
  // State
  _state: MutableStateFlow<ScanState>
  
  // Discovery
  suspend fun discoverHosts()
    - getLocalIp() → detect all interfaces, prefer WiFi/LAN /24, accept WG /32, show cell IP but skip scan
    - isCellularIp() → detect rmnet/ccmni/pdp or 100.64/10 CGNAT
    - TCP port 7 flood (all 254 simultaneous, 1000ms timeout) for ARP triggering
    - Also isReachable + TCP multiport (7,80,443,22,445,139,8080,3389) for direct detection
    - readNeighborTable() → cat /proc/net/arp via exec, then ip neigh, with logging
    - Merge: aliveHosts (from probes) + neighbors (from ARP) → deduplicate by IP
    - NO reverse DNS (causes 5s hang)
    - Build HostInfo with MAC + vendor lookup

  // Port Scanning  
  suspend fun scanPorts(ip, ports = DEFAULT_PORTS, timeout = 500)
    - Batch 100 parallel Socket.connect()
    - Banner grab for SSH (22), HTTP (80/443/8080)
    - UDP probes: DNS(53), DHCP(67), NTP(123), NetBIOS(137), SNMP(161), SSDP(1900), mDNS(5353), IKE(500), SIP(5060)

  // Utilities
  suspend fun fetchWanIp() — ipify primary, amazonaws fallback, 10s timeout
  suspend fun dnsLookup(host) / reverseDns(ip)
  suspend fun wakeOnLan(mac, ip)

  // ARP reading
  private fun readNeighborTable(): List<Pair<ip, mac>>
    - Approach 1: Runtime.exec("cat /proc/net/arp") → parse
    - Approach 2: Runtime.exec("/system/bin/ip neigh") → parse (skip FAILED/INCOMPLETE, find lladdr)
    - Log results at each step

  companion object {
    DEFAULT_PORTS = 33 common ports
    SERVICES map
    UDP payloads: NBSTAT, SNMP, DHCP, SSDP, IKE, SIP, mDNS builder, DNS builder
  }
}
```

### `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/scanner/ScannerScreen.kt`

```
ScannerScreen(onBack)
  - Auto-starts fetchWanIp + discoverHosts
  - Network info card: LAN IP + WAN IP (selectable)
  - Progress bar + phase text during scan
  - Host list: IP as headline, MAC + vendor as supporting, badge color for gateway
  - Refresh button in toolbar
  - DNS lookup button (globe icon) → DnsSheet

HostDetailSheet(host, scanner, onDismiss)
  - SelectionContainer for all text
  - IP, MAC, vendor, gateway badge
  - "Quick Scan" button (33 ports) + "Full Scan" button (1-1024)
  - Wake-on-LAN button (if MAC present)
  - Copy IP button
  - Port results table: port/proto, service, banner

DnsSheet(scanner, onDismiss)
  - Forward/Reverse toggle
  - Input field + Lookup button
  - Selectable result text
```

### `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/fileserver/FileServerService.kt`

Add to existing:
- `EXTRA_USE_HTTPS`, `EXTRA_PIN` extras
- `useHttps` / `pin` fields
- `createSslServerSocket(port)` — generate RSA 2048 keypair, X509v3CertificateBuilder (BouncyCastle), SSLContext
- PIN check in `handleClient()` — check Authorization: Bearer, ?pin= query, cookie; show login page if missing
- `sendPinPrompt(out)` — dark HTML login page with PIN input

### `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/fileserver/FileServerScreen.kt`

Add to existing:
- `useHttps` state (persisted in prefs)
- `pinCode` state (persisted in prefs)
- HTTPS toggle switch
- PIN text field
- Pass to `FileServerService.start(context, rootPath, port, useHttps, pin)`

---

## ADB Test Results (Phone: 192.168.1.3:34735, Android 15, Pixel)

```
$ ip neigh → shows .1, .2, .193, .228 with MACs (REACHABLE/STALE)
$ cat /proc/net/arp → shows same 4 hosts with flags 0x2
$ /proc/net/arp permissions: -r--r--r-- (world readable)
$ SELinux: Enforcing, label: u:object_r:proc_net:s0
$ nc -z 192.168.1.1 80 → OPEN (exit 0)
$ nc -z 192.168.1.1 443 → CLOSED (refused)
$ nslookup → not available on device
$ /system/bin/ip exists
```

---

## Dependencies Already Added

- `feature-chat/build.gradle.kts`: `implementation("org.bouncycastle:bcpkix-jdk18on:1.85")`
- `feature-chat/src/main/assets/oui.txt`: 74 common vendor entries

---

## Dev Loop Reminder

1. Branch → 2. Change → 3. PR → 4. Review → 5. Build APK → 6. Serve (192.168.1.2:3333) → 7. Install+test → 8. Merge → 9. Version bump → 10. GH Release
