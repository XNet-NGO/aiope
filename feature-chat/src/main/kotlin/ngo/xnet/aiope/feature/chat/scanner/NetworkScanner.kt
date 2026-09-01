package ngo.xnet.aiope.feature.chat.scanner

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
  val localIps: List<String> = emptyList(),
  val error: String? = null,
)

// ── Scanner Engine ──

class NetworkScanner(private val context: Context) {

  private val _state = MutableStateFlow(ScanState())
  val state: StateFlow<ScanState> = _state.asStateFlow()

  private val ouiDb: Map<String, String> by lazy { loadOuiDb() }

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val scanDispatcher =
    java.util.concurrent.Executors.newFixedThreadPool(1000).asCoroutineDispatcher()

  private var scanJob: kotlinx.coroutines.Job? = null

  // ══════════════════════════════════════════════════════════════
  // PUBLIC FIRE-AND-FORGET METHODS
  // ══════════════════════════════════════════════════════════════

  fun launchDiscoverHosts() {
    scanJob?.cancel()
    scanJob = scope.launch { discoverHosts() }
  }

  fun launchScanPorts(ip: String, ports: List<Int> = FULL_PORTS, timeout: Int = 500) {
    scanJob?.cancel()
    scanJob = scope.launch { scanPorts(ip, ports, timeout) }
  }

  fun launchCustomScan(ips: List<String>, ports: List<Int>, timeout: Int = 500) {
    scanJob?.cancel()
    scanJob = scope.launch {
      _state.value = _state.value.copy(isScanning = true, phase = "Custom scan...", progress = 0f)
      ips.forEachIndexed { idx, ip ->
        scanPorts(ip, ports, timeout)
        _state.value = _state.value.copy(progress = (idx + 1).toFloat() / ips.size)
      }
    }
  }

  fun launchSubnetScan(subnet: String) {
    scanJob?.cancel()
    scanJob = scope.launch { scanSubnet(subnet) }
  }

  fun launchUdpProbe(ip: String) {
    scanJob?.cancel()
    scanJob = scope.launch { udpProbe(ip) }
  }

  fun launchFetchWanIp() {
    scope.launch { fetchWanIp() }
  }

  fun cancelScan() {
    scanJob?.cancel()
    scanJob = null
    _state.value = _state.value.copy(isScanning = false, phase = "Cancelled", progress = 0f)
  }

  // ══════════════════════════════════════════════════════════════
  // PUBLIC SUSPEND METHODS
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

