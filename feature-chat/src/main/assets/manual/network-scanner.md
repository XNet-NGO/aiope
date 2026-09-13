# Network Scanner

keywords: network scanner, LAN scan, host discovery, ping sweep, ICMP, TCP probe, ARP table, ip neigh, /proc/net/arp, MAC address, OUI vendor lookup, oui.txt, port scan, TCP connect scan, UDP probe, banner grab, service detection, SERVICES map, gateway, WifiManager, DHCP, WAN IP, ipify, checkip, reverse DNS, wake-on-LAN, WoL, subnet scan, CIDR, StateFlow, ScanState, HostInfo, PortResult, NetworkScanner, ScannerScreen, live progress

AIOPE ships an on-device **network scanner** that maps the local network the phone
is attached to: it discovers live hosts, resolves their MAC address and hardware
vendor, scans TCP and UDP ports with service identification and banner grabbing, and
reports the gateway plus the device's own LAN and WAN addresses. Everything runs
directly on the device over raw sockets and the kernel ARP table — there is no
external scan engine. This page is written from the actual Kotlin source. Primary
files:

- `feature-chat/.../scanner/NetworkScanner.kt` — the scan engine (a process-wide singleton) and all its data models.
- `feature-chat/.../scanner/ScannerScreen.kt` — the Compose UI (`ScannerScreen`, host list, host detail sheet, DNS sheet, custom-scan sheet).

The scanner also depends on one asset: `oui.txt`, an IEEE OUI prefix → vendor
database loaded from `context.assets`.

## Data model

The scanner exposes three data classes (all defined at the top of `NetworkScanner.kt`).

**`HostInfo`** — one discovered host:

- `ip: String`
- `mac: String?` — from the ARP/neighbor table; `null` if not present there.
- `hostname: String?` — **declared but never populated during a scan.** No discovery
  path writes this field. Hostnames are resolved *on demand* via the reverse-DNS tool
  (see below), not during the sweep. Treat `hostname` as effectively always `null` in
  scan results.
- `vendor: String?` — resolved from `mac` via the OUI database; `null` if the MAC is
  unknown or absent.
- `openPorts: List<PortResult>` — populated only after a port scan / UDP probe of that host.
- `isGateway: Boolean` — `true` for the host whose IP equals the DHCP gateway.

**`PortResult`** — one open port:

- `port: Int`
- `protocol: String` — `"tcp"` or `"udp"` (default `"tcp"`).
- `state: String` — always `"open"` in current code (only open results are recorded).
- `service: String?` — a short service label from the `SERVICES` map (TCP) or the UDP probe name.
- `banner: String?` — grabbed banner text, when available.

**`ScanState`** — the single UI-facing state object streamed over a `StateFlow`:

- `isScanning: Boolean`
- `phase: String` — human-readable current step (e.g. `"Reading ARP table..."`).
- `hosts: List<HostInfo>`
- `progress: Float` — 0f..1f.
- `wanIp: String?`, `localIp: String?`, `localIps: List<String>`
- `error: String?`

## Live streaming progress

`NetworkScanner` holds a `MutableStateFlow<ScanState>` and exposes it read-only as
`val state: StateFlow<ScanState>`. Every phase of every scan mutates this flow, so the
UI updates live. `ScannerScreen` collects it with `collectAsState()` and renders:

- a network-info card showing `LAN:` (all `localIps` joined, or `localIp`) and `WAN:`;
- while `isScanning`, a `LinearProgressIndicator` bound to `state.progress`, the current
  `state.phase` text, and a **Cancel** button (`cancelScan()`);
- the sorted host list, each row colored by role (gateway = primary, has-open-ports =
  tertiary, otherwise outline) with a `GW` tag on the gateway.

Progress is reported incrementally, not just at the end. Host discovery drives progress
to `0.8f` during the sweep, bumps to `0.9f` while building results, then `1f` on
completion. A single `scanJob` is tracked; launching a new scan cancels the previous one
(`scanJob?.cancel()`).

The scanner is a singleton — `NetworkScanner.getInstance(context)` returns one
application-scoped instance, so scan state survives navigating in and out of the screen.

## Host discovery

`discoverHosts()` (fired via `launchDiscoverHosts()`) runs a multi-phase sweep:

1. **Detect local networking.** `getAllLocalIps()` enumerates non-loopback, non-cellular
   `NetworkInterface`s and collects IPv4 addresses in the private ranges `192.168.`,
   `10.`, and `172.`. `getGatewayIp()` reads the gateway from Wi-Fi DHCP. If there are no
   local IPs, it stops with `error = "No network connection"`.

2. **Skip cellular-only.** IPs on `rmnet`/`ccmni`/`pdp` interfaces, and CGNAT addresses
   (`100.64.0.0`–`100.127.255.255`), are treated as cellular by `isCellularIp(...)`. If
   nothing scannable remains, the sweep stops with phase
   `"Cellular network — subnet scan skipped"` — there is no meaningful /24 LAN to probe
   over mobile data.

