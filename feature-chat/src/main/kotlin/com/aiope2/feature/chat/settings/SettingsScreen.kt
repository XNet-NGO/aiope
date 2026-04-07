package com.aiope2.feature.chat.settings

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
private val HISTORY_STEPS = listOf(2, 4, 6, 8, 10, 12, 16, 20, 30, 40, 50, 75, 100, 150, 200, 0)
private val ENDPOINT_PRESETS = listOf("/chat/completions", "/completions", "/responses", "/embeddings", "/rerank",
  "/audio/speech", "/audio/transcriptions", "/images/generations", "/moderations")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(providerStore: ProviderStore, onBack: () -> Unit) {
  var screen by remember { mutableStateOf("list") }
  var editId by remember { mutableStateOf<String?>(null) }
  var profiles by remember { mutableStateOf(providerStore.getAll()) }
  var activeId by remember { mutableStateOf(providerStore.getActive().id) }
  fun refresh() { profiles = providerStore.getAll(); activeId = providerStore.getActive().id }

  when (screen) {
    "list" -> ProfileList(profiles, activeId,
      onSelect = { providerStore.setActive(it.id); activeId = it.id },
      onEdit = { editId = it.id; screen = "edit" },
      onAdd = { screen = "pick" }, onTasks = { screen = "tasks" }, onBack = onBack)
    "pick" -> TemplatePicker(
      onPick = { b ->
        val p = ProviderProfile(builtinId = b.id, label = b.displayName,
          apiBase = b.apiBase ?: "", selectedModelId = b.defaultModels.firstOrNull()?.id ?: "")
        providerStore.save(p); providerStore.setActive(p.id); editId = p.id; refresh(); screen = "edit"
      }, onBack = { screen = "list" })
    "edit" -> editId?.let { providerStore.getById(it) }?.let { profile ->
      ProfileEditor(profile, providerStore,
        onSave = { providerStore.save(it); providerStore.setActive(it.id); refresh(); screen = "list" },
        onDelete = { providerStore.delete(profile.id); refresh(); screen = "list" },
        onBack = { screen = "list" })
    }
    "tasks" -> TaskModelScreen(providerStore, onBack = { screen = "list" })
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProfileList(profiles: List<ProviderProfile>, activeId: String,
  onSelect: (ProviderProfile) -> Unit, onEdit: (ProviderProfile) -> Unit, onAdd: () -> Unit, onTasks: () -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = { TopAppBar(title = { Text("Providers") },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") } })
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        ListItem(
          headlineContent = { Text("🎯 Default Models per Task") },
          supportingContent = { Text("Set different models for chat, agent, titles, etc.", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onTasks() }
        )
        HorizontalDivider()
      }
      items(profiles) { p ->
        val builtin = ProviderTemplates.byId[p.builtinId]
        ListItem(
          headlineContent = { Text("${builtin?.icon ?: "⚙️"} ${p.label.ifBlank { builtin?.displayName ?: "Custom" }}") },
          supportingContent = { Text(p.selectedModelId.ifBlank { "no model" }, style = MaterialTheme.typography.bodySmall) },
          trailingContent = { if (p.id == activeId) Text("✓", color = MaterialTheme.colorScheme.primary) },
          modifier = Modifier.combinedClickable(onClick = { onSelect(p) }, onLongClick = { onEdit(p) })
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePicker(onPick: (BuiltinProvider) -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = { TopAppBar(title = { Text("Add Provider") },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      items(ProviderTemplates.ALL) { b ->
        ListItem(headlineContent = { Text("${b.icon} ${b.displayName}") },
          supportingContent = { Text(b.apiBase ?: "Custom endpoint", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onPick(b) })
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(profile: ProviderProfile, store: ProviderStore,
  onSave: (ProviderProfile) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
  var p by remember { mutableStateOf(profile) }
  val builtin = ProviderTemplates.byId[p.builtinId]
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(false) }
  var models by remember { mutableStateOf(
    store.getModelCache(p.builtinId) ?: store.getModelCacheStale(p.builtinId) ?: builtin?.defaultModels ?: emptyList()) }
  var modelExpanded by remember { mutableStateOf(false) }
  var customModelText by remember { mutableStateOf("") }

  // Current model config (read from profile, update back into profile)
  var mc by remember(p.selectedModelId) { mutableStateOf(p.activeModelConfig()) }
  fun saveModelConfig() { p = p.copy(modelConfigs = p.modelConfigs + (mc.modelId to mc)) }

  Scaffold(topBar = { TopAppBar(title = { Text(p.label.ifBlank { "Edit Provider" }) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    actions = { IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {

      // ══════════════════════════════════════════════════════════════
      // PROVIDER SETTINGS (shared across all models)
      // ══════════════════════════════════════════════════════════════
      Section("Provider")
      Field("Name", p.label) { p = p.copy(label = it) }
      Field("Base URL", p.apiBase, builtin?.apiBase ?: "https://api.example.com/v1") { p = p.copy(apiBase = it) }
      if (builtin?.requiresApiKey != false)
        Field("API Key", p.apiKey, builtin?.apiKeyHint ?: "API key") { p = p.copy(apiKey = it) }

      // Model selector
      Section("Model")
      Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
          loading = true
          scope.launch {
            val fetched = fetchModels(p.effectiveApiBase(), p.apiKey)
            if (fetched.isNotEmpty()) { store.saveModelCache(p.builtinId, fetched); models = fetched }
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
            "${m.displayName}  ${if (m.contextWindow > 0) "${m.contextWindow/1000}k" else ""}${if (m.supportsTools) " 🔧" else ""}${if (m.supportsVision) " 👁" else ""}"
          } ?: p.selectedModelId.ifBlank { "Select model" },
          onValueChange = {}, readOnly = true, label = { Text("Selected Model") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
          modifier = Modifier.fillMaxWidth().menuAnchor())
        ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
          models.forEach { m ->
            DropdownMenuItem(text = { Text("${m.displayName}  ${if (m.contextWindow>0) "${m.contextWindow/1000}k" else ""}${if (m.supportsTools) " 🔧" else ""}${if (m.supportsVision) " 👁" else ""}") },
              onClick = { p = p.copy(selectedModelId = m.id); mc = p.modelConfigs[m.id] ?: ModelConfig(modelId = m.id); modelExpanded = false })
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = customModelText, onValueChange = { customModelText = it },
          label = { Text("Add Custom Model") }, modifier = Modifier.weight(1f), singleLine = true)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = {
          if (customModelText.isNotBlank()) {
            val nm = ModelDef(customModelText.trim(), customModelText.trim().substringAfterLast("/"), 128_000)
            models = listOf(nm) + models
            p = p.copy(selectedModelId = nm.id); mc = p.modelConfigs[nm.id] ?: ModelConfig(modelId = nm.id)
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
          OutlinedTextField(value = mc.endpointOverride.ifBlank { "/chat/completions" },
            onValueChange = { mc = mc.copy(endpointOverride = it); saveModelConfig() },
            label = { Text("Endpoint Override") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = epExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor())
          ExposedDropdownMenu(expanded = epExpanded, onDismissRequest = { epExpanded = false }) {
            ENDPOINT_PRESETS.forEach { ep ->
              DropdownMenuItem(text = { Text(ep) }, onClick = { mc = mc.copy(endpointOverride = ep); saveModelConfig(); epExpanded = false })
            }
          }
        }
        Spacer(Modifier.height(8.dp))

        // Abilities
        Text("Abilities", style = MaterialTheme.typography.labelMedium)
        val autoDetect = mc.toolsOverride == null && mc.visionOverride == null && mc.audioOverride == null && mc.videoOverride == null
        var auto by remember(mc.modelId) { mutableStateOf(autoDetect) }
        LabeledSwitch("Auto-detect", auto) { auto = it
          if (it) { mc = mc.copy(toolsOverride = null, visionOverride = null, audioOverride = null, videoOverride = null); saveModelConfig() }
        }
        if (!auto) {
          LabeledSwitch("Tool Calling", mc.toolsOverride ?: true) { mc = mc.copy(toolsOverride = it); saveModelConfig() }
          LabeledSwitch("Vision", mc.visionOverride ?: false) { mc = mc.copy(visionOverride = it); saveModelConfig() }
          LabeledSwitch("Audio", mc.audioOverride ?: false) { mc = mc.copy(audioOverride = it); saveModelConfig() }
          LabeledSwitch("Video", mc.videoOverride ?: false) { mc = mc.copy(videoOverride = it); saveModelConfig() }
        }

        // Parameters
        Text("Parameters", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        LogSlider("Temperature", mc.temperature, 0f, 2f) { mc = mc.copy(temperature = if (it <= 0f) null else it); saveModelConfig() }
        LogSlider("Top-P", mc.topP, 0f, 1f) { mc = mc.copy(topP = if (it <= 0f) null else it); saveModelConfig() }
        StepSlider("Max Tokens", mc.maxTokens, TOKEN_STEPS) { mc = mc.copy(maxTokens = if (it == 0) null else it); saveModelConfig() }
        TopKSlider("Top-K", mc.topK) { mc = mc.copy(topK = if (it == 0) null else it); saveModelConfig() }

        // Context
        Text("Context", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        HistorySlider("History Messages", mc.maxContextMessages) { mc = mc.copy(maxContextMessages = if (it == 0) null else it); saveModelConfig() }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = mc.systemPromptOverride ?: "", onValueChange = { mc = mc.copy(systemPromptOverride = it.ifBlank { null }); saveModelConfig() },
          label = { Text("System Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
      }

      // ══════════════════════════════════════════════════════════════
      Spacer(Modifier.height(16.dp))
      var testResult by remember { mutableStateOf<String?>(null) }
      var testing by remember { mutableStateOf(false) }
      Button(onClick = { testing = true; testResult = null
        scope.launch { testResult = testConnection(p, mc); testing = false }
      }, enabled = !testing, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "Testing…" else "Test Connection") }
      testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall,
        color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp)) }
      Spacer(Modifier.height(8.dp))
      Button(onClick = { saveModelConfig(); onSave(p) }, modifier = Modifier.fillMaxWidth()) { Text("Save & Activate") }
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
@Composable private fun Field(label: String, value: String, placeholder: String = "", onChange: (String) -> Unit) {
  OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
    modifier = Modifier.fillMaxWidth(), singleLine = true,
    placeholder = if (placeholder.isNotBlank()) {{ Text(placeholder) }} else null)
  Spacer(Modifier.height(8.dp))
}
@Composable private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label); Switch(checked = checked, onCheckedChange = onChange)
  }
}
@Composable private fun LogSlider(label: String, value: Float?, min: Float, max: Float, onChange: (Float) -> Unit) {
  val v = value ?: 0f
  Text("$label: ${if (v <= 0f) "off" else "%.2f".format(v)}", style = MaterialTheme.typography.bodySmall)
  Slider(value = v, onValueChange = onChange, valueRange = min..max)
}
@Composable private fun StepSlider(label: String, value: Int?, steps: List<Int>, onChange: (Int) -> Unit) {
  val idx = if (value == null || value == 0) 0 else steps.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (steps.size - 1)
  val sv = steps[idx]; val display = if (sv == 0) "off" else if (sv >= 1000) "${sv / 1000}K" else sv.toString()
  Text("$label: $display", style = MaterialTheme.typography.bodySmall)
  Slider(value = idx.toFloat(), onValueChange = { onChange(steps[it.toInt().coerceIn(0, steps.size - 1)]) }, valueRange = 0f..(steps.size - 1).toFloat())
}
@Composable private fun TopKSlider(label: String, value: Int?, onChange: (Int) -> Unit) {
  val v = value ?: 0
  Text("$label: ${if (v == 0) "off" else v.toString()}", style = MaterialTheme.typography.bodySmall)
  Slider(value = v.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..200f)
}
@Composable private fun HistorySlider(label: String, value: Int?, onChange: (Int) -> Unit) {
  val idx = if (value == null || value == 0) HISTORY_STEPS.size - 1 else HISTORY_STEPS.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (HISTORY_STEPS.size - 1)
  val sv = HISTORY_STEPS[idx]; val display = if (sv == 0) "∞" else sv.toString()
  Text("$label: $display", style = MaterialTheme.typography.bodySmall)
  Slider(value = idx.toFloat(), onValueChange = { onChange(HISTORY_STEPS[it.toInt().coerceIn(0, HISTORY_STEPS.size - 1)]) }, valueRange = 0f..(HISTORY_STEPS.size - 1).toFloat())
}

// ── Network ──

private suspend fun testConnection(p: ProviderProfile, mc: ModelConfig): String = withContext(Dispatchers.IO) {
  try {
    var baseUrl = p.effectiveApiBase().trimEnd('/')
    val eo = mc.endpointOverride.trim().removePrefix("/")
    val chatPath = if (eo.isNotBlank()) eo
      else if (baseUrl.endsWith("/openai")) "chat/completions"
      else if (baseUrl.endsWith("/v1")) { baseUrl = baseUrl.removeSuffix("/v1"); "v1/chat/completions" }
      else "v1/chat/completions"
    val url = "$baseUrl/$chatPath"
    val model = p.effectiveModel()
    if (model.isBlank()) return@withContext "✗ No model selected"

    val messages = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "Reply with exactly: OK"))
    val body = org.json.JSONObject().apply { put("model", model); put("messages", messages); put("max_tokens", 10)
      mc.temperature?.let { put("temperature", it.toDouble()) } }
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json")
    if (p.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
    conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.doOutput = true
    conn.outputStream.write(body.toString().toByteArray())
    if (conn.responseCode !in 200..299) {
      val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
      return@withContext "✗ HTTP ${conn.responseCode}: ${err ?: "error"}"
    }
    val resp = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
    val tokens = resp.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
    val results = mutableListOf("✓ Chat: OK ($tokens tok)")

    if (mc.toolsOverride != false) {
      try {
        val tm = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "What is 2+2? Use the calculator tool."))
        val td = org.json.JSONArray().put(org.json.JSONObject().apply { put("type", "function"); put("function", org.json.JSONObject().apply {
          put("name", "calc"); put("description", "Calculate"); put("parameters", org.json.JSONObject().put("type", "object").put("properties", org.json.JSONObject().put("e", org.json.JSONObject().put("type", "string"))).put("required", org.json.JSONArray().put("e")))
        }) })
        val tb = org.json.JSONObject().apply { put("model", model); put("messages", tm); put("tools", td); put("max_tokens", 50) }
        val tc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        tc.requestMethod = "POST"; tc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) tc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        tc.connectTimeout = 15_000; tc.readTimeout = 30_000; tc.doOutput = true; tc.outputStream.write(tb.toString().toByteArray())
        if (tc.responseCode in 200..299) {
          val tr = org.json.JSONObject(tc.inputStream.bufferedReader().readText())
          val calls = tr.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optJSONArray("tool_calls")
          results.add(if (calls != null && calls.length() > 0) "✓ Tools: supported" else "⚠ Tools: no tool_calls")
        } else results.add("✗ Tools: HTTP ${tc.responseCode}")
      } catch (e: Exception) { results.add("✗ Tools: ${e.message?.take(60)}") }
    }
    if (mc.visionOverride == true) {
      try {
        val vm = org.json.JSONArray().put(org.json.JSONObject().apply { put("role", "user"); put("content", org.json.JSONArray()
          .put(org.json.JSONObject().put("type", "text").put("text", "Describe in one word."))
          .put(org.json.JSONObject().put("type", "image_url").put("image_url", org.json.JSONObject().put("url", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==")))) })
        val vb = org.json.JSONObject().apply { put("model", model); put("messages", vm); put("max_tokens", 10) }
        val vc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        vc.requestMethod = "POST"; vc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) vc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        vc.connectTimeout = 15_000; vc.readTimeout = 30_000; vc.doOutput = true; vc.outputStream.write(vb.toString().toByteArray())
        results.add(if (vc.responseCode in 200..299) "✓ Vision: supported" else "✗ Vision: HTTP ${vc.responseCode}")
      } catch (e: Exception) { results.add("✗ Vision: ${e.message?.take(60)}") }
    }
    if (mc.audioOverride == true) {
      try {
        val au = "$baseUrl/v1/audio/speech"
        val ab = org.json.JSONObject().apply { put("model", "tts-1"); put("input", "test"); put("voice", "alloy") }
        val ac = java.net.URL(au).openConnection() as java.net.HttpURLConnection
        ac.requestMethod = "POST"; ac.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) ac.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        ac.connectTimeout = 10_000; ac.readTimeout = 15_000; ac.doOutput = true; ac.outputStream.write(ab.toString().toByteArray())
        results.add(if (ac.responseCode in 200..299) "✓ Audio: supported" else "⚠ Audio: HTTP ${ac.responseCode}")
      } catch (e: Exception) { results.add("⚠ Audio: ${e.message?.take(60)}") }
    }
    if (mc.videoOverride == true) {
      try {
        val vdm = org.json.JSONArray().put(org.json.JSONObject().apply { put("role", "user"); put("content", org.json.JSONArray()
          .put(org.json.JSONObject().put("type", "text").put("text", "Reply OK."))
          .put(org.json.JSONObject().put("type", "video_url").put("video_url", org.json.JSONObject().put("url", "https://example.com/test.mp4")))) })
        val vdb = org.json.JSONObject().apply { put("model", model); put("messages", vdm); put("max_tokens", 10) }
        val vdc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        vdc.requestMethod = "POST"; vdc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) vdc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        vdc.connectTimeout = 15_000; vdc.readTimeout = 30_000; vdc.doOutput = true; vdc.outputStream.write(vdb.toString().toByteArray())
        results.add(if (vdc.responseCode in 200..299) "✓ Video: supported" else "⚠ Video: HTTP ${vdc.responseCode}")
      } catch (e: Exception) { results.add("⚠ Video: ${e.message?.take(60)}") }
    }
    results.joinToString("\n")
  } catch (e: Exception) { "✗ ${e.message?.take(100)}" }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskModelScreen(providerStore: ProviderStore, onBack: () -> Unit) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val taskStore = remember { com.aiope2.core.network.TaskModelStore(ctx) }
  val profiles = providerStore.getAll()

  Scaffold(topBar = { TopAppBar(title = { Text("Default Models per Task") },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
      item {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))) {
          Column(Modifier.padding(16.dp)) {
            Text("Assign different models to different tasks. Each task falls back to the active profile if not configured.",
              style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
  profiles: List<ProviderProfile>
) {
  var tc by remember { mutableStateOf(taskStore.getTaskConfig(task)) }
  var expanded by remember { mutableStateOf(false) }
  val assignedProfile = tc.profileId?.let { pid -> profiles.firstOrNull { it.id == pid } }
  val displayConfig = assignedProfile?.label ?: "Active profile"
  val displayModel = tc.modelId ?: assignedProfile?.selectedModelId ?: "default"

  Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
    Column {
      // Header — tap to expand
      Surface(Modifier.fillMaxWidth().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp)) {
          Text(task.label, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
          Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(8.dp))

          // Current assignment
          Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween) {
              Column(Modifier.weight(1f)) {
                Text("Config: $displayConfig", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text("Model: $displayModel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }

      // Expanded: profile + model selection
      androidx.compose.animation.AnimatedVisibility(visible = expanded) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          Text("Select configuration", style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))

          // "Use active profile" option
          val isDefault = tc.profileId == null
          Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
            taskStore.clearTaskConfig(task); tc = com.aiope2.core.network.TaskModelConfig(task.id); expanded = false
          }, shape = RoundedCornerShape(8.dp),
            color = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(if (isDefault) 0.dp else 0.5.dp,
              if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
              if (isDefault) { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
              Text("Use active profile (default)", fontWeight = if (isDefault) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
          }

          // Each profile with its models
          profiles.forEach { profile ->
            var profileExpanded by remember { mutableStateOf(false) }
            val models = providerStore.getModelCache(profile.builtinId)
              ?: providerStore.getModelCacheStale(profile.builtinId)
              ?: com.aiope2.core.network.ProviderTemplates.byId[profile.builtinId]?.defaultModels ?: emptyList()
            val isProfileSelected = tc.profileId == profile.id

            Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
              if (models.size > 1) profileExpanded = !profileExpanded
              else {
                val newTc = com.aiope2.core.network.TaskModelConfig(task.id, profile.id, profile.selectedModelId)
                taskStore.setTaskConfig(task, newTc); tc = newTc; expanded = false
              }
            }, shape = RoundedCornerShape(8.dp),
              color = if (isProfileSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
              border = androidx.compose.foundation.BorderStroke(if (isProfileSelected) 0.dp else 0.5.dp,
                if (isProfileSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
              Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isProfileSelected && models.size <= 1) { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                  Text(profile.label, fontWeight = if (isProfileSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (isProfileSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                  if (models.size > 1) Text("${models.size} models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  else Text(profile.selectedModelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (models.size > 1) Icon(if (profileExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }

            // Sub-expand: individual models
            androidx.compose.animation.AnimatedVisibility(visible = profileExpanded && models.size > 1) {
              Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)) {
                models.forEach { m ->
                  val isModelSelected = isProfileSelected && tc.modelId == m.id
                  Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                    val newTc = com.aiope2.core.network.TaskModelConfig(task.id, profile.id, m.id)
                    taskStore.setTaskConfig(task, newTc); tc = newTc; expanded = false; profileExpanded = false
                  }, shape = RoundedCornerShape(6.dp),
                    color = if (isModelSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                      if (isModelSelected) { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                      Text(m.displayName, fontSize = 13.sp, fontWeight = if (isModelSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (isModelSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
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
    val url = when { base.endsWith("/v1") || base.endsWith("/openai") -> "$base/models"; else -> "$base/v1/models" }
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 15_000; conn.readTimeout = 15_000
    val body = conn.inputStream.bufferedReader().readText()
    val data = org.json.JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
    (0 until data.length()).map { val o = data.getJSONObject(it)
      val id = o.getString("id"); val name = o.optString("display_name", "").ifBlank { o.optString("name", id) }
      ModelDef(id, name, o.optInt("context_window"))
    }.sortedBy { it.id }
  } catch (e: Exception) { android.util.Log.e("FetchModels", "Failed: ${e.message}"); emptyList() }
}
