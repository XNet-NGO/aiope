package com.aiope2.feature.chat.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiope2.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun McpServerScreen(toolStore: ToolStore, onBack: () -> Unit) {
  var servers by remember { mutableStateOf(toolStore.getMcpServers()) }
  val mcpManager = remember { McpManager(toolStore) }
  var editServerId by remember { mutableStateOf<String?>(null) }
  var showJsonImport by remember { mutableStateOf(false) }
  fun reload() {
    servers = toolStore.getMcpServers()
  }

  val editing = editServerId?.let { id -> servers.find { it.id == id } ?: McpServerConfig(id = id, name = "", url = "") }

  if (editing != null) {
    McpServerDetailPage(
      editing,
      toolStore,
      mcpManager,
      onBack = {
        editServerId = null
        reload()
      },
      onDelete = {
        toolStore.removeMcpServer(it)
        mcpManager.clearSession(it)
        editServerId = null
        reload()
      },
    )
    return
  }

  val _bgActive1 = com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground
  Scaffold(containerColor = if (_bgActive1) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background, contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, topBar = {
    TopAppBar(
      colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = if (com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.surface),
      title = { Text("MCP Servers") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = {
        IconButton(onClick = { showJsonImport = true }) { Icon(Icons.Default.Edit, "JSON") }
        IconButton(onClick = {
          val s = McpServerConfig(name = "", url = "")
          toolStore.addMcpServer(s)
          reload()
          editServerId = s.id
        }) { Icon(Icons.Default.Add, "Add") }
      },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      if (servers.isEmpty()) {
        item {
          Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No MCP servers", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Tap + to add a server or paste JSON config", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
          }
        }
      }
      items(servers.size) { i ->
        val s = servers[i]
        val cs = MaterialTheme.colorScheme
        Card(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { editServerId = s.id },
          colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        ) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val dotColor = mcpDotColor(s, cs)
            Box(Modifier.size(10.dp).background(dotColor, shape = androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(s.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
              if (s.url.isNotBlank()) {
                Text(
                  s.url,
                  style = MaterialTheme.typography.bodySmall,
                  color = cs.onSurfaceVariant,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
              }
              Spacer(Modifier.height(6.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                McpPill(mcpStatusLabel(s.status), color = dotColor)
                McpPill(if (s.transport == McpTransport.HTTP) "HTTP" else "SSE", color = cs.primary)
                if (s.toolCount > 0) McpPill("${s.toolCount} tools", color = cs.primary)
                if (!s.enabled) McpPill("Disabled", color = cs.outline)
              }
              if (s.status == McpStatus.ERROR && s.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(s.error, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 2)
              }
            }
            Icon(Icons.Default.KeyboardArrowRight, "Edit", tint = cs.onSurfaceVariant.copy(0.5f))
          }
        }
      }
    }
  }
  if (showJsonImport) {
    McpJsonImportSheet(onDismiss = {
      showJsonImport = false
      reload()
    }, toolStore = toolStore)
  }
}

private fun mcpDotColor(s: McpServerConfig, cs: ColorScheme) = when (s.status) {
  McpStatus.CONNECTED -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
  McpStatus.CONNECTING -> cs.primary
  McpStatus.ERROR -> cs.error
  McpStatus.IDLE -> if (s.enabled) cs.outline else cs.outline.copy(0.4f)
}
private fun mcpStatusLabel(s: McpStatus) = when (s) {
  McpStatus.CONNECTED -> "Connected"
  McpStatus.CONNECTING -> "Connecting"
  McpStatus.ERROR -> "Error"
  McpStatus.IDLE -> "Idle"
}

@Composable
private fun McpPill(text: String, color: androidx.compose.ui.graphics.Color) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = color.copy(alpha = 0.12f),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.35f)),
  ) {
    Text(
      text,
      Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      fontSize = 11.sp,
      fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
      color = color,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerDetailPage(
  server: McpServerConfig,
  toolStore: ToolStore,
  mcpManager: McpManager,
  onBack: () -> Unit,
  onDelete: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val isNew = server.name.isBlank() && server.url.isBlank()
  var name by remember { mutableStateOf(server.name) }
  var url by remember { mutableStateOf(server.url) }
  var transport by remember { mutableStateOf(server.transport) }
  var enabled by remember { mutableStateOf(server.enabled) }
  var headers by remember { mutableStateOf(server.headers.entries.map { it.key to it.value }.toMutableList().ifEmpty { mutableListOf("Authorization" to "") }) }
  var tools by remember { mutableStateOf(mcpManager.getCachedTools(server.id)) }
  var status by remember { mutableStateOf(server.status) }
  var error by remember { mutableStateOf(server.error) }
  var connecting by remember { mutableStateOf(false) }
  var showDeleteConfirm by remember { mutableStateOf(false) }

  fun save() {
    val hdrs = headers.filter { it.first.isNotBlank() && it.second.isNotBlank() }.associate { it.first.trim() to it.second.trim() }
    toolStore.updateMcpServer(server.copy(name = name.trim(), url = url.trim(), transport = transport, headers = hdrs, enabled = enabled, status = status, error = error))
  }

  fun connect() {
    if (url.isBlank()) return
    save()
    connecting = true
    status = McpStatus.CONNECTING
    error = null
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
      val updated = server.copy(
        name = name.trim(),
        url = url.trim(),
        transport = transport,
        headers = headers.filter { it.first.isNotBlank() && it.second.isNotBlank() }.associate { it.first.trim() to it.second.trim() },
        enabled = enabled,
      )
      val result = mcpManager.discoverTools(updated)
      result.onSuccess {
        tools = it
        status = McpStatus.CONNECTED
        error = null
      }
      result.onFailure {
        status = McpStatus.ERROR
        error = it.message
      }
      connecting = false
    }
  }

  Scaffold(
    containerColor = if (com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = if (com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.surface),
        title = { Text(if (isNew) "Add MCP Server" else name.ifBlank { "(unnamed)" }) },
        navigationIcon = {
          IconButton(onClick = {
            save()
            onBack()
          }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
          if (!isNew) IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        },
      )
    },
  ) { pad ->
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
      // Status bar
      item {
        val dotColor = mcpDotColor(server.copy(status = status, enabled = enabled), cs)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Box(Modifier.size(10.dp).background(dotColor, shape = androidx.compose.foundation.shape.CircleShape))
          McpPill(mcpStatusLabel(status), color = dotColor)
          McpPill(if (transport == McpTransport.HTTP) "HTTP" else "SSE", color = cs.primary)
          if (tools.isNotEmpty()) McpPill("${tools.size} tools", color = cs.primary)
        }
        if (status == McpStatus.ERROR && error != null) {
          Text(error!!, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(bottom = 8.dp))
        }
      }

      // Name
      item {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
      }

      // Transport
      item {
        Text("Transport", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
          SegmentedButton(selected = transport == McpTransport.HTTP, onClick = { transport = McpTransport.HTTP }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Streamable HTTP") }
          SegmentedButton(selected = transport == McpTransport.SSE, onClick = { transport = McpTransport.SSE }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("SSE") }
        }
        Spacer(Modifier.height(8.dp))
      }

      // URL
      item {
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
      }

      // Headers
      item {
        Text("Headers", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
      }
      items(headers.size) { idx ->
        val (k, v) = headers[idx]
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = k,
            onValueChange = { headers = headers.toMutableList().also { l -> l[idx] = it to v } },
            label = { Text("Key") },
            singleLine = true,
            modifier = Modifier.weight(0.4f),
          )
          Spacer(Modifier.width(8.dp))
          OutlinedTextField(
            value = v,
            onValueChange = { headers = headers.toMutableList().also { l -> l[idx] = k to it } },
            label = { Text("Value") },
            singleLine = true,
            modifier = Modifier.weight(0.55f),
          )
          IconButton(onClick = { headers = headers.toMutableList().also { it.removeAt(idx) } }) {
            Icon(Icons.Default.Delete, "Remove", modifier = Modifier.size(18.dp))
          }
        }
      }
      item {
        TextButton(onClick = { headers = headers.toMutableList().also { it.add("" to "") } }) {
          Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Add header")
        }
        Spacer(Modifier.height(8.dp))
      }

      // Enable + Connect
      item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("Enabled", Modifier.weight(1f))
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { connect() }, enabled = !connecting && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
          Text(
            if (connecting) {
              "Connecting..."
            } else if (status == McpStatus.CONNECTED) {
              "Reconnect"
            } else {
              "Connect"
            },
          )
        }
        Spacer(Modifier.height(12.dp))
      }

      // Tools
      item {
        HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant.copy(0.3f))
        Spacer(Modifier.height(8.dp))
        Text("Tools", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (tools.isEmpty() && status != McpStatus.CONNECTED) {
          Text("Connect to discover available tools", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } else if (tools.isEmpty()) {
          Text("No tools available on this server", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
      }
      items(tools.size) { idx ->
        val tool = tools[idx]
        var toolEnabled by remember(tool.name) { mutableStateOf(toolStore.isToolEnabled(tool.name)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(tool.name, fontSize = 13.sp)
            if (tool.description.isNotBlank()) Text(tool.description, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 2)
          }
          Switch(checked = toolEnabled, onCheckedChange = {
            toolEnabled = it
            toolStore.setToolEnabled(tool.name, it)
          })
        }
      }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("Delete server?") },
      text = { Text("Remove ${name.ifBlank { "this server" }}? This cannot be undone.") },
      confirmButton = {
        TextButton(onClick = {
          onDelete(server.id)
          showDeleteConfirm = false
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpJsonImportSheet(onDismiss: () -> Unit, toolStore: ToolStore) {
  var json by remember { mutableStateOf("") }
  var result by remember { mutableStateOf("") }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(16.dp)) {
      Text("Import MCP Config", style = MaterialTheme.typography.headlineSmall)
      Spacer(Modifier.height(4.dp))
      Text("Paste JSON in the standard MCP format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = json,
        onValueChange = { json = it },
        modifier = Modifier.fillMaxWidth().height(200.dp),
        label = { Text("{\"mcpServers\": {...}}") },
        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
      )
      Spacer(Modifier.height(8.dp))
      if (result.isNotBlank()) {
        Text(
          result,
          style = MaterialTheme.typography.bodySmall,
          color = if (result.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(Modifier.height(8.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
          try {
            val count = toolStore.importFromJson(json)
            result = "Imported $count server(s)"
          } catch (e: Exception) {
            result = "Error: ${e.message}"
          }
        }, enabled = json.isNotBlank()) { Text("Import") }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}
