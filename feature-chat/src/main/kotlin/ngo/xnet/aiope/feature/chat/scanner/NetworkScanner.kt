package ngo.xnet.aiope.feature.chat.scanner

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

// ── Data Models ──

data class HostInfo(
  val ip: String,
  val mac: String? = null,
  val hostname: String? = null,
  val vendor: String? = null,
  val openPorts: List<PortResult> = emptyList(),
  val isGateway: Boolean = false,
)

data class PortResult(
  val port: Int,
  val protocol: String = "tcp",
  val state: String = "open",
  val service: String? = null,
  val banner: String? = null,
)

data class ScanState(
  val isScanning: Boolean = false,
  val phase: String = "",
  val hosts: List<HostInfo> = emptyList(),
  val progress: Float = 0f,
  val wanIp: String? = null,
  val localIp: String? = null,
  val error: String? = null,
)

// ── Scanner Engine ──

class NetworkScanner(private val context: Context) {

  private val _state = MutableStateFlow(ScanState())
  val state: StateFlow<ScanState> = _state.asStateFlow()

  private val ouiDb: Map<String, String> by lazy { loadOuiDb() }

  // ══════════════════════════════════════════════════════════════
  // HOST DISCOVERY (PortAuthority approach: TCP7 flood → read ARP)
  // ══════════════════════════════════════════════════════════════

  suspend fun discoverHosts() = withContext(Dispatchers.IO) {
    _state.value = ScanState(isScanning = true, phase = "Detecting network...", wanIp = _state.value.wanIp)
    val localIp = getLocalIp()
    val gatewayIp = getGatewayIp()
    android.util.Log.i("Scanner", "localIp=$localIp gatewayIp=$gatewayIp")
    _state.value = _state.value.copy(localIp = localIp)

    if (localIp == null) {
      _state.value = _state.value.copy(isScanning = false, error = "No network connection")
      return@withContext
    }

    // Detect if we're on cellular (CGNAT ranges that shouldn't be scanned)
    val isCellular = isCellularIp(localIp)
    if (isCellular) {
      _state.value = _state.value.copy(
        isScanning = false,
        phase = "Cellular network — subnet scan skipped",
        progress = 1f,
      )
      return@withContext
    }

    val subnet = localIp.substringBeforeLast(".")

    // Phase 1: Probe entire subnet — TCP connect + isReachable simultaneously
    // Both trigger ARP, and isReachable directly tells us if host is up
    _state.value = _state.value.copy(phase = "Scanning $subnet.0/24...")
    val aliveHosts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    coroutineScope {
      (1..254).map { i ->
        async(Dispatchers.IO) {
          val ip = "$subnet.$i"
          var found = false
          // Method 1: InetAddress.isReachable (ICMP or TCP echo)
          try {
            if (InetAddress.getByName(ip).isReachable(1000)) {
              found = true
            }
          } catch (_: Exception) {}
          // Method 2: TCP connect to common ports
          if (!found) {
            val ports = intArrayOf(7, 80, 443, 22, 445, 139, 8080, 3389)
            for (port in ports) {
              try {
                Socket().use { s ->
                  s.tcpNoDelay = true
                  s.connect(InetSocketAddress(ip, port), 500)
                  found = true
                }
                break
              } catch (_: Exception) {}
            }
          }
          if (found) aliveHosts[ip] = true
          _state.value = _state.value.copy(progress = i / 254f)
        }
      }.awaitAll()
    }

    // Phase 2: Wait for ARP table to settle
    delay(500)

    // Phase 3: Read ARP table and merge with directly-found hosts
    _state.value = _state.value.copy(phase = "Reading results...")
    android.util.Log.i("Scanner", "Alive from probes: ${aliveHosts.size} - ${aliveHosts.keys.take(10)}")
    val neighbors = readNeighborTable().toMap() // ip -> mac
    android.util.Log.i("Scanner", "Neighbors from ARP: ${neighbors.size} - ${neighbors.keys.take(10)}")
    val allIps = (aliveHosts.keys + neighbors.keys).distinct()

    // Phase 4: Resolve hostnames
    _state.value = _state.value.copy(phase = "Resolving hostnames...")
    val hosts = coroutineScope {
      allIps.map { ip ->
        async(Dispatchers.IO) {
          val mac = neighbors[ip]
          val hostname = try {
            val addr = InetAddress.getByName(ip)
            val name = addr.canonicalHostName
            if (name != ip) name else null
          } catch (_: Exception) {
            null
          }
          HostInfo(
            ip = ip,
            mac = mac,
            hostname = hostname,
            vendor = lookupVendor(mac),
            isGateway = ip == gatewayIp,
          )
        }
      }.awaitAll()
    }.sortedBy { ipToLong(it.ip) }

    _state.value = _state.value.copy(
      isScanning = false,
      phase = "Done — ${hosts.size} hosts",
      progress = 1f,
      hosts = hosts,
    )
  }

