package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ngo.xnet.aiope.core.network.*
import ngo.xnet.aiope.feature.chat.db.ChatDao

@Composable
internal fun ProfileList(
  providerStore: ProviderStore,
  chatDao: ChatDao? = null,
  onAgent: () -> Unit,
  onTasks: () -> Unit,
  onTools: () -> Unit,
  onMcp: () -> Unit,
  onServers: () -> Unit = {},
  onVoice: () -> Unit = {},
  onTheme: () -> Unit = {},
  onProviders: () -> Unit = {},
  onRag: () -> Unit = {},
  onSecurity: () -> Unit = {},
  onManual: () -> Unit = {},
  onBack: () -> Unit,
) {
  val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
  val scaffoldColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
  Scaffold(containerColor = scaffoldColor, contentColor = MaterialTheme.colorScheme.onSurface, topBar = {
    TopAppBar(
      title = { Text("Settings") },
      colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        ListItem(
          headlineContent = { Text("Providers") },
          supportingContent = { Text("API providers, endpoints, and models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onProviders() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Agent") },
          supportingContent = { Text("Customize the system prompt and agent behavior", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onAgent() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Default Models per Task") },
          supportingContent = { Text("Set different models for chat, agent, titles, etc.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onTasks() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("MCP Servers") },
          supportingContent = { Text("Add remote tool servers via Model Context Protocol", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onMcp() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Security") },
          supportingContent = { Text("Optional sign-in factors: biometric, security key, TOTP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onSecurity() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Manual") },
          supportingContent = { Text("Browse AIOPE's built-in guide to every feature and setting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onManual() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Remote Servers") },
          supportingContent = { Text("Deploy and manage SSH dev servers controlled by AIOPE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onServers() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Voice") },
          supportingContent = { Text("Voice selection, speech settings for live calls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onVoice() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Theme") },
          supportingContent = { Text("Colors, background, bubbles, display options", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onTheme() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("Tools") },
          supportingContent = { Text("Enable or disable individual tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onTools() },
        )
        HorizontalDivider()
        ListItem(
          headlineContent = { Text("RAG Documents") },
          supportingContent = { Text("Upload and manage files for on-device retrieval", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onRag() },
        )
        HorizontalDivider()

        // Export / Import
        if (chatDao != null) {
          val scope = rememberCoroutineScope()
          val ctx = androidx.compose.ui.platform.LocalContext.current
          var importStatus by remember { mutableStateOf("") }
          val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
          ) { uri ->
            if (uri != null) {
              scope.launch(Dispatchers.IO) {
                try {
                  SettingsPorter.importFromUri(ctx, chatDao, uri, replace = false)
                  withContext(Dispatchers.Main) { importStatus = "Imported successfully" }
                } catch (e: Exception) {
                  withContext(Dispatchers.Main) { importStatus = "Error: ${e.message?.take(40)}" }
                }
              }
            }
          }
          var exportJson by remember { mutableStateOf("") }
          val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
          ) { uri ->
            if (uri != null && exportJson.isNotBlank()) {
              scope.launch(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportJson) }
                withContext(Dispatchers.Main) { importStatus = "Exported successfully" }
              }
            }
          }
          ListItem(
            headlineContent = { Text("Export Settings") },
            supportingContent = { Text("Backup providers, tools, agent, memories", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.clickable {
              scope.launch(Dispatchers.IO) {
                val json = SettingsPorter.export(chatDao)
                withContext(Dispatchers.Main) {
                  exportJson = json
                  exportLauncher.launch("aiope-settings.json")
                }
              }
            },
          )
          HorizontalDivider()
          ListItem(
            headlineContent = { Text("Import Settings") },
            supportingContent = { Text(importStatus.ifBlank { "Restore from a backup file" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.clickable { importLauncher.launch("application/json") },
          )
          HorizontalDivider()
        }
      }
      item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val installed = remember { mutableStateOf(ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.isInstalled(ctx)) }
        val running = remember { mutableStateOf(false) }
        val status = remember { mutableStateOf(if (installed.value) "Installed" else "Not installed") }
        val scope = rememberCoroutineScope()
        ListItem(
          headlineContent = { Text("Alpine (proot)") },
          supportingContent = {
            Text(
              status.value,
              style = MaterialTheme.typography.bodySmall,
              color = if (installed.value) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
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
                        val envDir = ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.envDir(ctx)
                        // Delete marker first so setup knows to re-download
                        envDir.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.delete() }
                        // Delete rootfs
                        val rootfs = ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.rootfsDir(ctx)
                        rootfs.deleteRecursively()
                        status.value = "Old install removed"
                      }
                      ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.setup(ctx) { msg ->
                        status.value = msg
                      }
                      installed.value = ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.isInstalled(ctx)
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
      item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val modelInstalled = remember { mutableStateOf(ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.isInstalled(ctx)) }
        val modelRunning = remember { mutableStateOf(false) }
        val modelStatus = remember {
          mutableStateOf(
            if (ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.isInstalled(ctx)) {
              "Installed (${ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
            } else {
              "Not installed"
            },
          )
        }
        val modelScope = rememberCoroutineScope()
        ListItem(
          headlineContent = { Text("Local Embedding Model (Bekko)") },
          supportingContent = {
            Text(
              modelStatus.value,
              style = MaterialTheme.typography.bodySmall,
              color = if (modelInstalled.value) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
          },
          trailingContent = {
            TextButton(
              onClick = {
                if (!modelRunning.value) {
                  modelRunning.value = true
                  modelStatus.value = "Downloading..."
                  modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                      if (modelInstalled.value) {
                        modelStatus.value = "Removing old model..."
                        ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.remove(ctx)
                      }
                      ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.setup(ctx) { msg ->
                        modelStatus.value = msg
                      }
                      modelInstalled.value = ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.isInstalled(ctx)
                      modelStatus.value = if (modelInstalled.value) {
                        "Installed (${ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
                      } else {
                        "Failed"
                      }
                    } catch (e: Exception) {
                      modelStatus.value = "Error: ${e.message?.take(40)}"
                    }
                    modelRunning.value = false
                  }
                }
              },
              enabled = !modelRunning.value,
            ) {
              Text(
                if (modelRunning.value) {
                  "Downloading..."
                } else if (modelInstalled.value) {
                  "Redownload"
                } else {
                  "Download"
                },
              )
            }
          },
        )
        val localEnabled = remember {
          mutableStateOf(ngo.xnet.aiope.feature.chat.engine.EmbeddingBackend.isLocalEnabled(ctx))
        }
        val reindexScope = rememberCoroutineScope()
        val showReindexPrompt = remember { mutableStateOf(false) }
        val reindexBusy = remember { mutableStateOf(false) }
        val reindexNote = remember { mutableStateOf<String?>(null) }
        ListItem(
          headlineContent = { Text("Use on-device embeddings") },
          supportingContent = {
            Text(
              reindexNote.value ?: if (localEnabled.value) {
                if (modelInstalled.value) "On — RAG embeds locally (Bekko)" else "On, but model not downloaded — using cloud"
              } else {
                "Off — RAG embeds via cloud API"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          trailingContent = {
            Switch(
              checked = localEnabled.value,
              enabled = !reindexBusy.value,
              onCheckedChange = { on ->
                localEnabled.value = on
                ngo.xnet.aiope.feature.chat.engine.EmbeddingBackend.setLocalEnabled(ctx, on)
                ngo.xnet.aiope.feature.chat.engine.EmbeddingBackend.reset()
                reindexNote.value = null
                // Switching backend changes embedding dimensions; existing vectors won't
                // match. If there are indexed docs, offer to re-index with the new backend.
                reindexScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                  val count = ngo.xnet.aiope.feature.chat.engine.EmbeddingBackend.indexedDocumentCount(ctx)
                  if (count > 0) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                      showReindexPrompt.value = true
                    }
                  }
                }
              },
            )
          },
        )
        if (showReindexPrompt.value) {
          AlertDialog(
            onDismissRequest = { if (!reindexBusy.value) showReindexPrompt.value = false },
            title = { Text("Re-index knowledge base?") },
            text = {
              Text(
                if (reindexBusy.value) {
                  "Re-indexing all documents with the ${if (localEnabled.value) "on-device" else "cloud"} model..."
                } else {
                  "You switched embedding backends. Existing documents were indexed with a " +
                    "different model, so their vectors won't match new searches. Re-index now " +
                    "with the ${if (localEnabled.value) "on-device (Bekko)" else "cloud"} model?"
                },
              )
            },
            confirmButton = {
              TextButton(
                enabled = !reindexBusy.value,
                onClick = {
                  reindexBusy.value = true
                  reindexScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = ngo.xnet.aiope.feature.chat.engine.EmbeddingBackend.reindexWithCurrentBackend(ctx)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                      reindexBusy.value = false
                      showReindexPrompt.value = false
                      reindexNote.value = if (ok) "Re-indexed with new backend" else "Re-index failed — try again"
                    }
                  }
                },
              ) { Text(if (reindexBusy.value) "Re-indexing..." else "Re-index") }
            },
            dismissButton = {
              TextButton(
                enabled = !reindexBusy.value,
                onClick = { showReindexPrompt.value = false },
              ) { Text("Later") }
            },
          )
        }
        HorizontalDivider()
      }
      item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val odInstalled = remember { mutableStateOf(ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.isInstalled(ctx)) }
        val odBusy = remember { mutableStateOf(false) }
        val odStatus = remember {
          mutableStateOf(
            if (ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.isInstalled(ctx)) {
              "Installed (${ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
            } else {
              "Not installed"
            },
          )
        }
        val odScope = rememberCoroutineScope()
        ListItem(
          headlineContent = { Text("Object Detection Model (RT-DETRv4-S)") },
          supportingContent = {
            Text(
              odStatus.value,
              style = MaterialTheme.typography.bodySmall,
              color = if (odInstalled.value) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
          },
          trailingContent = {
            TextButton(
              enabled = !odBusy.value,
              onClick = {
                if (!odBusy.value) {
                  odBusy.value = true
                  odStatus.value = "Downloading..."
                  odScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                      if (odInstalled.value) {
                        odStatus.value = "Removing old model..."
                        ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.remove(ctx)
                      }
                      ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.setup(ctx) { msg ->
                        odStatus.value = msg
                      }
                      odInstalled.value = ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.isInstalled(ctx)
                      odStatus.value = if (odInstalled.value) {
                        "Installed (${ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
                      } else {
                        "Failed"
                      }
                    } catch (e: Exception) {
                      odStatus.value = "Error: ${e.message?.take(40)}"
                    }
                    odBusy.value = false
                  }
                }
              },
            ) {
              Text(
                if (odBusy.value) "Downloading..." else if (odInstalled.value) "Redownload" else "Download",
              )
            }
          },
        )
        HorizontalDivider()
      }
      item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val yInstalled = remember { mutableStateOf(ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.isInstalled(ctx)) }
        val yBusy = remember { mutableStateOf(false) }
        val yStatus = remember {
          mutableStateOf(
            if (ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.isInstalled(ctx)) {
              "Installed (${ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
            } else {
              "Not installed"
            },
          )
        }
        val yScope = rememberCoroutineScope()
        ListItem(
          headlineContent = { Text("Live Detection Model (YOLOv9-s)") },
          supportingContent = {
            Text(
              yStatus.value,
              style = MaterialTheme.typography.bodySmall,
              color = if (yInstalled.value) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
          },
          trailingContent = {
            TextButton(
              enabled = !yBusy.value,
              onClick = {
                if (!yBusy.value) {
                  yBusy.value = true
                  yStatus.value = "Downloading..."
                  yScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                      if (yInstalled.value) {
                        yStatus.value = "Removing old model..."
                        ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.remove(ctx)
                      }
                      ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.setup(ctx) { msg -> yStatus.value = msg }
                      yInstalled.value = ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.isInstalled(ctx)
                      yStatus.value = if (yInstalled.value) {
                        "Installed (${ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
                      } else {
                        "Failed"
                      }
                    } catch (e: Exception) {
                      yStatus.value = "Error: ${e.message?.take(40)}"
                    }
                    yBusy.value = false
                  }
                }
              },
            ) {
              Text(if (yBusy.value) "Downloading..." else if (yInstalled.value) "Redownload" else "Download")
            }
          },
        )
        HorizontalDivider()

        // Speech-to-Text model (sherpa-onnx streaming zipformer EN, offline).
        val sInstalled = remember { mutableStateOf(ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.isInstalled(ctx)) }
        val sBusy = remember { mutableStateOf(false) }
        val sStatus = remember {
          mutableStateOf(
            if (ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.isInstalled(ctx)) {
              "Installed (${ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
            } else {
              "Not installed"
            },
          )
        }
        val sScope = rememberCoroutineScope()
        ListItem(
          headlineContent = { Text("Speech-to-Text Model (offline)") },
          supportingContent = {
            Text(
              sStatus.value,
              style = MaterialTheme.typography.bodySmall,
              color = if (sInstalled.value) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
          },
          trailingContent = {
            TextButton(
              enabled = !sBusy.value,
              onClick = {
                if (!sBusy.value) {
                  sBusy.value = true
                  sStatus.value = "Downloading..."
                  sScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                      if (sInstalled.value) {
                        sStatus.value = "Removing old model..."
                        ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.remove(ctx)
                      }
                      ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.setup(ctx) { msg -> sStatus.value = msg }
                      sInstalled.value = ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.isInstalled(ctx)
                      sStatus.value = if (sInstalled.value) {
                        "Installed (${ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap.installedBytes(ctx) / 1024 / 1024}MB)"
                      } else {
                        "Failed"
                      }
                    } catch (e: Exception) {
                      sStatus.value = "Error: ${e.message?.take(40)}"
                    }
                    sBusy.value = false
                  }
                }
              },
            ) {
              Text(if (sBusy.value) "Downloading..." else if (sInstalled.value) "Redownload" else "Download")
            }
          },
        )
        HorizontalDivider()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplatePicker(onPick: (BuiltinProvider) -> Unit, onBack: () -> Unit) {
  val _bgActive = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current.useBackground
  Scaffold(containerColor = if (_bgActive) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface, topBar = {
    TopAppBar(
      title = { Text("Add Provider") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      items(ProviderTemplates.ALL) { b ->
        ListItem(
          headlineContent = { Text("${b.icon} ${b.displayName}") },
          supportingContent = { Text(b.apiBase ?: "Custom endpoint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          modifier = Modifier.clickable { onPick(b) },
        )
      }
    }
  }
}