  suspend fun wakeOnLan(mac: String, ip: String): Boolean = withContext(Dispatchers.IO) {
    try {
      val macBytes = mac.split("[:\\-]".toRegex()).map { it.toInt(16).toByte() }.toByteArray()
      if (macBytes.size != 6) return@withContext false

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
  // HOST DISCOVERY
  // ══════════════════════════════════════════════════════════════

  private suspend fun discoverHosts() {
    _state.value =
      ScanState(isScanning = true, phase = "Detecting networks...", wanIp = _state.value.wanIp)
    val localIps = getAllLocalIps()
    val gatewayIp = getGatewayIp()
    android.util.Log.i("Scanner", "localIps=$localIps gatewayIp=$gatewayIp")
    _state.value = _state.value.copy(localIp = localIps.firstOrNull(), localIps = localIps)

    if (localIps.isEmpty()) {
      _state.value = _state.value.copy(isScanning = false, error = "No network connection")
      return
    }

    val scannable = localIps.filter { !isCellularIp(it) }
    if (scannable.isEmpty()) {
      _state.value = _state.value.copy(
        isScanning = false,
        phase = "Cellular network — subnet scan skipped",
        progress = 1f,
      )
      return
    }

    val aliveHosts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val subnets = getScannableSubnets(scannable)

    val totalHosts = subnets.size * 254
    val scannedCount = java.util.concurrent.atomic.AtomicInteger(0)

    subnets.forEachIndexed { subIdx, subnet ->
      _state.value = _state.value.copy(
        phase = "Scanning $subnet.0/24 (${subIdx + 1}/${subnets.size})...",
      )

      (1..254).chunked(50).forEach { batch ->
        coroutineScope {
          batch.map { i ->
            async(Dispatchers.IO) {
              val ip = "$subnet.$i"
              var found = false
              try {
                if (InetAddress.getByName(ip).isReachable(800)) found = true
              } catch (_: Exception) {}
              if (!found) {
                val ports = intArrayOf(7, 80, 443, 22, 445, 139, 8080, 3389)
                for (port in ports) {
                  try {
                    Socket().use { s ->
                      s.tcpNoDelay = true
                      s.connect(InetSocketAddress(ip, port), 400)
                      found = true
                    }
                    break
                  } catch (_: Exception) {}
                }
              }
              if (found) aliveHosts[ip] = true
            }
          }.awaitAll()
        }
        val done = scannedCount.addAndGet(batch.size)
        _state.value = _state.value.copy(progress = done.toFloat() / totalHosts * 0.8f)
      }
    }

    delay(600)

    _state.value = _state.value.copy(phase = "Reading ARP table...")
    val neighbors = readNeighborTable().toMap()
    android.util.Log.i(
      "Scanner",
      "Alive from probes: ${aliveHosts.size}, Neighbors from ARP: ${neighbors.size}",
    )

    val allIps = (aliveHosts.keys + neighbors.keys).distinct()

    _state.value = _state.value.copy(phase = "Building results...", progress = 0.9f)
    val hosts = allIps.map { ip ->
      val mac = neighbors[ip]
      HostInfo(
        ip = ip,
        mac = mac,
        vendor = lookupVendor(mac),
        isGateway = ip == gatewayIp,
      )
    }.sortedBy { ipToLong(it.ip) }

    _state.value = _state.value.copy(
      isScanning = false,
      phase = "Done — ${hosts.size} hosts (${subnets.size} subnets)",
      progress = 1f,
      hosts = hosts,
    )
  }

  // ══════════════════════════════════════════════════════════════
  // PORT SCANNING
  // ══════════════════════════════════════════════════════════════

  private suspend fun scanPorts(
    ip: String,
    ports: List<Int> = FULL_PORTS,
    timeout: Int = 500,
  ) {
    _state.value =
      _state.value.copy(isScanning = true, phase = "TCP $ip — 0/${ports.size}", progress = 0f)
    val results = java.util.Collections.synchronizedList(mutableListOf<PortResult>())
    val scanned = java.util.concurrent.atomic.AtomicInteger(0)
    val total = ports.size

    val chunkSize = if (total > 1000) 1000 else 500
    val effectiveTimeout = if (total > 1000) 500 else timeout
    val dispatcher = if (total > 1000) scanDispatcher else Dispatchers.IO

    coroutineScope {
      ports.chunked(chunkSize).forEach { chunk ->
        chunk.map { port ->
          async(dispatcher) {
            try {
              Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, port), effectiveTimeout)
                val banner = if (total <= 1000) grabBanner(s, ip, port) else null
                results.add(PortResult(port, "tcp", "open", SERVICES[port], banner))
              }
            } catch (_: Exception) {}
            val done = scanned.incrementAndGet()
            if (done % 100 == 0 || done == total) {
              _state.value = _state.value.copy(
                phase = "TCP $ip — $done/$total",
                progress = done.toFloat() / total,
              )
            }
          }
        }.awaitAll()
      }
    }

    val commonPorts = listOf(21, 22, 23, 25, 53, 80, 443, 445, 8080, 8443, 3389, 5900)
    val foundPorts = results.map { it.port }.toSet()
    commonPorts.filter { it !in foundPorts }.forEach { port ->
      try {
        Socket().use { s ->
          s.tcpNoDelay = true
          s.connect(InetSocketAddress(ip, port), 1000)
          val banner = grabBanner(s, ip, port)
          results.add(PortResult(port, "tcp", "open", SERVICES[port], banner))
        }
      } catch (_: Exception) {}
    }

