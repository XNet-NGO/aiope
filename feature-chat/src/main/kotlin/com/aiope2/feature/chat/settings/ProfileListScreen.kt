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
internal fun ProfileList(
  profiles: List<ProviderProfile>,
  activeId: String,
  providerStore: ProviderStore,
  onSelect: (ProviderProfile) -> Unit,
  onEdit: (ProviderProfile) -> Unit,
  onAdd: () -> Unit,
  onTasks: () -> Unit,
  onTools: () -> Unit,
  onMcp: () -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Settings") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        ListItem(
          headlineContent = { Text("Default Models per Task") },
          supportingContent = { Text("Set different models for chat, agent, titles, etc.", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onTasks() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("MCP Servers") },
          supportingContent = { Text("Add remote tool servers via Model Context Protocol", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onMcp() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Tools") },
          supportingContent = { Text("Enable or disable individual tools", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onTools() },
        )
        HorizontalDivider()
      }
      item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val installed = remember { mutableStateOf(com.aiope2.core.terminal.shell.ProotBootstrap.isInstalled(ctx)) }
        val running = remember { mutableStateOf(false) }
        val status = remember { mutableStateOf(if (installed.value) "Installed" else "Not installed") }
        val scope = rememberCoroutineScope()
        ListItem(
          headlineContent = { Text("Alpine (proot)") },
          supportingContent = {
            Text(
              status.value,
              style = MaterialTheme.typography.bodySmall,
              color = if (installed.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
          },
          trailingContent = {
            TextButton(
              onClick = {
                if (!running.value) {
                  running.value = true
                  status.value = "Downloading..."
                  scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                      // For redeploy: wipe existing rootfs
                      if (installed.value) {
                        status.value = "Removing old install..."
                        val envDir = com.aiope2.core.terminal.shell.ProotBootstrap.envDir(ctx)
                        // Delete marker first so setup knows to re-download
                        envDir.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.delete() }
                        // Delete rootfs
                        val rootfs = com.aiope2.core.terminal.shell.ProotBootstrap.rootfsDir(ctx)
                        rootfs.deleteRecursively()
                        status.value = "Old install removed"
                      }
                      com.aiope2.core.terminal.shell.ProotBootstrap.setup(ctx) { msg ->
                        status.value = msg
                      }
                      installed.value = com.aiope2.core.terminal.shell.ProotBootstrap.isInstalled(ctx)
                      status.value = if (installed.value) "Installed" else "Failed"
                    } catch (e: Exception) {
                      status.value = "Error: ${e.message?.take(40)}"
                    }
                    running.value = false
                  }
                }
              },
              enabled = !running.value,
            ) {
              Text(
                if (running.value) {
                  "Deploying..."
                } else if (installed.value) {
                  "Redeploy"
                } else {
                  "Deploy"
                },
              )
            }
          },
        )
        HorizontalDivider()
      }
      items(profiles) { p ->
        val builtin = ProviderTemplates.byId[p.builtinId]
        ListItem(
          headlineContent = { Text("${p.label.ifBlank { builtin?.displayName ?: "Custom" }}") },
          supportingContent = {
            Text(
              "${p.effectiveApiBase().removePrefix("https://").removePrefix("http://").take(40)}  •  ${p.selectedModelId.ifBlank { "no model" }}",
              style = MaterialTheme.typography.bodySmall,
            )
          },
          trailingContent = { if (p.id == activeId) Text("✔", color = MaterialTheme.colorScheme.primary) },
          modifier = Modifier.combinedClickable(onClick = { onSelect(p) }, onLongClick = { onEdit(p) }),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplatePicker(onPick: (BuiltinProvider) -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Add Provider") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      items(ProviderTemplates.ALL) { b ->
        ListItem(
          headlineContent = { Text("${b.icon} ${b.displayName}") },
          supportingContent = { Text(b.apiBase ?: "Custom endpoint", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onPick(b) },
        )
      }
    }
  }
}
