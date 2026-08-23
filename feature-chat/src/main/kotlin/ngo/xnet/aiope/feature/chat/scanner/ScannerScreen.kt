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
  val scanner = remember { NetworkScanner(context) }
  val state by scanner.state.collectAsState()
  val scope = rememberCoroutineScope()
  var selectedHost by remember { mutableStateOf<HostInfo?>(null) }
  var showDns by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    if (state.hosts.isEmpty() && !state.isScanning) {
      scanner.fetchWanIp()
      scanner.discoverHosts()
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text("Network Scanner") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = {
          IconButton(onClick = { showDns = true }) { Icon(Icons.Default.Public, "DNS") }
          IconButton(onClick = {
            scope.launch {
              scanner.fetchWanIp()
              scanner.discoverHosts()
            }
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
              Text("LAN: ${state.localIp ?: "..."}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
              Text("WAN: ${state.wanIp ?: "..."}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
          }
        }
      }

      // Progress
      if (state.isScanning) {
        LinearProgressIndicator(progress = { if (state.progress > 0f) state.progress else 0f }, modifier = Modifier.fillMaxWidth())
        Text(state.phase, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
      }

      state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

      // Host list
      LazyColumn(Modifier.fillMaxSize()) {
        item {
          Text(
            "${state.hosts.size} hosts found",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(16.dp, 8.dp),
          )
        }
        items(state.hosts) { host ->
          HostRow(host, onClick = { selectedHost = host })
        }
      }
    }
  }

  // Bottom sheets
  if (selectedHost != null) {
    HostDetailSheet(host = selectedHost!!, scanner = scanner, onDismiss = { selectedHost = null })
  }
  if (showDns) {
    DnsSheet(scanner = scanner, onDismiss = { showDns = false })
  }
}

// ── Host Row ──

@Composable
private fun HostRow(host: HostInfo, onClick: () -> Unit) {
  ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    headlineContent = {
      Text(host.hostname ?: host.ip, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    },
    supportingContent = {
      SelectionContainer {
        Text(
          buildString {
            append(host.ip)
            host.vendor?.let { append(" • $it") }
            if (host.openPorts.isNotEmpty()) append(" • ${host.openPorts.size} ports open")
          },
          style = MaterialTheme.typography.bodySmall,
        )
      }
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
      host.mac?.let {
        Text(it.take(8), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
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
  val currentHost = state.hosts.firstOrNull { it.ip == host.ip } ?: host
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  ModalBottomSheet(onDismissRequest = onDismiss) {
    SelectionContainer {
      Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Text(currentHost.hostname ?: currentHost.ip, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(currentHost.ip, fontFamily = FontFamily.Monospace)
        currentHost.mac?.let { Text("MAC: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        currentHost.vendor?.let { Text("Vendor: $it", style = MaterialTheme.typography.bodySmall) }
        if (currentHost.isGateway) Text("⭐ Gateway", color = MaterialTheme.colorScheme.primary)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { scope.launch { scanner.scanPorts(currentHost.ip) } },
            enabled = !state.isScanning,
          ) { Text("Scan Ports") }

          if (currentHost.mac != null) {
            OutlinedButton(onClick = {
              scope.launch {
                val ok = scanner.wakeOnLan(currentHost.mac!!, currentHost.ip)
                Toast.makeText(context, if (ok) "WoL packet sent" else "Failed", Toast.LENGTH_SHORT).show()
              }
            }) { Text("Wake") }
          }

          OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(currentHost.ip))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
          }) { Text("Copy IP") }
        }

        if (state.isScanning && state.phase.contains(currentHost.ip)) {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }

        // Open ports
        if (currentHost.openPorts.isNotEmpty()) {
          Spacer(Modifier.height(16.dp))
          Text("Open Ports (${currentHost.openPorts.size})", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(8.dp))
          currentHost.openPorts.forEach { port ->
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