3. **Sweep each /24.** `getScannableSubnets(...)` derives the `/24` prefixes to scan. For
   each subnet, hosts `.1`–`.254` are probed in chunks of 50 concurrent coroutines. Each
   host is tested by:
   - **ICMP reachability** first: `InetAddress.getByName(ip).isReachable(800)` (800 ms).
   - **TCP fallback** if ICMP fails: a connect attempt (400 ms) to any of ports
     `7, 80, 443, 22, 445, 139, 8080, 3389`; the first success marks the host alive.

   This ICMP-plus-TCP approach catches hosts that drop ping but keep a port open. Total
   work is `subnets.size * 254`.

4. **Read the neighbor table.** After a short settle delay, `readNeighborTable()` collects
   IP↔MAC pairs (see below). The final host set is the union of probe-alive IPs and ARP
   neighbors, de-duplicated.

5. **Build results.** Each IP becomes a `HostInfo` with its MAC (if known), vendor
   (`lookupVendor(mac)`), and `isGateway` flag, sorted numerically by IP
   (`ipToLong`). Final phase: `"Done — N hosts (M subnets)"`.

### MAC addresses via the neighbor table

`readNeighborTable()` does **not** send ARP itself; it reads what the kernel already
learned (the earlier connect/ping probes populate the ARP cache). It tries two sources
in order:

1. `cat /proc/net/arp` — parses each line, keeping entries whose flags are not `0x0` and
   whose MAC is a real `xx:xx:xx:...` (not all-zero).
2. If that yields nothing, `/system/bin/ip neigh` — parses `lladdr` entries, skipping
   `FAILED`/`INCOMPLETE` states.

MACs are upper-cased. On modern Android, `/proc/net/arp` is frequently unreadable or
empty for the app, in which case MAC/vendor columns will simply be blank for many hosts —
this is an OS restriction, not a scan failure.

### Vendor via OUI database

`lookupVendor(mac)` takes the first 6 hex digits of the MAC (the OUI), upper-cases them,
and looks them up in `ouiDb`. `loadOuiDb()` lazily reads the `oui.txt` asset, parsing
tab-separated `PREFIX<TAB>Vendor` lines and skipping `#` comments. If a MAC is unknown or
`null`, vendor is `null`.

## TCP port scan

`scanPorts(ip, ports = FULL_PORTS, timeout = 500)` (via `launchScanPorts(ip)`) is a
plain **TCP connect scan** — it opens a `Socket`, and a successful `connect(...)` within
the timeout means the port is open. Details straight from the code:

- **Default target is every port.** `launchScanPorts` defaults to `FULL_PORTS`, which is
  `(1..65535).toList()` — all 65,535 ports.
- **Adaptive concurrency and banners by size.** If the port count is `> 1000`, the scan
  uses a dedicated 1000-thread dispatcher, chunk size 1000, a fixed 500 ms timeout, **and
  no banner grabbing**. For `<= 1000` ports it uses `Dispatchers.IO`, chunk size 500, the
  caller's timeout, and **does** grab banners. So banner grabbing is *gated to scans of at
  most 1000 ports* — a full 65k sweep intentionally skips banners for speed.
- **Common-port second pass.** After the main pass, ports
  `21, 22, 23, 25, 53, 80, 443, 445, 8080, 8443, 3389, 5900` that weren't already found
  are retried once with a longer 1000 ms timeout (with banner grab), to catch slow
  services.
- **Service labels.** Each open port's `service` comes from the `SERVICES` map, which has
  **129 entries** (e.g. `22→ssh`, `80→http`, `443→https`, `445→smb`, `3389→rdp`,
  `3306→mysql`, `6379→redis`, `27017→mongodb`, `51820→wireguard`). Ports not in the map
  get a `null` service.
- Progress updates fire every 100 ports (and at completion). Results merge with any
  existing UDP results for that host and update `HostInfo.openPorts`. Final phase:
  `"Done — N TCP ports open"`.

There is also a curated `DEFAULT_PORTS` list of **129 common ports** available in the
companion object, though `launchScanPorts`/the UI default to the full range.

### Banner grabbing

`grabBanner(socket, ip, port)` reads a short identifying string (protocol-specific):

- **Port 22 (SSH):** reads the first line of the SSH identification string (up to 100 chars).
- **Ports 80 / 443 / 8080 / 8443 (HTTP):** sends `GET / HTTP/1.1` and extracts the
  `Server:` header and the `<title>` from the first 512 bytes of the response, joined as
  `server | title`.
- **Everything else (generic):** waits briefly (500 ms) for the service to speak first and
  captures up to 100 bytes.

Banners are best-effort; any failure yields `null`.

## UDP probe

`udpProbe(ip)` (via `launchUdpProbe(ip)`) sends purpose-built payloads to a fixed set of
UDP services and records any that reply. There are **32 UDP probes** in the `UDP_PROBES`
list, each a `Triple(port, name, payload)`:

