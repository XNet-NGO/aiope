package ngo.xnet.aiope.feature.chat.scanner

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scanner = remember { NetworkScanner.getInstance(context) }
  val state by scanner.state.collectAsState()
  var selectedIp by remember { mutableStateOf<String?>(null) }
  var showDns by remember { mutableStateOf(false) }
  var showCustom by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    if (state.hosts.isEmpty() && !state.isScanning) {
      scanner.launchFetchWanIp()
      scanner.launchDiscoverHosts()
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text("Network Scanner") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = {
          IconButton(onClick = { showCustom = true }) { Icon(Icons.Default.Tune, "Custom") }
          IconButton(onClick = { showDns = true }) { Icon(Icons.Default.Public, "DNS") }
          IconButton(onClick = {
            scanner.launchFetchWanIp()
            scanner.launchDiscoverHosts()
          }, enabled = !state.isScanning) {
            Icon(Icons.Default.Refresh, "Rescan")
          }
        },
      )
    },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad)) {
      // Network info bar
      Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SelectionContainer {
          Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
              val lanText = if (state.localIps.size > 1) state.localIps.joinToString(", ") else state.localIp ?: "..."
              Text("LAN: $lanText", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
              Text("WAN: ${state.wanIp ?: "..."}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
          }
        }
      }

      // Progress + Cancel
      if (state.isScanning) {
        LinearProgressIndicator(progress = { if (state.progress > 0f) state.progress else 0f }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(state.phase, style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = { scanner.cancelScan() }) { Text("Cancel") }
        }
      }

      state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

      // Host list
      LazyColumn(Modifier.fillMaxSize()) {
        item {
          if (!state.isScanning && state.hosts.isNotEmpty()) {
            Text(
              "${state.hosts.size} hosts found",
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.padding(16.dp, 8.dp),
            )
          }
        }
        items(state.hosts) { host ->
          HostRow(host, onClick = { selectedIp = host.ip })
        }
      }
    }
  }

  // Bottom sheets
  val selectedHost = selectedIp?.let { ip -> state.hosts.firstOrNull { it.ip == ip } }
  if (selectedHost != null) {
    HostDetailSheet(host = selectedHost, scanner = scanner, onDismiss = { selectedIp = null })
  }
  if (showDns) {
    DnsSheet(scanner = scanner, onDismiss = { showDns = false })
  }
  if (showCustom) {
    CustomScanSheet(scanner = scanner, onDismiss = { showCustom = false })
  }
}

// ── Host Row ──

@Composable
private fun HostRow(host: HostInfo, onClick: () -> Unit) {
  ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    headlineContent = {
      Text(host.ip, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    },
    supportingContent = {
      val info = buildString {
        host.vendor?.let { append(it) }
        host.mac?.let {
          if (isNotEmpty()) append(" • ")
          append(it)
        }
        if (host.openPorts.isNotEmpty()) {
          if (isNotEmpty()) append(" • ")
          append("${host.openPorts.size} ports")
        }
      }
      if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
    },
    leadingContent = {
      val color = when {
        host.isGateway -> MaterialTheme.colorScheme.primary
        host.openPorts.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
      }
      Badge(containerColor = color, modifier = Modifier.size(10.dp)) {}
    },
    trailingContent = {
      if (host.isGateway) {
        Text("GW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
      }
    },
  )
}

// ── Host Detail Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostDetailSheet(host: HostInfo, scanner: NetworkScanner, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  val state by scanner.state.collectAsState()
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  ModalBottomSheet(onDismissRequest = onDismiss) {
    SelectionContainer {
      Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Text(host.ip, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        host.mac?.let { Text("MAC: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        host.vendor?.let { Text("Vendor: $it", style = MaterialTheme.typography.bodyMedium) }
        if (host.isGateway) Text("⭐ Gateway", color = MaterialTheme.colorScheme.primary)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = { scanner.launchScanPorts(host.ip) },
            enabled = !state.isScanning,
          ) { Text("TCP Scan") }

          OutlinedButton(
            onClick = { scanner.launchUdpProbe(host.ip) },
            enabled = !state.isScanning,
          ) { Text("UDP Probe") }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (host.mac != null) {
            OutlinedButton(onClick = {
              scope.launch {
                val ok = scanner.wakeOnLan(host.mac!!, host.ip)
                Toast.makeText(context, if (ok) "WoL packet sent" else "Failed", Toast.LENGTH_SHORT).show()
              }
            }) { Text("Wake") }
          }

          OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(host.ip))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
          }) { Text("Copy IP") }
        }

        if (state.isScanning && state.phase.contains(host.ip)) {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
          Text(state.phase, style = MaterialTheme.typography.bodySmall)
        }

        // Open ports
        if (host.openPorts.isNotEmpty()) {
          Spacer(Modifier.height(16.dp))
          Text("Open Ports (${host.openPorts.size})", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(8.dp))
          host.openPorts.forEach { port ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
              Text("${port.port}/${port.protocol}", fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp), fontSize = 13.sp)
              Text(port.service ?: "", modifier = Modifier.width(80.dp), fontSize = 13.sp)
              port.banner?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, fontSize = 11.sp) }
            }
          }
        }
      }
    }
  }
}

