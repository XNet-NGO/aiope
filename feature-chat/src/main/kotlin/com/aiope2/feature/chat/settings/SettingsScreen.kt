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

private val TOKEN_STEPS = listOf(0, 256, 512, 1024, 2048, 4096, 8192, 16000, 32000, 64000, 128000, 200000, 500000, 1000000)
private val HISTORY_STEPS = listOf(1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000, 200000, 256000, 384000, 500000, 750000, 1000000, 2000000, 5000000, 10000000)
private val ENDPOINT_PRESETS = listOf(
  "/chat/completions", "/completions", "/responses", "/embeddings", "/rerank",
  "/audio/speech", "/audio/transcriptions", "/images/generations", "/moderations",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(providerStore: ProviderStore, toolStore: ToolStore, onBack: () -> Unit) {
  var screen by remember { mutableStateOf("list") }
  var editId by remember { mutableStateOf<String?>(null) }
  var profiles by remember { mutableStateOf(providerStore.getAll()) }
  var activeId by remember { mutableStateOf(providerStore.getActive().id) }
  fun refresh() {
    profiles = providerStore.getAll()
    activeId = providerStore.getActive().id
  }

  when (screen) {
    "list" -> ProfileList(
      profiles, activeId, providerStore,
      onSelect = {
        providerStore.setActive(it.id)
        activeId = it.id
      },
      onEdit = {
        editId = it.id
        screen = "edit"
      },
      onAdd = { screen = "pick" }, onTasks = { screen = "tasks" }, onTools = { screen = "tools" }, onMcp = { screen = "mcp" }, onBack = onBack,
    )

    "tools" -> ToolToggleScreen(toolStore, onBack = { screen = "list" })

    "mcp" -> McpServerScreen(toolStore, onBack = { screen = "list" })

    "pick" -> TemplatePicker(onPick = { b ->
      val p = ProviderProfile(
        builtinId = b.id,
        label = b.displayName,
        apiBase = b.apiBase ?: "",
        selectedModelId = b.defaultModels.firstOrNull()?.id ?: "",
      )
      providerStore.save(p)
      providerStore.setActive(p.id)
      editId = p.id
      refresh()
      screen = "edit"
    }, onBack = { screen = "list" })

    "edit" -> editId?.let { providerStore.getById(it) }?.let { profile ->
      ProfileEditor(
        profile,
        providerStore,
        onSave = {
          providerStore.save(it)
          providerStore.setActive(it.id)
          refresh()
          screen = "list"
        },
        onDelete = {
          providerStore.delete(profile.id)
          refresh()
          screen = "list"
        },
        onBack = { screen = "list" },
      )
    }

    "tasks" -> TaskModelScreen(providerStore, onBack = { screen = "list" })
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProfileList(
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
private fun TemplatePicker(onPick: (BuiltinProvider) -> Unit, onBack: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
  profile: ProviderProfile,
  store: ProviderStore,
  onSave: (ProviderProfile) -> Unit,
  onDelete: () -> Unit,
  onBack: () -> Unit,
) {
  var p by remember { mutableStateOf(profile) }
  val builtin = ProviderTemplates.byId[p.builtinId]
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(false) }
  var models by remember { mutableStateOf(store.getModelCache(p.builtinId) ?: store.getModelCacheStale(p.builtinId) ?: builtin?.defaultModels ?: emptyList()) }
  var modelExpanded by remember { mutableStateOf(false) }
  var customModelText by remember { mutableStateOf("") }

  // Current model config (read from profile, update back into profile)
  var mc by remember(p.selectedModelId) { mutableStateOf(p.activeModelConfig()) }
  fun saveModelConfig() {
    p = p.copy(modelConfigs = p.modelConfigs + (mc.modelId to mc))
  }
  fun configFromModel(m: ModelDef): ModelConfig = p.modelConfigs[m.id] ?: ModelConfig(
    modelId = m.id,
    toolsOverride = if (m.contextWindow > 0) m.supportsTools else null,
    visionOverride = if (m.contextWindow > 0) m.supportsVision else null,
    audioOverride = if (m.contextWindow > 0) m.supportsAudio else null,
    videoOverride = if (m.contextWindow > 0) m.supportsVideo else null,
    reasoningEffort = if (m.supportsReasoning) "auto" else null,
    contextTokens = if (m.contextWindow > 0) m.contextWindow else 128_000,
    autoCompact = m.contextWindow > 0,
  )

  Scaffold(topBar = {
    TopAppBar(
      title = { Text(p.label.ifBlank { "Edit Provider" }) },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = { IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") } },
    )
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
      // ══════════════════════════════════════════════════════════════
      // PROVIDER SETTINGS (shared across all models)
      // ══════════════════════════════════════════════════════════════
      Section("Provider")
      Field("Name", p.label) { p = p.copy(label = it) }
      Field("Base URL", p.apiBase, builtin?.apiBase ?: "https://api.example.com/v1") { p = p.copy(apiBase = it) }
      Field("API Key", p.apiKey, builtin?.apiKeyHint ?: "API key", obfuscate = true) { p = p.copy(apiKey = it) }

      // Model selector
      Section("Model")
      Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
          loading = true
          scope.launch {
            val fetched = fetchModels(p.effectiveApiBase(), p.apiKey)
            if (fetched.isNotEmpty()) {
              store.saveModelCache(p.builtinId, fetched)
              models = fetched
            }
            loading = false
          }
        }, enabled = !loading) { Text(if (loading) "Loading…" else "Load Models") }
        Spacer(Modifier.width(8.dp))
        Text("${models.size} models", style = MaterialTheme.typography.bodySmall)
      }
      Spacer(Modifier.height(8.dp))
      ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
        OutlinedTextField(
          value = models.firstOrNull { it.id == p.selectedModelId }?.let { m ->
            val tags = if (m.contextWindow > 0) "${m.contextWindow / 1000}k" + (if (m.supportsTools) " T" else "") + (if (m.supportsVision) " V" else "") + (if (m.supportsReasoning) " R" else "") else ""
            "${m.displayName}${if (tags.isNotEmpty()) "  $tags" else ""}"
          } ?: p.selectedModelId.ifBlank { "Select model" },
          onValueChange = {},
          readOnly = true,
          label = { Text("Selected Model") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
          modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
          models.forEach { m ->
            DropdownMenuItem(
              text = {
                val tags = if (m.contextWindow > 0) "${m.contextWindow / 1000}k" + (if (m.supportsTools) " T" else "") + (if (m.supportsVision) " V" else "") + (if (m.supportsReasoning) " R" else "") else ""
                Text("${m.displayName}${if (tags.isNotEmpty()) "  $tags" else ""}")
              },
              onClick = {
                p = p.copy(selectedModelId = m.id)
                mc = configFromModel(m)
                saveModelConfig()
                modelExpanded = false
              },
            )
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = customModelText,
          onValueChange = { customModelText = it },
          label = { Text("Add Custom Model") },
          modifier = Modifier.weight(1f),
          singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = {
          if (customModelText.isNotBlank()) {
            val mid = customModelText.trim()
            val modality = when {
              mid.startsWith("cf-image/") || mid.contains("/image/") -> "image"
              mid.startsWith("cf-audio/") -> "audio"
              else -> "text"
            }
            val nm = ModelDef(mid, mid.substringAfterLast("/"), 128_000, supportsTools = modality == "text", outputModality = modality)
            models = listOf(nm) + models
            p = p.copy(selectedModelId = nm.id)
            mc = configFromModel(nm)
            saveModelConfig()
            customModelText = ""
          }
        }) { Icon(Icons.Default.Add, "Add") }
      }

      // ══════════════════════════════════════════════════════════════
      // PER-MODEL SETTINGS (for the currently selected model)
      // ══════════════════════════════════════════════════════════════
      if (p.selectedModelId.isNotBlank()) {
        Section("Model Settings: ${p.selectedModelId.substringAfterLast('/').take(30)}")

        // Endpoint override
        var epExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = epExpanded, onExpandedChange = { epExpanded = it }) {
          OutlinedTextField(
            value = mc.endpointOverride.ifBlank { "/chat/completions" },
            onValueChange = {
              mc = mc.copy(endpointOverride = it)
              saveModelConfig()
            },
            label = { Text("Endpoint Override") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = epExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
          )
          ExposedDropdownMenu(expanded = epExpanded, onDismissRequest = { epExpanded = false }) {
            ENDPOINT_PRESETS.forEach { ep ->
              DropdownMenuItem(text = { Text(ep) }, onClick = {
                mc = mc.copy(endpointOverride = ep)
                saveModelConfig()
                epExpanded = false
              })
            }
          }
        }
        Spacer(Modifier.height(8.dp))

        // Abilities
        Text("Abilities", style = MaterialTheme.typography.labelMedium)
        val autoDetect = mc.toolsOverride == null && mc.visionOverride == null && mc.audioOverride == null && mc.videoOverride == null
        var auto by remember(mc.modelId) { mutableStateOf(autoDetect) }
        LabeledSwitch("Auto-detect", auto) {
          auto = it
          if (it) {
            mc = mc.copy(toolsOverride = null, visionOverride = null, audioOverride = null, videoOverride = null)
            saveModelConfig()
          }
        }
        if (!auto) {
          LabeledSwitch("Tool Calling", mc.toolsOverride ?: true) {
            mc = mc.copy(toolsOverride = it)
            saveModelConfig()
          }
          LabeledSwitch("Vision", mc.visionOverride ?: false) {
            mc = mc.copy(visionOverride = it)
            saveModelConfig()
          }
          LabeledSwitch("Audio", mc.audioOverride ?: false) {
            mc = mc.copy(audioOverride = it)
            saveModelConfig()
          }
          LabeledSwitch("Video", mc.videoOverride ?: false) {
            mc = mc.copy(videoOverride = it)
            saveModelConfig()
          }
        }

        // Reasoning
        Text("Reasoning", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        val reasoningOptions = listOf("off", "auto", "low", "medium", "high")
        val reasoningIdx = reasoningOptions.indexOf(mc.reasoningEffort ?: "off").coerceAtLeast(0)
        Text("Reasoning Effort: ${reasoningOptions[reasoningIdx]}", style = MaterialTheme.typography.bodySmall)
        Slider(value = reasoningIdx.toFloat(), onValueChange = {
          val v = reasoningOptions[it.toInt().coerceIn(0, reasoningOptions.size - 1)]
          mc = mc.copy(reasoningEffort = if (v == "off") null else v)
          saveModelConfig()
        }, valueRange = 0f..4f, steps = 3)

        // Parameters
        Text("Parameters", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        LogSlider("Temperature", mc.temperature, 0f, 2f) {
          mc = mc.copy(temperature = if (it <= 0f) null else it)
          saveModelConfig()
        }
        LogSlider("Top-P", mc.topP, 0f, 1f) {
          mc = mc.copy(topP = if (it <= 0f) null else it)
          saveModelConfig()
        }
        StepSlider("Max Tokens", mc.maxTokens, TOKEN_STEPS) {
          mc = mc.copy(maxTokens = if (it == 0) null else it)
          saveModelConfig()
        }
        TopKSlider("Top-K", mc.topK) {
          mc = mc.copy(topK = if (it == 0) null else it)
          saveModelConfig()
        }

        // Context
        Text("Context", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        HistorySlider("Context Tokens", mc.contextTokens) {
          mc = mc.copy(contextTokens = it)
          saveModelConfig()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Auto-compact at 95%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          Switch(checked = mc.autoCompact, onCheckedChange = {
            mc = mc.copy(autoCompact = it)
            saveModelConfig()
          })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = mc.systemPromptOverride ?: "",
          onValueChange = {
            mc = mc.copy(systemPromptOverride = it.ifBlank { null })
            saveModelConfig()
          },
          label = { Text("System Prompt") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 3,
          maxLines = 6,
        )
      }

      // ══════════════════════════════════════════════════════════════
      Spacer(Modifier.height(16.dp))
      var testResult by remember { mutableStateOf<String?>(null) }
      var testing by remember { mutableStateOf(false) }
      Button(onClick = {
        testing = true
        testResult = null
        scope.launch {
          testResult = testConnection(p, mc)
          testing = false
        }
      }, enabled = !testing, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "Testing…" else "Test Connection") }
      testResult?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (it.startsWith("[OK]")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      Spacer(Modifier.height(8.dp))
      Button(onClick = {
        saveModelConfig()
        store.saveModelCache(p.builtinId, models)
        onSave(p)
      }, modifier = Modifier.fillMaxWidth()) { Text("Save & Activate") }
      Spacer(Modifier.height(32.dp))
    }
  }
}

// ── Components ──

@Composable private fun Section(title: String) {
  Spacer(Modifier.height(16.dp))
  Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
  HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable private fun Field(label: String, value: String, placeholder: String = "", obfuscate: Boolean = false, onChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    visualTransformation = if (obfuscate) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    placeholder = if (placeholder.isNotBlank()) {
      { Text(placeholder) }
    } else {
      null
    },
  )
  Spacer(Modifier.height(8.dp))
}

@Composable private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label)
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable private fun LogSlider(label: String, value: Float?, min: Float, max: Float, onChange: (Float) -> Unit) {
  val v = value ?: 0f
  Text("$label: ${if (v <= 0f) "off" else "%.2f".format(v)}", style = MaterialTheme.typography.bodySmall)
  Slider(value = v, onValueChange = onChange, valueRange = min..max)
}

@Composable private fun StepSlider(label: String, value: Int?, steps: List<Int>, onChange: (Int) -> Unit) {
  val idx = if (value == null || value == 0) 0 else steps.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (steps.size - 1)
  val sv = steps[idx]
  val display = if (sv == 0) {
    "off"
  } else if (sv >= 1000) {
    "${sv / 1000}K"
  } else {
    sv.toString()
  }
  Text("$label: $display", style = MaterialTheme.typography.bodySmall)
  Slider(value = idx.toFloat(), onValueChange = { onChange(steps[it.toInt().coerceIn(0, steps.size - 1)]) }, valueRange = 0f..(steps.size - 1).toFloat())
}

@Composable private fun TopKSlider(label: String, value: Int?, onChange: (Int) -> Unit) {
  val v = value ?: 0
  Text("$label: ${if (v == 0) "off" else v.toString()}", style = MaterialTheme.typography.bodySmall)
  Slider(value = v.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..200f)
}

@Composable private fun HistorySlider(label: String, value: Int, onChange: (Int) -> Unit) {
  val idx = HISTORY_STEPS.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (HISTORY_STEPS.size - 1)
  val sv = HISTORY_STEPS[idx]
  val display = if (sv >= 1_000_000) {
    "${sv / 1_000_000}M"
  } else if (sv >= 1000) {
    "${sv / 1000}K"
  } else {
    sv.toString()
  }
  Text("$label: $display", style = MaterialTheme.typography.bodySmall)
  Slider(value = idx.toFloat(), onValueChange = { onChange(HISTORY_STEPS[it.toInt().coerceIn(0, HISTORY_STEPS.size - 1)]) }, valueRange = 0f..(HISTORY_STEPS.size - 1).toFloat())
}

// ── Network ──

private suspend fun testConnection(p: ProviderProfile, mc: ModelConfig): String = withContext(Dispatchers.IO) {
  try {
    var baseUrl = p.effectiveApiBase().trimEnd('/')
    val eo = mc.endpointOverride.trim().removePrefix("/")
    val chatPath = if (eo.isNotBlank()) {
      eo
    } else if (baseUrl.endsWith("/openai")) {
      "chat/completions"
    } else if (baseUrl.endsWith("/v1")) {
      baseUrl = baseUrl.removeSuffix("/v1")
      "v1/chat/completions"
    } else {
      "v1/chat/completions"
    }
    val url = "$baseUrl/$chatPath"
    val model = p.effectiveModel()
    if (model.isBlank()) return@withContext "[FAIL] No model selected"

    val messages = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "Reply with exactly: OK"))
    val body = org.json.JSONObject().apply {
      put("model", model)
      put("messages", messages)
      put("max_tokens", 10)
      mc.temperature?.let { put("temperature", it.toDouble()) }
    }
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    if (p.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.doOutput = true
    conn.outputStream.write(body.toString().toByteArray())
    if (conn.responseCode !in 200..299) {
      val err = try {
        conn.errorStream?.bufferedReader()?.readText()?.take(200)
      } catch (_: Exception) {
        null
      }
      return@withContext "[FAIL] HTTP ${conn.responseCode}: ${err ?: "error"}"
    }
    val resp = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
    val tokens = resp.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
    val results = mutableListOf("[OK] Chat: OK ($tokens tok)")

    if (mc.toolsOverride != false) {
      try {
        val tm = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "What is 2+2? Use the calculator tool."))
        val td = org.json.JSONArray().put(
          org.json.JSONObject().apply {
            put("type", "function")
            put(
              "function",
              org.json.JSONObject().apply {
                put("name", "calc")
                put("description", "Calculate")
                put("parameters", org.json.JSONObject().put("type", "object").put("properties", org.json.JSONObject().put("e", org.json.JSONObject().put("type", "string"))).put("required", org.json.JSONArray().put("e")))
              },
            )
          },
        )
        val tb = org.json.JSONObject().apply {
          put("model", model)
          put("messages", tm)
          put("tools", td)
          put("max_tokens", 50)
        }
        val tc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        tc.requestMethod = "POST"
        tc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) tc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        tc.connectTimeout = 15_000
        tc.readTimeout = 30_000
        tc.doOutput = true
        tc.outputStream.write(tb.toString().toByteArray())
        if (tc.responseCode in 200..299) {
          val tr = org.json.JSONObject(tc.inputStream.bufferedReader().readText())
          val calls = tr.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optJSONArray("tool_calls")
          results.add(if (calls != null && calls.length() > 0) "[OK] Tools: supported" else "[WARN] Tools: no tool_calls")
        } else {
          results.add("[FAIL] Tools: HTTP ${tc.responseCode}")
        }
      } catch (e: Exception) {
        results.add("[FAIL] Tools: ${e.message?.take(60)}")
      }
    }
    if (mc.visionOverride == true) {
      try {
        val vm = org.json.JSONArray().put(
          org.json.JSONObject().apply {
            put("role", "user")
            put(
              "content",
              org.json.JSONArray()
                .put(org.json.JSONObject().put("type", "text").put("text", "Describe in one word."))
                .put(org.json.JSONObject().put("type", "image_url").put("image_url", org.json.JSONObject().put("url", "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCABAAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD54itvarsVt7VcitvarsVt7V9xOuc2FxJTitvarsVt7VcitvarsVt7Vx1K59LhcSUorb2q7Fbe1XIrb2q7Fbe1cVSufSYXElOK29quxW3tVyG29quxW3tXHOufS4XEnn0Vt7VditvarkVt7Vditvau2pXP5ywuJKcVt7VditvarkVt7VditvauKdc+lwuJKUVt7VditvarkVt7VdhtvauOpXPpMLiSnFbe1XYrb2q5Fbe1XYrb2rjqVz6XC4k8+itvarsVt7VditvarkVt7V21K5/OeFxJTitvarsNt7VcitvarsVt7VxVK59JhcSU4rb2q5Fbe1XYbb2q5Fbe1cc659LhcSU4rb2q7Fbe1XIrb2q7Fbe1cdSufSYXEnn8Vt7VcitvarsVt7Vcitvauydc/nPC4kpw23tV2K29quRW3tV2G29q46lc+lwuJKcVt7VcitvarsVt7VcitvauOpXPpMLiSnFbe1XYrb2q5Fbe1XYrb2rinXPpcLiT/9k="))),
            )
          },
        )
        val vb = org.json.JSONObject().apply {
          put("model", model)
          put("messages", vm)
          put("max_tokens", 10)
        }
        val vc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        vc.requestMethod = "POST"
        vc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) vc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        vc.connectTimeout = 15_000
        vc.readTimeout = 30_000
        vc.doOutput = true
        vc.outputStream.write(vb.toString().toByteArray())
        results.add(if (vc.responseCode in 200..299) "[OK] Vision: supported" else "[FAIL] Vision: HTTP ${vc.responseCode}")
      } catch (e: Exception) {
        results.add("[FAIL] Vision: ${e.message?.take(60)}")
      }
    }
    if (mc.audioOverride == true) {
      try {
        val au = "$baseUrl/v1/audio/speech"
        val ab = org.json.JSONObject().apply {
          put("model", "tts-1")
          put("input", "test")
          put("voice", "alloy")
        }
        val ac = java.net.URL(au).openConnection() as java.net.HttpURLConnection
        ac.requestMethod = "POST"
        ac.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) ac.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        ac.connectTimeout = 10_000
        ac.readTimeout = 15_000
        ac.doOutput = true
        ac.outputStream.write(ab.toString().toByteArray())
        results.add(if (ac.responseCode in 200..299) "[OK] Audio: supported" else "[WARN] Audio: HTTP ${ac.responseCode}")
      } catch (e: Exception) {
        results.add("[WARN] Audio: ${e.message?.take(60)}")
      }
    }
    if (mc.videoOverride == true) {
      try {
        val vdm = org.json.JSONArray().put(
          org.json.JSONObject().apply {
            put("role", "user")
            put(
              "content",
              org.json.JSONArray()
                .put(org.json.JSONObject().put("type", "text").put("text", "Reply OK."))
                .put(org.json.JSONObject().put("type", "video_url").put("video_url", org.json.JSONObject().put("url", "https://example.com/test.mp4"))),
            )
          },
        )
        val vdb = org.json.JSONObject().apply {
          put("model", model)
          put("messages", vdm)
          put("max_tokens", 10)
        }
        val vdc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        vdc.requestMethod = "POST"
        vdc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) vdc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        vdc.connectTimeout = 15_000
        vdc.readTimeout = 30_000
        vdc.doOutput = true
        vdc.outputStream.write(vdb.toString().toByteArray())
        results.add(if (vdc.responseCode in 200..299) "[OK] Video: supported" else "[WARN] Video: HTTP ${vdc.responseCode}")
      } catch (e: Exception) {
        results.add("[WARN] Video: ${e.message?.take(60)}")
      }
    }
    results.joinToString("\n")
  } catch (e: Exception) {
    "[FAIL] ${e.message?.take(100)}"
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskModelScreen(providerStore: ProviderStore, onBack: () -> Unit) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val taskStore = remember { com.aiope2.core.network.TaskModelStore(ctx) }
  val profiles = providerStore.getAll()

  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Default Models per Task") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
      item {
        Card(
          Modifier.fillMaxWidth().padding(vertical = 8.dp),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        ) {
          Column(Modifier.padding(16.dp)) {
            Text(
              "Assign different models to different tasks. Each task falls back to the active profile if not configured.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      items(com.aiope2.core.network.ModelTask.configurable) { task ->
        TaskCard(task, taskStore, providerStore, profiles)
        Spacer(Modifier.height(8.dp))
      }

      item { Spacer(Modifier.height(32.dp)) }
    }
  }
}

@Composable
private fun TaskCard(
  task: com.aiope2.core.network.ModelTask,
  taskStore: com.aiope2.core.network.TaskModelStore,
  providerStore: ProviderStore,
  profiles: List<ProviderProfile>,
) {
  var tc by remember { mutableStateOf(taskStore.getTaskConfig(task)) }
  var expanded by remember { mutableStateOf(false) }
  val assignedProfile = tc.profileId?.let { pid -> profiles.firstOrNull { it.id == pid } }
  val displayConfig = assignedProfile?.label ?: "Active profile"
  val displayModel = tc.modelId ?: assignedProfile?.selectedModelId ?: "default"

  Card(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
  ) {
    Column {
      // Header — tap to expand
      Surface(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface,
      ) {
        Column(Modifier.padding(16.dp)) {
          Text(task.label, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
          Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(8.dp))

          // Current assignment
          Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          ) {
            Row(
              Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(Modifier.weight(1f)) {
                Text("Config: $displayConfig", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text("Model: $displayModel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Expanded: profile + model selection
      androidx.compose.animation.AnimatedVisibility(visible = expanded) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          Text(
            "Select configuration",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
          )

          // "Use active profile" option
          val isDefault = tc.profileId == null
          Surface(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
              taskStore.clearTaskConfig(task)
              tc = com.aiope2.core.network.TaskModelConfig(task.id)
              expanded = false
            },
            shape = RoundedCornerShape(8.dp),
            color = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
              if (isDefault) 0.dp else 0.5.dp,
              if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
          ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
              if (isDefault) {
                Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
              }
              Text(
                "Use active profile (default)",
                fontWeight = if (isDefault) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
            }
          }

          // Each profile with its models
          profiles.forEach { profile ->
            var profileExpanded by remember { mutableStateOf(false) }
            val models = providerStore.getModelCache(profile.builtinId)
              ?: providerStore.getModelCacheStale(profile.builtinId)
              ?: com.aiope2.core.network.ProviderTemplates.byId[profile.builtinId]?.defaultModels ?: emptyList()
            val isProfileSelected = tc.profileId == profile.id

            Surface(
              Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                if (models.size > 1) {
                  profileExpanded = !profileExpanded
                } else {
                  val newTc = com.aiope2.core.network.TaskModelConfig(task.id, profile.id, profile.selectedModelId)
                  taskStore.setTaskConfig(task, newTc)
                  tc = newTc
                  expanded = false
                }
              },
              shape = RoundedCornerShape(8.dp),
              color = if (isProfileSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
              border = androidx.compose.foundation.BorderStroke(
                if (isProfileSelected) 0.dp else 0.5.dp,
                if (isProfileSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              ),
            ) {
              Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isProfileSelected && models.size <= 1) {
                  Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                  Text(
                    profile.label,
                    fontWeight = if (isProfileSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (isProfileSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  )
                  if (models.size > 1) {
                    Text("${models.size} models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  } else {
                    Text(profile.selectedModelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                if (models.size > 1) Icon(if (profileExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }

            // Sub-expand: individual models
            androidx.compose.animation.AnimatedVisibility(visible = profileExpanded && models.size > 1) {
              Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)) {
                models.forEach { m ->
                  val isModelSelected = isProfileSelected && tc.modelId == m.id
                  Surface(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                      val newTc = com.aiope2.core.network.TaskModelConfig(task.id, profile.id, m.id)
                      taskStore.setTaskConfig(task, newTc)
                      tc = newTc
                      expanded = false
                      profileExpanded = false
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isModelSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                      if (isModelSelected) {
                        Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                      }
                      Text(
                        m.displayName,
                        fontSize = 13.sp,
                        fontWeight = if (isModelSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (isModelSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
    }
  }
}
private suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelDef> = withContext(Dispatchers.IO) {
  try {
    var base = baseUrl.trimEnd('/')
    val url = when {
      base.endsWith("/v1") || base.endsWith("/openai") -> "$base/models"
      else -> "$base/v1/models"
    }
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    val body = conn.inputStream.bufferedReader().readText()
    val data = org.json.JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
    (0 until data.length()).map {
      val o = data.getJSONObject(it)
      val id = o.getString("id")
      val name = o.optString("name", id).ifBlank { id }
      val inputMods = o.optJSONObject("modalities")?.optJSONArray("input")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
      val outputMods = o.optJSONObject("modalities")?.optJSONArray("output")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
      val outMod = when {
        "image" in outputMods -> "image"
        "audio" in outputMods -> "audio"
        id.startsWith("cf-image/") -> "image"
        else -> "text"
      }
      ModelDef(
        id, name,
        contextWindow = o.optInt("context_window"),
        supportsTools = o.optBoolean("tool_call", outMod == "text"),
        supportsVision = "image" in inputMods || o.optBoolean("attachment"),
        supportsAudio = "audio" in inputMods,
        supportsVideo = "video" in inputMods,
        supportsReasoning = o.optBoolean("reasoning"),
        outputModality = outMod,
        maxOutput = o.optInt("max_output"),
        family = o.optString("family", ""),
      )
    }.sortedBy { it.id }
  } catch (e: Exception) {
    android.util.Log.e("FetchModels", "Failed: ${e.message}")
    emptyList()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolToggleScreen(toolStore: ToolStore, onBack: () -> Unit) {
  val tools = listOf(
    "run_sh" to "Android shell commands",
    "run_proot" to "Alpine proot Linux environment",
    "read_file" to "Read file contents",
    "write_file" to "Write files",
    "list_directory" to "List directory contents",
    "get_location" to "GPS location",
    "search_location" to "Search places",
    "open_intent" to "Open URLs, maps, apps",
    "fetch_url" to "Fetch web pages",
    "query_data" to "Live data queries",
    "search_web" to "Web search",
    "browser_navigate" to "Browser navigation",
    "browser_content" to "Read browser page",
    "browser_elements" to "List browser elements",
    "browser_click" to "Click browser elements",
    "browser_fill" to "Fill browser inputs",
    "browser_eval" to "Run JavaScript in browser",
    "browser_back" to "Browser back",
    "browser_scroll" to "Scroll browser",
    "browser_open" to "Open browser panel",
    "browser_close" to "Close browser panel",
    "browser_maximize" to "Maximize browser",
    "memory_store" to "Store persistent memories",
    "memory_recall" to "Recall memories",
    "memory_forget" to "Delete memories",
  )
  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Tools") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        var uiEnabled by remember { mutableStateOf(toolStore.isDynamicUiEnabled()) }
        ListItem(
          headlineContent = { Text("Dynamic UI", fontSize = 14.sp) },
          supportingContent = { Text("Enable aiope-ui rich interactive blocks in responses", style = MaterialTheme.typography.bodySmall) },
          trailingContent = {
            Switch(checked = uiEnabled, onCheckedChange = {
              uiEnabled = it
              toolStore.setDynamicUiEnabled(it)
            })
          },
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
      }
      items(tools.size) { i ->
        val (id, desc) = tools[i]
        var enabled by remember { mutableStateOf(toolStore.isToolEnabled(id)) }
        ListItem(
          headlineContent = { Text(id, fontSize = 14.sp) },
          supportingContent = { Text(desc, style = MaterialTheme.typography.bodySmall) },
          trailingContent = {
            Switch(checked = enabled, onCheckedChange = {
              enabled = it
              toolStore.setToolEnabled(id, it)
            })
          },
        )
        if (i < tools.size - 1) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerScreen(toolStore: ToolStore, onBack: () -> Unit) {
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

  Scaffold(topBar = {
    TopAppBar(
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
          colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(0.4f)),
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

  Scaffold(topBar = {
    TopAppBar(
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
  }) { pad ->
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