  // ══════════════════════════════════════════════════════════════
  // PORT SCANNING
  // ══════════════════════════════════════════════════════════════

  suspend fun scanPorts(ip: String, ports: List<Int> = DEFAULT_PORTS, timeout: Int = 1000) = withContext(Dispatchers.IO) {
    _state.value = _state.value.copy(isScanning = true, phase = "Scanning ports on $ip...")
    val results = mutableListOf<PortResult>()

    coroutineScope {
      ports.chunked(100).forEachIndexed { chunkIdx, chunk ->
        chunk.map { port ->
          async(Dispatchers.IO) {
            _state.value = _state.value.copy(progress = (chunkIdx * 100 + chunk.indexOf(port)).toFloat() / ports.size)
            try {
              Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, port), timeout)
                val banner = grabBanner(s, ip, port)
                PortResult(port, "tcp", "open", SERVICES[port], banner)
              }
            } catch (_: Exception) {
              null
            }
          }
        }.awaitAll().filterNotNull().let { results.addAll(it) }
      }
    }

    // UDP probes
    results.addAll(probeUdpServices(ip))

    // Update host in state
    val updated = _state.value.hosts.map { h ->
      if (h.ip == ip) h.copy(openPorts = results.sortedBy { it.port }) else h
    }
    _state.value = _state.value.copy(isScanning = false, phase = "Done", progress = 1f, hosts = updated)
  }

  // ══════════════════════════════════════════════════════════════
  // WAN IP
  // ══════════════════════════════════════════════════════════════

  suspend fun fetchWanIp() = withContext(Dispatchers.IO) {
    try {
      val url = java.net.URL("https://api.ipify.org")
      val conn = url.openConnection() as java.net.HttpURLConnection
      conn.connectTimeout = 10000
      conn.readTimeout = 10000
      conn.instanceFollowRedirects = true
      val code = conn.responseCode
      if (code == 200) {
        val ip = conn.inputStream.bufferedReader().readText().trim()
        _state.value = _state.value.copy(wanIp = ip)
      } else {
        _state.value = _state.value.copy(wanIp = "HTTP $code")
      }
      conn.disconnect()
    } catch (e: Exception) {
      android.util.Log.e("Scanner", "WAN IP fetch failed: ${e.message}")
      // Try fallback
      try {
        val ip = java.net.URL("https://checkip.amazonaws.com").readText().trim()
        _state.value = _state.value.copy(wanIp = ip)
      } catch (_: Exception) {
        _state.value = _state.value.copy(wanIp = "Unavailable")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  // DNS LOOKUP
  // ══════════════════════════════════════════════════════════════

  suspend fun dnsLookup(host: String): String = withContext(Dispatchers.IO) {
    try {
      val addresses = InetAddress.getAllByName(host)
      addresses.joinToString("\n") { it.hostAddress ?: "" }
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  suspend fun reverseDns(ip: String): String = withContext(Dispatchers.IO) {
    try {
      val addr = InetAddress.getByName(ip)
      val hostname = addr.canonicalHostName
      if (hostname == ip) "No PTR record" else hostname
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  // ══════════════════════════════════════════════════════════════
  // WAKE-ON-LAN
  // ══════════════════════════════════════════════════════════════

  suspend fun wakeOnLan(mac: String, ip: String): Boolean = withContext(Dispatchers.IO) {
    try {
      val macBytes = mac.split("[:\\-]".toRegex()).map { it.toInt(16).toByte() }.toByteArray()
      if (macBytes.size != 6) return@withContext false

      // Magic packet: 6x 0xFF + 16x MAC
      val packet = ByteArray(6 + 16 * 6)
      for (i in 0..5) packet[i] = 0xFF.toByte()
      for (i in 0..15) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)

      val addr = InetAddress.getByName(ip)
      DatagramSocket().use { sock ->
        sock.send(DatagramPacket(packet, packet.size, addr, 9))
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  // ══════════════════════════════════════════════════════════════
  // PRIVATE HELPERS
  // ══════════════════════════════════════════════════════════════

  /** Read ARP/neighbor table. Tries multiple approaches for Android compatibility */
  private fun readNeighborTable(): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()

    // Approach 1: ip neigh (works on most Android with toybox/toolbox)
    try {
      val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh"))
      BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
        reader.lineSequence().forEach { line ->
          val parts = line.split("\\s+".toRegex())
          if (parts.size >= 5) {
            val ip = parts[0]
            val state = parts.last()
            if (state != "FAILED" && state != "INCOMPLETE") {
              // MAC is typically at index 4 after "lladdr"
              val llIdx = parts.indexOf("lladdr")
              val mac = if (llIdx >= 0 && llIdx + 1 < parts.size) parts[llIdx + 1].uppercase() else null
              if (mac != null && mac != "00:00:00:00:00:00" && mac.contains(":")) {
                results.add(ip to mac)
              }
            }
          }
        }
      }
      process.waitFor()
    } catch (_: Exception) {}

    if (results.isNotEmpty()) return results

    // Approach 2: /proc/net/arp (may be restricted on API 29+)
    try {
      File("/proc/net/arp").bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
          val parts = line.split("\\s+".toRegex())
          if (parts.size >= 4) {
            val ip = parts[0]
            val flags = parts[2]
            val mac = parts[3].uppercase()
            // flags 0x2 = complete entry, 0x0 = incomplete
            if (flags != "0x0" && mac != "00:00:00:00:00:00" && mac.contains(":")) {
              results.add(ip to mac)
            }
          }
        }
      }
    } catch (_: Exception) {}

    if (results.isNotEmpty()) return results

    // Approach 3: cat /proc/net/arp via shell (some Android restricts direct File access but allows exec)
    try {
      val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/arp"))
      BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
        reader.lineSequence().drop(1).forEach { line ->
          val parts = line.split("\\s+".toRegex())
          if (parts.size >= 4) {
            val ip = parts[0]
            val flags = parts[2]
            val mac = parts[3].uppercase()
            if (flags != "0x0" && mac != "00:00:00:00:00:00" && mac.contains(":")) {
              results.add(ip to mac)
            }
          }
        }
      }
      process.waitFor()
    } catch (_: Exception) {}

    return results
  }

  private fun grabBanner(socket: Socket, ip: String, port: Int): String? = try {
    socket.soTimeout = 1500
    when (port) {
      22 -> socket.getInputStream().bufferedReader().readLine()?.take(100)

      80, 443, 8080, 8443 -> {
        val out = socket.getOutputStream()
        out.write("GET / HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        val resp = socket.getInputStream().bufferedReader().readText().take(512)
        val server = Regex("Server:\\s*(.+)", RegexOption.IGNORE_CASE).find(resp)?.groupValues?.get(1)?.trim()
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(resp)?.groupValues?.get(1)?.trim()
        listOfNotNull(server, title).joinToString(" | ").ifBlank { null }
      }

      else -> socket.getInputStream().bufferedReader().readLine()?.take(100)
    }
  } catch (_: Exception) {
    null
  }

  private fun probeUdpServices(ip: String): List<PortResult> {
    val results = mutableListOf<PortResult>()
    // DNS (port 53)
    probeUdp(ip, 53, buildDnsQuery())?.let { results.add(PortResult(53, "udp", "open", "dns")) }
    // DHCP server (port 67)
    probeUdp(ip, 67, DHCP_DISCOVER)?.let { results.add(PortResult(67, "udp", "open", "dhcp")) }
    // NTP (port 123)
    probeUdp(ip, 123, ByteArray(48).also { it[0] = 0x1B })?.let { results.add(PortResult(123, "udp", "open", "ntp")) }
    // NetBIOS Name (port 137)
    probeUdp(ip, 137, NBSTAT_QUERY)?.let { resp ->
      val name = parseNetBiosName(resp)
      results.add(PortResult(137, "udp", "open", "netbios", name))
    }
    // SNMP (port 161) - public community string
    probeUdp(ip, 161, SNMP_GET_REQUEST)?.let { resp ->
      val info = parseSnmpResponse(resp)
      results.add(PortResult(161, "udp", "open", "snmp", info))
    }
    // SSDP/UPnP (port 1900)
    probeUdp(ip, 1900, SSDP_MSEARCH)?.let { resp ->
      val info = String(resp).lines().firstOrNull { it.startsWith("SERVER:", true) }?.substringAfter(":")?.trim()
      results.add(PortResult(1900, "udp", "open", "ssdp", info))
    }
    // mDNS (port 5353)
    probeUdp(ip, 5353, buildMdnsQuery())?.let { results.add(PortResult(5353, "udp", "open", "mdns")) }
    // IPSec IKE (port 500)
    probeUdp(ip, 500, IKE_INIT)?.let { results.add(PortResult(500, "udp", "open", "ike/ipsec")) }
    // SIP (port 5060)
    probeUdp(ip, 5060, SIP_OPTIONS.toByteArray())?.let { results.add(PortResult(5060, "udp", "open", "sip")) }
    return results
  }

  private fun parseNetBiosName(data: ByteArray): String? {
    if (data.size < 57) return null
    try {
      val nameCount = data[56].toInt() and 0xFF
      if (nameCount > 0 && data.size > 57 + 18) {
        return String(data, 57, 15).trim { it <= ' ' || it == '\u0000' }
      }
    } catch (_: Exception) {}
    return null
  }

  private fun parseSnmpResponse(data: ByteArray): String? {
    // Very basic: look for printable ASCII strings in the response
    try {
      val str = String(data, Charsets.US_ASCII)
      val printable = str.filter { it in ' '..'~' }
      return if (printable.length > 5) printable.take(80) else null
    } catch (_: Exception) {}
    return null
  }

  private fun probeUdp(ip: String, port: Int, payload: ByteArray): ByteArray? = try {
    DatagramSocket().use { sock ->
      sock.soTimeout = 2000
      sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), port))
      val buf = ByteArray(512)
      val resp = DatagramPacket(buf, buf.size)
      sock.receive(resp)
      buf.copyOf(resp.length)
    }
  } catch (_: Exception) {
    null
  }

  private fun buildDnsQuery(): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    buf.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    val name = "version.bind"
    for (part in name.split(".")) {
      buf.write(part.length)
      buf.write(part.toByteArray())
    }
    buf.write(byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x03))
    return buf.toByteArray()
  }

  private fun lookupVendor(mac: String?): String? {
    if (mac == null) return null
    val oui = mac.replace(":", "").take(6).uppercase()
    return ouiDb[oui]
  }

  private fun loadOuiDb(): Map<String, String> {
    val db = mutableMapOf<String, String>()
    try {
      context.assets.open("oui.txt").bufferedReader().useLines { lines ->
        lines.forEach { line ->
          if (line.length > 7 && !line.startsWith("#")) {
            val sep = line.indexOf('\t')
            if (sep > 0) db[line.substring(0, sep).uppercase()] = line.substring(sep + 1)
          }
        }
      }
    } catch (_: Exception) {}
    return db
  }

  /** Detect CGNAT/cellular IPs that shouldn't be subnet-scanned */
  private fun isCellularIp(ip: String): Boolean {
    // Check if the IP comes from a cellular interface
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        if (!intf.isUp) continue
        val name = intf.name.lowercase()
        val isCellIntf = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
        if (!isCellIntf) continue
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.hostAddress == ip) return true
        }
      }
    } catch (_: Exception) {}
    // Also check common CGNAT ranges where subnet scan makes no sense
    // 100.64.0.0/10 (RFC 6598 CGNAT)
    if (ip.startsWith("100.") && ip.split(".")[1].toIntOrNull()?.let { it in 64..127 } == true) return true
    return false
  }

  private fun getLocalIp(): String? {
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      // First pass: prefer non-cellular private IPs with /24
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) continue
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          val prefix = ifAddr.networkPrefixLength.toInt()
          if (prefix in 16..24 && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) return ip
        }
      }
      // Second pass: accept any private IP (WireGuard /32, VPN, etc.)
      val intfs2 = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in intfs2) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) continue
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
        }
      }
      // Third pass: include cellular (still a valid local IP to show)
      val intfs3 = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in intfs3) {
        if (!intf.isUp || intf.isLoopback) continue
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          if (!ip.startsWith("127.")) return ip
        }
      }
    } catch (_: Exception) {}
    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ip = wm.connectionInfo.ipAddress
    if (ip == 0) return null
    return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
  }

  private fun getGatewayIp(): String? {
    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val gw = wm.dhcpInfo.gateway
    if (gw == 0) return null
    return "${gw and 0xff}.${gw shr 8 and 0xff}.${gw shr 16 and 0xff}.${gw shr 24 and 0xff}"
  }

  private fun ipToLong(ip: String): Long = ip.split(".").fold(0L) { acc, v -> acc * 256 + (v.toIntOrNull() ?: 0) }

  companion object {
    val DEFAULT_PORTS = (1..1024).toList() + listOf(1433, 1521, 1723, 2222, 3306, 3389, 5432, 5900, 5901, 6379, 8080, 8443, 8888, 9090, 27017)

    val SERVICES = mapOf(
      21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
      80 to "http", 110 to "pop3", 111 to "rpc", 135 to "msrpc", 139 to "netbios",
      143 to "imap", 443 to "https", 445 to "smb", 465 to "smtps", 587 to "submission",
      993 to "imaps", 995 to "pop3s", 1433 to "mssql", 1521 to "oracle", 1723 to "pptp",
      2222 to "ssh-alt", 3306 to "mysql", 3389 to "rdp", 5432 to "postgres",
      5900 to "vnc", 5901 to "vnc", 6379 to "redis", 8080 to "http-alt",
      8443 to "https-alt", 8888 to "http-alt", 9090 to "http-alt", 27017 to "mongodb",
    )

    private val NBSTAT_QUERY = byteArrayOf(
      0x80.toByte(), 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x20, 0x43, 0x4B, 0x41,
      0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
      0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
      0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
      0x41, 0x41, 0x41, 0x41, 0x41, 0x00, 0x00, 0x21,
      0x00, 0x01,
    )

    // SNMP v1 GetRequest for sysDescr.0 (community: public)
    private val SNMP_GET_REQUEST = byteArrayOf(
      0x30, 0x29, 0x02, 0x01, 0x00, 0x04, 0x06, 0x70, 0x75, 0x62, 0x6C, 0x69, 0x63,
      0xA0.toByte(), 0x1C, 0x02, 0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00,
      0x30, 0x0E, 0x30, 0x0C, 0x06, 0x08, 0x2B, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00, 0x05, 0x00,
    )

    // DHCP Discover (minimal)
    private val DHCP_DISCOVER = byteArrayOf(
      0x01, 0x01, 0x06, 0x00, 0x12, 0x34, 0x56, 0x78.toByte(),
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    // SSDP M-SEARCH
    private val SSDP_MSEARCH = "M-SEARCH * HTTP/1.1\r\nHost:239.255.255.250:1900\r\nST:ssdp:all\r\nMAN:\"ssdp:discover\"\r\nMX:2\r\n\r\n".toByteArray()

    // IKE SA_INIT (minimal header to elicit response)
    private val IKE_INIT = byteArrayOf(
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x20, 0x22, 0x08, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x1C, 0x00, 0x00, 0x00, 0x00,
    )

    // SIP OPTIONS
    private const val SIP_OPTIONS = "OPTIONS sip:nm SIP/2.0\r\nVia: SIP/2.0/UDP nm;branch=z9hG4bK0\r\nFrom: <sip:nm@nm>;tag=0\r\nTo: <sip:nm@nm>\r\nCall-ID: 0@0.0.0.0\r\nCSeq: 0 OPTIONS\r\nMax-Forwards: 0\r\nContent-Length: 0\r\n\r\n"
  }

  private fun buildMdnsQuery(): ByteArray {
    // Query for _services._dns-sd._udp.local
    val buf = java.io.ByteArrayOutputStream()
    buf.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    val parts = "_services._dns-sd._udp.local".split(".")
    for (part in parts) {
      buf.write(part.length)
      buf.write(part.toByteArray())
    }
    buf.write(byteArrayOf(0x00, 0x00, 0x0C, 0x00, 0x01))
    return buf.toByteArray()
  }
}
