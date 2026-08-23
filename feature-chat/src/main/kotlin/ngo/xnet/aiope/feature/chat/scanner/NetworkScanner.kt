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
    _state.value = ScanState(isScanning = true, phase = "Detecting network...")
    val localIp = getLocalIp()
    val gatewayIp = getGatewayIp()
    _state.value = _state.value.copy(localIp = localIp)

    if (localIp == null) {
      _state.value = _state.value.copy(isScanning = false, error = "No network connection")
      return@withContext
    }

    val subnet = localIp.substringBeforeLast(".")

    // Phase 1: Flood entire subnet with TCP connections simultaneously (1000ms timeout)
    // The connections will fail but the kernel performs ARP resolution as a side effect
    _state.value = _state.value.copy(phase = "Scanning $subnet.0/24...")
    coroutineScope {
      (1..254).map { i ->
        async(Dispatchers.IO) {
          try {
            Socket().use { s ->
              s.tcpNoDelay = true
              s.connect(InetSocketAddress("$subnet.$i", 7), 1000)
            }
          } catch (_: Exception) {
            // Expected — we just want the ARP side effect
          }
          _state.value = _state.value.copy(progress = i / 254f)
        }
      }.awaitAll()
    }

    // Phase 2: Wait for ARP table to settle
    delay(500)

    // Phase 3: Read the neighbor/ARP table
    _state.value = _state.value.copy(phase = "Reading neighbor table...")
    val neighbors = readNeighborTable()
    val hosts = neighbors.map { (ip, mac) ->
      HostInfo(
        ip = ip,
        mac = mac,
        vendor = lookupVendor(mac),
        isGateway = ip == gatewayIp,
      )
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
      ports.mapIndexed { idx, port ->
        async(Dispatchers.IO) {
          _state.value = _state.value.copy(progress = idx.toFloat() / ports.size)
          try {
            Socket().use { s ->
              s.tcpNoDelay = true
              s.connect(InetSocketAddress(ip, port), timeout)
              // Grab banner
              val banner = grabBanner(s, ip, port)
              PortResult(port, "tcp", "open", SERVICES[port], banner)
            }
          } catch (_: Exception) {
            null
          }
        }
      }.awaitAll().filterNotNull().let { results.addAll(it) }
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
      conn.connectTimeout = 5000
      conn.readTimeout = 5000
      val ip = conn.inputStream.bufferedReader().readText().trim()
      conn.disconnect()
      _state.value = _state.value.copy(wanIp = ip)
    } catch (e: Exception) {
      _state.value = _state.value.copy(wanIp = "Error: ${e.message}")
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

  /** Read ARP/neighbor table. Tries `ip neigh` first, falls back to /proc/net/arp */
  private fun readNeighborTable(): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()

    // Try `ip neigh` (works on most Android versions)
    try {
      val process = Runtime.getRuntime().exec("ip neigh")
      BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
        reader.lineSequence().forEach { line ->
          val parts = line.split("\\s+".toRegex())
          if (parts.size >= 5) {
            val ip = parts[0]
            val mac = parts[4].uppercase()
            val state = parts.last()
            if (state != "FAILED" && state != "INCOMPLETE" && mac.contains(":") && mac != "00:00:00:00:00:00") {
              results.add(ip to mac)
            }
          }
        }
      }
      process.waitFor()
    } catch (_: Exception) {}

    if (results.isNotEmpty()) return results

    // Fallback: /proc/net/arp
    try {
      File("/proc/net/arp").bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
          val parts = line.split("\\s+".toRegex())
          if (parts.size >= 4 && parts[2] != "0x0") {
            val ip = parts[0]
            val mac = parts[3].uppercase()
            if (mac != "00:00:00:00:00:00") {
              results.add(ip to mac)
            }
          }
        }
      }
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
    // DNS
    probeUdp(ip, 53, buildDnsQuery())?.let { results.add(PortResult(53, "udp", "open", "dns")) }
    // NTP
    probeUdp(ip, 123, ByteArray(48).also { it[0] = 0x1B })?.let { results.add(PortResult(123, "udp", "open", "ntp")) }
    // NetBIOS
    probeUdp(ip, 137, NBSTAT_QUERY)?.let { results.add(PortResult(137, "udp", "open", "netbios")) }
    return results
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
  }
}