`dns` (53), `dhcp` (67), `tftp` (69), `ntp` (123), `netbios` (137), `netbios-dgm` (138),
`snmp` (161), `snmptrap` (162), `ldap` (389), `ike` (500), `syslog` (514), `rip` (520),
`ipmi` (623), `openvpn` (1194), `citrix` (1604), `ssdp` (1900), `nfs` (2049), `stun`
(3478), `ws-discovery` (3702), `ike-nat` (4500), `sip` (5060), `mdns` (5353), `pcanywhere`
(5632), `sun-rpc` (6481), `ubiquiti` (10001), `memcached` (11211), `vxworks` (17185),
`quake` (27960), `plex` (32414), `ethernetip` (44818), `bacnet` (47808), `wireguard`
(51820).

`probeUdp(...)` sends the payload on a `DatagramSocket` (2 s receive timeout) and returns
up to 512 bytes of any reply. A reply marks the UDP port open — `state = "open"`. Because
UDP is connectionless, silence is ambiguous (filtered vs. no service), so only *responding*
ports are reported.

`parseUdpResponse(port, data)` turns replies into readable banners for several protocols:
NetBIOS name (137), SNMP printable string (161), SSDP `SERVER:` line (1900), first SIP
line (5060); ports 53/123/500/5353 report `"responded"`, and anything else reports
`"responded (<N>B)"` with the reply byte count. Progress advances per probe; final phase:
`"Done — N UDP services found"`.

## Gateway, local IPs, and WAN IP

- **Gateway** — `getGatewayIp()` reads `WifiManager.dhcpInfo.gateway` and formats it as a
  dotted quad. This uses the **deprecated** `WifiManager` DHCP API (annotated
  `@Suppress("DEPRECATION")`) and therefore only works when on Wi-Fi; off Wi-Fi it returns
  `null` and no host is flagged as gateway.
- **Local IPs** — `getAllLocalIps()` returns every private-range IPv4 on active,
  non-cellular interfaces (so multi-homed setups, e.g. Wi-Fi + a WireGuard tunnel, show
  multiple LAN addresses). IPv6 and loopback are excluded.
- **WAN IP** — `fetchWanIp()` (via `launchFetchWanIp()`) does an HTTPS `GET` to
  `https://api.ipify.org` (10 s timeouts). On any failure it falls back to
  `https://checkip.amazonaws.com`; if both fail, `wanIp` becomes `"Unavailable"`. On a
  non-200 it stores `"HTTP <code>"`. **This is the one part of the scanner that contacts
  the public internet** — everything else is on-LAN. `ScannerScreen` fetches the WAN IP
  automatically alongside host discovery on first open and on Rescan.

## Additional tools in the engine and UI

Beyond the core sweep, `NetworkScanner` provides:

- **`dnsLookup(host)`** — forward resolution; returns all A/AAAA addresses
  (`InetAddress.getAllByName`), newline-joined.
- **`reverseDns(ip)`** — on-demand PTR lookup via `canonicalHostName`; returns
  `"No PTR record"` when the name resolves back to the IP. This is the *only* way hostnames
  are obtained; the sweep never fills `HostInfo.hostname`.
- **`wakeOnLan(mac, ip)`** — builds a standard Wake-on-LAN magic packet (6×`0xFF` + the MAC
  repeated 16 times) and sends it as a UDP datagram to port 9. Returns `false` on a
  malformed MAC or send failure.
- **`scanSubnet(subnet)`** (`launchSubnetScan`) — the same ICMP+TCP `/24` sweep as
  discovery, but for an arbitrary subnet you type in — useful for the far side of a VPN /
  WireGuard tunnel.
- **`launchCustomScan(ips, ports, timeout)`** — port-scans an explicit list of IPs/ports.

The UI (`ScannerScreen`) surfaces these via a top-bar **Tune** (custom scan), **Public**
(DNS lookup), and **Refresh** (rescan) action, plus a per-host bottom sheet with **TCP
Scan**, **UDP Probe**, **Wake**, and **Copy IP** buttons. The custom-scan sheet parses IP
input as single IPs, dash ranges (`192.168.1.1-10`), and `/24` CIDR; ports as single
values and dash ranges (empty = all 65,535).

## Honest limitations

- **Hostname is never populated by the scan.** The `HostInfo.hostname` field exists but no
  discovery code writes it; use `reverseDns` on demand.
- **MAC/vendor depend on OS-readable ARP.** If `/proc/net/arp` and `ip neigh` are both
  restricted/empty (common on newer Android), MAC and vendor will be blank for most hosts.
- **Gateway detection is Wi-Fi-only** and uses a deprecated API; it is `null` on cellular
  or other transports.
- **TCP scan is connect-based** (full three-way handshake), not a stealth/SYN scan — it is
  visible to the target and its logs.
- **UDP results are reply-only.** Non-responding UDP ports are not reported as
  open/closed/filtered — only ports that actually answered a probe appear.
- **`state` is always `"open"`** in results; the scanner records open ports/services only,
  not closed or filtered ones.
- No claims are made here about stealth, evasion, or authorization — the scanner sends real
  probes from the device and should only be pointed at networks you are permitted to scan.