    val sorted = results.sortedBy { it.port }
    val existingUdp =
      _state.value.hosts.firstOrNull { it.ip == ip }?.openPorts?.filter { it.protocol == "udp" }
        ?: emptyList()
    val merged = (sorted + existingUdp).sortedBy { it.port }
    val updated = _state.value.hosts.map { h ->
      if (h.ip == ip) h.copy(openPorts = merged) else h
    }
    _state.value = _state.value.copy(
      isScanning = false,
      phase = "Done — ${sorted.size} TCP ports open",
      progress = 1f,
      hosts = updated,
    )
  }

  // ══════════════════════════════════════════════════════════════
  // SUBNET SCAN
  // ══════════════════════════════════════════════════════════════

  private suspend fun scanSubnet(subnet: String) {
    _state.value =
      _state.value.copy(isScanning = true, phase = "Scanning $subnet.0/24...", progress = 0f)
    val aliveHosts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val scannedCount = java.util.concurrent.atomic.AtomicInteger(0)

    (1..254).chunked(50).forEach { batch ->
      coroutineScope {
        batch.map { i ->
          async(Dispatchers.IO) {
            val ip = "$subnet.$i"
            var found = false
            try {
              if (InetAddress.getByName(ip).isReachable(800)) found = true
            } catch (_: Exception) {}
            if (!found) {
              val ports = intArrayOf(7, 80, 443, 22, 445, 139, 8080, 3389)
              for (port in ports) {
                try {
                  Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(ip, port), 400)
                    found = true
                  }
                  break
                } catch (_: Exception) {}
              }
            }
            if (found) aliveHosts[ip] = true
          }
        }.awaitAll()
      }
      val done = scannedCount.addAndGet(batch.size)
      _state.value = _state.value.copy(progress = done / 254f * 0.8f)
    }

    delay(500)
    _state.value = _state.value.copy(phase = "Reading ARP table...")
    val neighbors = readNeighborTable().toMap()
    val allIps =
      (aliveHosts.keys + neighbors.keys.filter { it.startsWith("$subnet.") }).distinct()

    val newHosts = allIps.map { ip ->
      val mac = neighbors[ip]
      HostInfo(ip = ip, mac = mac, vendor = lookupVendor(mac))
    }

    val existingOther = _state.value.hosts.filter { !it.ip.startsWith("$subnet.") }
    val merged = (existingOther + newHosts).sortedBy { ipToLong(it.ip) }

    _state.value = _state.value.copy(
      isScanning = false,
      phase = "Done — ${newHosts.size} hosts on $subnet.0/24",
      progress = 1f,
      hosts = merged,
    )
  }

  // ══════════════════════════════════════════════════════════════
  // UDP PROBE
  // ══════════════════════════════════════════════════════════════

  private suspend fun udpProbe(ip: String) {
    _state.value =
      _state.value.copy(isScanning = true, phase = "UDP probing $ip...", progress = 0f)
    val results = java.util.Collections.synchronizedList(mutableListOf<PortResult>())
    val probes = UDP_PROBES
    val total = probes.size

    coroutineScope {
      probes.mapIndexed { idx, (port, name, payload) ->
        async(Dispatchers.IO) {
          val resp = probeUdp(ip, port, payload)
          if (resp != null) {
            val banner = parseUdpResponse(port, resp)
            results.add(PortResult(port, "udp", "open", name, banner))
          }
          _state.value = _state.value.copy(progress = (idx + 1).toFloat() / total)
        }
      }.awaitAll()
    }

    val existing =
      _state.value.hosts.firstOrNull { it.ip == ip }?.openPorts?.filter { it.protocol == "tcp" }
        ?: emptyList()
    val merged = (existing + results).sortedBy { it.port }
    val updated = _state.value.hosts.map { h ->
      if (h.ip == ip) h.copy(openPorts = merged) else h
    }
    _state.value = _state.value.copy(
      isScanning = false,
      phase = "Done — ${results.size} UDP services found",
      progress = 1f,
      hosts = updated,
    )
  }

  // ══════════════════════════════════════════════════════════════
  // WAN IP
  // ══════════════════════════════════════════════════════════════

  private suspend fun fetchWanIp() {
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
      try {
        val ip = java.net.URL("https://checkip.amazonaws.com").readText().trim()
        _state.value = _state.value.copy(wanIp = ip)
      } catch (_: Exception) {
        _state.value = _state.value.copy(wanIp = "Unavailable")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  // PRIVATE HELPERS
  // ══════════════════════════════════════════════════════════════

  private fun readNeighborTable(): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    android.util.Log.i("Scanner", "readNeighborTable: starting...")

    try {
      val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/arp"))
      val output = process.inputStream.bufferedReader().readText()
      val error = process.errorStream.bufferedReader().readText()
      process.waitFor()
      android.util.Log.i(
        "Scanner",
        "/proc/net/arp exec: exit=${process.exitValue()} lines=${output.lines().size} err=$error",
      )

      output.lines().drop(1).forEach { line ->
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
    } catch (e: Exception) {
      android.util.Log.e("Scanner", "cat /proc/net/arp failed: ${e.message}")
    }

    if (results.isNotEmpty()) {
      android.util.Log.i(
        "Scanner",
        "/proc/net/arp returned ${results.size} entries: ${results.take(3)}",
      )
      return results
    }

    try {
      val process = Runtime.getRuntime().exec(arrayOf("/system/bin/ip", "neigh"))
      val output = process.inputStream.bufferedReader().readText()
      val error = process.errorStream.bufferedReader().readText()
      process.waitFor()
      android.util.Log.i(
        "Scanner",
        "ip neigh exec: exit=${process.exitValue()} lines=${output.lines().size} err=$error",
      )

      output.lines().forEach { line ->
        val parts = line.split("\\s+".toRegex())
        if (parts.size >= 5) {
          val ip = parts[0]
          val stateStr = parts.last()
          if (stateStr != "FAILED" && stateStr != "INCOMPLETE") {
            val llIdx = parts.indexOf("lladdr")
            val mac =
              if (llIdx >= 0 && llIdx + 1 < parts.size) parts[llIdx + 1].uppercase() else null
            if (mac != null && mac != "00:00:00:00:00:00" && mac.contains(":")) {
              results.add(ip to mac)
            }
          }
        }
      }
    } catch (e: Exception) {
      android.util.Log.e("Scanner", "ip neigh failed: ${e.message}")
    }

    android.util.Log.i("Scanner", "ip neigh returned ${results.size} entries: ${results.take(3)}")
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
        val server =
          Regex("Server:\\s*(.+)", RegexOption.IGNORE_CASE).find(resp)?.groupValues?.get(1)?.trim()
        val title =
          Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
            .find(resp)?.groupValues?.get(1)?.trim()
        listOfNotNull(server, title).joinToString(" | ").ifBlank { null }
      }

      else -> {
        socket.soTimeout = 500
        val byte = socket.getInputStream().read()
        if (byte >= 0) {
          val buf = ByteArray(99)
          buf[0] = byte.toByte()
          val n = socket.getInputStream().read(buf, 1, 98)
          String(buf, 0, if (n > 0) n + 1 else 1).trim().take(100).ifBlank { null }
        } else {
          null
        }
      }
    }
  } catch (_: Exception) {
    null
  }

  private fun parseUdpResponse(port: Int, data: ByteArray): String? = when (port) {
    137 -> parseNetBiosName(data)

    161 -> parseSnmpResponse(data)

    1900 ->
      String(data).lines().firstOrNull { it.startsWith("SERVER:", true) }
        ?.substringAfter(":")?.trim()

    5060 -> String(data).lines().firstOrNull()?.take(60)

    53 -> "responded"

    123 -> "responded"

    500 -> "responded"

    5353 -> "responded"

    else -> if (data.isNotEmpty()) "responded (${data.size}B)" else null
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

  private fun isCellularIp(ip: String): Boolean {
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        if (!intf.isUp) continue
        val name = intf.name.lowercase()
        val isCellIntf =
          name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
        if (!isCellIntf) continue
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.hostAddress == ip) return true
        }
      }
    } catch (_: Exception) {}
    if (ip.startsWith("100.") &&
      ip.split(".")[1].toIntOrNull()?.let { it in 64..127 } == true
    ) {
      return true
    }
    return false
  }

  private fun getAllLocalIps(): List<String> {
    val ips = mutableListOf<String>()
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
          continue
        }
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
            ips.add(ip)
          }
        }
      }
    } catch (_: Exception) {}
    return ips.distinct()
  }

  private fun getScannableSubnets(localIps: List<String>): List<String> {
    val subnets = mutableSetOf<String>()
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
          continue
        }
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          if (!(ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
            continue
          }
          val prefix = ifAddr.networkPrefixLength.toInt()
          if (prefix in 8..32) {
            subnets.add(ip.substringBeforeLast("."))
          }
        }
      }
    } catch (_: Exception) {}
    localIps.forEach { subnets.add(it.substringBeforeLast(".")) }
    return subnets.toList()
  }

  @Suppress("DEPRECATION")
  private fun getLocalIp(): String? {
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
          continue
        }
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          val prefix = ifAddr.networkPrefixLength.toInt()
          if (prefix in 16..24 &&
            (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))
          ) {
            return ip
          }
        }
      }
      val intfs2 = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in intfs2) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
          continue
        }
        for (ifAddr in intf.interfaceAddresses) {
          val addr = ifAddr.address ?: continue
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val ip = addr.hostAddress ?: continue
          if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
            return ip
          }
        }
      }
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

  @Suppress("DEPRECATION")
  private fun getGatewayIp(): String? {
    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val gw = wm.dhcpInfo.gateway
    if (gw == 0) return null
    return "${gw and 0xff}.${gw shr 8 and 0xff}.${gw shr 16 and 0xff}.${gw shr 24 and 0xff}"
  }

  private fun ipToLong(ip: String): Long = ip.split(".").fold(0L) { acc, v -> acc * 256 + (v.toIntOrNull() ?: 0) }

  // ══════════════════════════════════════════════════════════════
  // COMPANION OBJECT
  // ══════════════════════════════════════════════════════════════

  companion object {
    @Volatile private var instance: NetworkScanner? = null

    fun getInstance(context: Context): NetworkScanner = instance ?: synchronized(this) {
      instance ?: NetworkScanner(context.applicationContext).also { instance = it }
    }

    val DEFAULT_PORTS = listOf(
      21, 22, 23, 25, 53, 67, 68, 69, 80, 110, 111, 119, 123, 135, 137, 138, 139, 143,
      161, 162, 179, 389, 443, 445, 465, 500, 514, 515, 548, 554, 587, 631, 636,
      993, 995, 1025, 1080, 1194,
      1000, 1111, 1433, 1521, 1723, 1883,
      2000, 2049, 2082, 2083, 2222, 2375, 2376,
      3000, 3128, 3268, 3306, 3333, 3389, 3478,
      4000, 4080, 4443, 4500, 4567, 4662, 4848,
      5000, 5001, 5004, 5060, 5222, 5353, 5432, 5555, 5601, 5631, 5632, 5800, 5900, 5901,
      6443, 6379, 6667, 6881,
      7070, 7443, 7547, 7777, 8000, 8008, 8080, 8081, 8088, 8123, 8200, 8291, 8443, 8444,
      8500, 8545, 8686, 8834, 8888, 8889, 8983,
      9000, 9001, 9090, 9091, 9100, 9200, 9300, 9418, 9443, 9993,
      10000, 10001, 11211, 15672, 17500, 19132, 25565, 27017, 28015, 32400, 32768,
      49152, 51413, 51820, 55555,
    )

    val FULL_PORTS = (1..65535).toList()

    val SERVICES = mapOf(
      21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
      67 to "dhcp-s", 68 to "dhcp-c", 69 to "tftp", 80 to "http", 110 to "pop3",
      111 to "rpc", 119 to "nntp", 123 to "ntp", 135 to "msrpc", 137 to "netbios",
      138 to "netbios", 139 to "netbios", 143 to "imap", 161 to "snmp", 162 to "snmptrap",
      179 to "bgp", 389 to "ldap", 443 to "https", 445 to "smb", 465 to "smtps",
      500 to "ike", 514 to "syslog", 515 to "lpd", 548 to "afp", 554 to "rtsp",
      587 to "submission", 631 to "ipp", 636 to "ldaps",
      993 to "imaps", 995 to "pop3s", 1025 to "msrpc", 1080 to "socks",
      1194 to "openvpn", 1000 to "custom", 1111 to "custom",
      1433 to "mssql", 1521 to "oracle", 1723 to "pptp", 1883 to "mqtt",
      2000 to "custom", 2049 to "nfs", 2082 to "cpanel", 2083 to "cpanel-s",
      2222 to "ssh-alt", 2375 to "docker", 2376 to "docker-s",
      3000 to "dev-http", 3128 to "squid", 3268 to "ldap-gc", 3306 to "mysql",
      3333 to "custom", 3389 to "rdp", 3478 to "stun",
      4000 to "custom", 4080 to "http-alt", 4443 to "https-alt",
      4500 to "ike-nat", 4567 to "custom", 4662 to "edonkey", 4848 to "appserver",
      5000 to "upnp", 5001 to "synology", 5004 to "rtp", 5060 to "sip",
      5222 to "xmpp", 5353 to "mdns", 5432 to "postgres", 5555 to "adb",
      5601 to "kibana", 5631 to "pcanywhere", 5632 to "pcanywhere",
      5800 to "vnc-http", 5900 to "vnc", 5901 to "vnc",
      6443 to "k8s-api", 6379 to "redis", 6667 to "irc", 6881 to "bittorrent",
      7070 to "realserver", 7443 to "https-alt", 7547 to "cwmp", 7777 to "custom",
      8000 to "http-alt", 8008 to "http-alt", 8080 to "http-proxy",
      8081 to "http-alt", 8088 to "http-alt", 8123 to "home-asst",
      8200 to "trivnet", 8291 to "mikrotik", 8443 to "https-alt", 8444 to "https-alt",
      8500 to "consul", 8545 to "ethereum", 8686 to "sun-mgmt",
      8834 to "nessus", 8888 to "http-alt", 8889 to "http-alt", 8983 to "solr",
      9000 to "custom", 9001 to "custom", 9090 to "prometheus",
      9091 to "transmission", 9100 to "jetdirect", 9200 to "elastic",
      9300 to "elastic", 9418 to "git", 9443 to "https-alt", 9993 to "zerotier",
      10000 to "webmin", 10001 to "custom", 11211 to "memcached",
      15672 to "rabbitmq", 17500 to "dropbox", 19132 to "minecraft-be",
      25565 to "minecraft", 27017 to "mongodb", 28015 to "rethinkdb",
      32400 to "plex", 32768 to "custom", 49152 to "upnp",
      51413 to "bittorrent", 51820 to "wireguard", 55555 to "custom",
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

    private val SNMP_GET_REQUEST = byteArrayOf(
      0x30, 0x29, 0x02, 0x01, 0x00, 0x04, 0x06, 0x70, 0x75, 0x62, 0x6C, 0x69, 0x63,
      0xA0.toByte(), 0x1C, 0x02, 0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00,
      0x02, 0x01, 0x00,
      0x30, 0x0E, 0x30, 0x0C, 0x06, 0x08, 0x2B, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00,
      0x05, 0x00,
    )

    private val DHCP_DISCOVER = byteArrayOf(
      0x01, 0x01, 0x06, 0x00, 0x12, 0x34, 0x56, 0x78.toByte(),
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private val SSDP_MSEARCH =
      (
        "M-SEARCH * HTTP/1.1\r\nHost:239.255.255.250:1900\r\n" +
          "ST:ssdp:all\r\nMAN:\"ssdp:discover\"\r\nMX:2\r\n\r\n"
        ).toByteArray()

    private val IKE_INIT = byteArrayOf(
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x20, 0x22, 0x08, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x1C, 0x00, 0x00, 0x00, 0x00,
    )

    private const val SIP_OPTIONS =
      "OPTIONS sip:nm SIP/2.0\r\nVia: SIP/2.0/UDP nm;branch=z9hG4bK0\r\n" +
        "From: <sip:nm@nm>;tag=0\r\nTo: <sip:nm@nm>\r\n" +
        "Call-ID: 0@0.0.0.0\r\nCSeq: 0 OPTIONS\r\n" +
        "Max-Forwards: 0\r\nContent-Length: 0\r\n\r\n"

    val UDP_PROBES: List<Triple<Int, String, ByteArray>> = listOf(
      Triple(53, "dns", buildStaticDnsQuery()),
      Triple(67, "dhcp", DHCP_DISCOVER),
      Triple(
        69,
        "tftp",
        byteArrayOf(
          0x00, 0x01, 0x2F, 0x00, 0x6E, 0x65, 0x74, 0x61, 0x73, 0x63, 0x69, 0x69, 0x00,
        ),
      ),
      Triple(123, "ntp", ByteArray(48).also { it[0] = 0x1B }),
      Triple(137, "netbios", NBSTAT_QUERY),
      Triple(138, "netbios-dgm", ByteArray(10)),
      Triple(161, "snmp", SNMP_GET_REQUEST),
      Triple(162, "snmptrap", SNMP_GET_REQUEST),
      Triple(
        389,
        "ldap",
        byteArrayOf(
          0x30, 0x0C, 0x02, 0x01, 0x01, 0x60, 0x07, 0x02, 0x01, 0x03, 0x04, 0x00,
          0x80.toByte(), 0x00,
        ),
      ),
      Triple(500, "ike", IKE_INIT),
      Triple(514, "syslog", "<14>test".toByteArray()),
      Triple(520, "rip", byteArrayOf(0x01, 0x01, 0x00, 0x00) + ByteArray(20)),
      Triple(
        623,
        "ipmi",
        byteArrayOf(
          0x06, 0x00, 0xFF.toByte(), 0x07, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x09, 0x20, 0x18.toByte(),
          0xC8.toByte(), 0x81.toByte(), 0x00, 0x38, 0x8E.toByte(), 0x04, 0xB5.toByte(),
        ),
      ),
      Triple(
        1194,
        "openvpn",
        byteArrayOf(0x38, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
      ),
      Triple(1604, "citrix", byteArrayOf(0x1E, 0x00, 0x01, 0x30, 0x02, 0xFD.toByte()) + ByteArray(26)),
      Triple(1900, "ssdp", SSDP_MSEARCH),
      Triple(
        2049,
        "nfs",
        byteArrayOf(
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
          0x00, 0x01, 0x86.toByte(), 0xA3.toByte(), 0x00, 0x00, 0x00, 0x02,
          0x00, 0x00, 0x00, 0x00,
        ),
      ),
      Triple(
        3478,
        "stun",
        byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + ByteArray(12),
      ),
      Triple(3702, "ws-discovery", "<?xml version=\"1.0\"?><Probe/>".toByteArray()),
      Triple(4500, "ike-nat", IKE_INIT),
      Triple(5060, "sip", SIP_OPTIONS.toByteArray()),
      Triple(5353, "mdns", buildStaticMdnsQuery()),
      Triple(5632, "pcanywhere", byteArrayOf(0x4E, 0x51)),
      Triple(
        6481,
        "sun-rpc",
        byteArrayOf(
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
          0x00, 0x01, 0x86.toByte(), 0xA0.toByte(), 0x00, 0x00, 0x00, 0x02,
          0x00, 0x00, 0x00, 0x04,
        ),
      ),
      Triple(10001, "ubiquiti", byteArrayOf(0x01, 0x00, 0x00, 0x00)),
      Triple(11211, "memcached", "stats\r\n".toByteArray()),
      Triple(17185, "vxworks", byteArrayOf(0x00, 0x00, 0x55, 0x55)),
      Triple(
        27960,
        "quake",
        byteArrayOf(
          0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
          0x67, 0x65, 0x74, 0x73, 0x74, 0x61, 0x74, 0x75, 0x73,
        ),
      ),
      Triple(32414, "plex", "HELLO\n".toByteArray()),
      Triple(44818, "ethernetip", byteArrayOf(0x63, 0x00, 0x00, 0x00) + ByteArray(20)),
      Triple(
        47808,
        "bacnet",
        byteArrayOf(
          0x81.toByte(), 0x0A, 0x00, 0x11, 0x01, 0x04, 0x00, 0x05,
          0x01, 0x0C, 0x0C, 0x02, 0x3F, 0xFF.toByte(), 0xFF.toByte(), 0x19, 0x4B,
        ),
      ),
      Triple(51820, "wireguard", ByteArray(32).also { it[0] = 0x01 }),
    )

    private fun buildStaticDnsQuery(): ByteArray {
      val buf = java.io.ByteArrayOutputStream()
      buf.write(
        byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
      )
      val name = "version.bind"
      for (part in name.split(".")) {
        buf.write(part.length)
        buf.write(part.toByteArray())
      }
      buf.write(byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x03))
      return buf.toByteArray()
    }

    private fun buildStaticMdnsQuery(): ByteArray {
      val buf = java.io.ByteArrayOutputStream()
      buf.write(
        byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
      )
      val parts = "_services._dns-sd._udp.local".split(".")
      for (part in parts) {
        buf.write(part.length)
        buf.write(part.toByteArray())
      }
      buf.write(byteArrayOf(0x00, 0x00, 0x0C, 0x00, 0x01))
      return buf.toByteArray()
    }
  }
}