// ── DNS Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsSheet(scanner: NetworkScanner, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var result by remember { mutableStateOf("") }
  var isReverse by remember { mutableStateOf(false) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
      Text("DNS Lookup", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(12.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !isReverse, onClick = { isReverse = false }, label = { Text("Forward") })
        FilterChip(selected = isReverse, onClick = { isReverse = true }, label = { Text("Reverse") })
      }

      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(if (isReverse) "IP Address" else "Hostname") },
        placeholder = { Text(if (isReverse) "8.8.8.8" else "google.com") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )

      Spacer(Modifier.height(8.dp))
      Button(onClick = {
        scope.launch {
          result = if (isReverse) scanner.reverseDns(query.trim()) else scanner.dnsLookup(query.trim())
        }
      }) { Text("Lookup") }

      if (result.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
          Text(result, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
      }
    }
  }
}

// ── Custom Scan Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomScanSheet(scanner: NetworkScanner, onDismiss: () -> Unit) {
  var ipsText by remember { mutableStateOf("") }
  var portsText by remember { mutableStateOf("") }
  var subnetText by remember { mutableStateOf("") }
  val state by scanner.state.collectAsState()
  var mode by remember { mutableIntStateOf(0) } // 0=port scan, 1=subnet discovery

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
      Text("Custom Scan", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(8.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Port Scan") })
        FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Subnet Discovery") })
      }

      Spacer(Modifier.height(12.dp))

      if (mode == 1) {
        OutlinedTextField(
          value = subnetText,
          onValueChange = { subnetText = it },
          label = { Text("Subnet") },
          placeholder = { Text("192.168.1 or 10.8.0") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Button(
          onClick = {
            val subnet = subnetText.trim().removeSuffix(".0/24").removeSuffix(".0").removeSuffix("/24")
            if (subnet.count { it == '.' } == 2) {
              scanner.launchSubnetScan(subnet)
              onDismiss()
            }
          },
          enabled = !state.isScanning && subnetText.isNotBlank(),
        ) { Text("Discover Hosts") }
        Spacer(Modifier.height(8.dp))
        Text("Scans the /24 subnet for live hosts via ARP + TCP probes.\nUse this for subnets at the other end of a VPN/WireGuard tunnel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        OutlinedTextField(
          value = ipsText,
          onValueChange = { ipsText = it },
          label = { Text("IPs") },
          placeholder = { Text("192.168.1.1, 10.0.0.1-10, 10.0.0.0/24") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = false,
          minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = portsText,
          onValueChange = { portsText = it },
          label = { Text("Ports (empty = all 65535)") },
          placeholder = { Text("80, 443, 1000-2000, 8080") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Button(
          onClick = {
            val ips = parseIps(ipsText)
            val ports = parsePorts(portsText).ifEmpty { NetworkScanner.FULL_PORTS }
            if (ips.isNotEmpty()) {
              scanner.launchCustomScan(ips, ports)
              onDismiss()
            }
          },
          enabled = !state.isScanning && ipsText.isNotBlank(),
        ) { Text("Scan Ports") }
        Spacer(Modifier.height(8.dp))
        Text("IPs: comma-separated, ranges (1.1.1.1-10), CIDR (/24)\nPorts: comma-separated, ranges (1000-2000)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/** Parse IP input: single IPs, ranges (192.168.1.1-10), CIDR (/24) */
private fun parseIps(input: String): List<String> {
  val result = mutableListOf<String>()
  input.split(",", "\n", " ").map { it.trim() }.filter { it.isNotBlank() }.forEach { token ->
    when {
      token.contains("/") -> {
        val parts = token.split("/")
        val base = parts[0]
        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
        if (prefix == 24) {
          val subnet = base.substringBeforeLast(".")
          (1..254).forEach { result.add("$subnet.$it") }
        }
      }

      token.contains("-") -> {
        val base = token.substringBeforeLast(".")
        val lastOctet = token.substringAfterLast(".")
        if (lastOctet.contains("-")) {
          val rangeParts = lastOctet.split("-")
          val start = rangeParts[0].toIntOrNull() ?: return@forEach
          val end = rangeParts.getOrNull(1)?.toIntOrNull() ?: return@forEach
          (start..end).forEach { result.add("$base.$it") }
        } else {
          result.add(token)
        }
      }

      else -> result.add(token)
    }
  }
  return result.distinct()
}

/** Parse port input: single ports, ranges (1000-2000) */
private fun parsePorts(input: String): List<Int> {
  if (input.isBlank()) return emptyList()
  val result = mutableListOf<Int>()
  input.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }.forEach { token ->
    if (token.contains("-")) {
      val parts = token.split("-", limit = 2)
      val start = parts[0].toIntOrNull() ?: return@forEach
      val end = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
      (start..end.coerceAtMost(65535)).forEach { result.add(it) }
    } else {
      token.toIntOrNull()?.let { result.add(it) }
    }
  }
  return result.distinct().filter { it in 1..65535 }
}
