package com.aiope2.feature.chat.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiope2.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TOKEN_STEPS = listOf(0, 256, 512, 1024, 2048, 4096, 8192, 16000, 32000, 64000, 128000, 200000, 500000, 1000000)
private val HISTORY_STEPS = listOf(2, 4, 6, 8, 10, 12, 16, 20, 30, 40, 50, 75, 100, 150, 200, 0)

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
      onAdd = { screen = "pick" }, onBack = onBack)
    "pick" -> TemplatePicker(
      onPick = { builtin ->
        val p = ProviderProfile(builtinId = builtin.id, label = builtin.displayName,
          apiBase = builtin.apiBase ?: "", selectedModelId = builtin.defaultModels.firstOrNull()?.id ?: "")
        providerStore.save(p); providerStore.setActive(p.id); editId = p.id; refresh(); screen = "edit"
      }, onBack = { screen = "list" })
    "edit" -> editId?.let { providerStore.getById(it) }?.let { profile ->
      ProfileEditor(profile, providerStore,
        onSave = { providerStore.save(it); providerStore.setActive(it.id); refresh(); screen = "list" },
        onDelete = { providerStore.delete(profile.id); refresh(); screen = "list" },
        onBack = { screen = "list" })
    }
  }
}

// ── Provider List ──

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProfileList(profiles: List<ProviderProfile>, activeId: String,
  onSelect: (ProviderProfile) -> Unit, onEdit: (ProviderProfile) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = { TopAppBar(title = { Text("Providers") },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") } })
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
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

// ── Template Picker ──

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

// ── Profile Editor ──

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
  // Model dropdown
  var modelExpanded by remember { mutableStateOf(false) }
  // Custom model input
  var customModelText by remember { mutableStateOf("") }

  Scaffold(topBar = { TopAppBar(title = { Text(p.label.ifBlank { "Edit Provider" }) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    actions = { IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {

      // ── Profile ──
      Section("Profile")
      Field("Label", p.label) { p = p.copy(label = it) }

      // ── Connection ──
      Section("Connection")
      Field("Base URL", p.apiBase, builtin?.apiBase ?: "https://api.example.com/v1") { p = p.copy(apiBase = it) }

      // Endpoint override with preset dropdown
      var endpointExpanded by remember { mutableStateOf(false) }
      val endpointPresets = listOf(
        "/chat/completions", "/completions", "/responses",
        "/embeddings", "/rerank",
        "/audio/speech", "/audio/transcriptions",
        "/images/generations", "/moderations"
      )
      ExposedDropdownMenuBox(expanded = endpointExpanded, onExpandedChange = { endpointExpanded = it }) {
        OutlinedTextField(
          value = p.endpointOverride.ifBlank { "/chat/completions" },
          onValueChange = { p = p.copy(endpointOverride = it) },
          label = { Text("Endpoint Override") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = endpointExpanded) },
          modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = endpointExpanded, onDismissRequest = { endpointExpanded = false }) {
          endpointPresets.forEach { ep ->
            DropdownMenuItem(text = { Text(ep) },
              onClick = { p = p.copy(endpointOverride = ep); endpointExpanded = false })
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      if (builtin?.requiresApiKey != false)
        Field("API Key", p.apiKey, builtin?.apiKeyHint ?: "API key") { p = p.copy(apiKey = it) }

      // ── Model ──
      Section("Model")

      // Load models button
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

      // Model dropdown (like AIOPE v1 spinner)
      ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
        OutlinedTextField(
          value = models.firstOrNull { it.id == p.selectedModelId }?.let { m ->
            buildString {
              append(m.displayName)
              if (m.contextWindow > 0) append("  ${m.contextWindow / 1000}k")
              if (m.supportsTools) append(" 🔧")
              if (m.supportsVision) append(" 👁")
            }
          } ?: p.selectedModelId.ifBlank { "Select model" },
          onValueChange = {},
          readOnly = true,
          label = { Text("Selected Model") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
          modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
          models.forEach { m ->
            DropdownMenuItem(
              text = {
                Text(buildString {
                  append(m.displayName)
                  if (m.contextWindow > 0) append("  ${m.contextWindow / 1000}k")
                  if (m.supportsTools) append(" 🔧")
                  if (m.supportsVision) append(" 👁")
                })
              },
              onClick = { p = p.copy(selectedModelId = m.id); modelExpanded = false }
            )
          }
        }
      }
      Spacer(Modifier.height(8.dp))

      // Add custom model (additive, not override)
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = customModelText, onValueChange = { customModelText = it },
          label = { Text("Add Custom Model") }, modifier = Modifier.weight(1f), singleLine = true,
          placeholder = { Text("model-id") })
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = {
          if (customModelText.isNotBlank()) {
            val newModel = ModelDef(customModelText.trim(), customModelText.trim().substringAfterLast("/"), 128_000)
            models = listOf(newModel) + models
            p = p.copy(selectedModelId = newModel.id)
            customModelText = ""
          }
        }) { Icon(Icons.Default.Add, "Add") }
      }

      // ── Abilities ──
      Section("Abilities")
      val autoDetect = p.toolsOverride == null && p.visionOverride == null
      var auto by remember { mutableStateOf(autoDetect) }
      LabeledSwitch("Auto-detect", auto) { auto = it
        if (it) p = p.copy(toolsOverride = null, visionOverride = null, audioOverride = null, videoOverride = null)
      }
      if (!auto) {
        LabeledSwitch("Tool Calling", p.toolsOverride ?: true) { p = p.copy(toolsOverride = it) }
        LabeledSwitch("Vision", p.visionOverride ?: false) { p = p.copy(visionOverride = it) }
        LabeledSwitch("Audio", p.audioOverride ?: false) { p = p.copy(audioOverride = it) }
        LabeledSwitch("Video", p.videoOverride ?: false) { p = p.copy(videoOverride = it) }
      }

      // ── Parameters ──
      Section("Parameters")
      LogSlider("Temperature", p.temperature, 0f, 2f) { p = p.copy(temperature = if (it <= 0f) null else it) }
      LogSlider("Top-P", p.topP, 0f, 1f) { p = p.copy(topP = if (it <= 0f) null else it) }
      StepSlider("Max Tokens", p.maxTokens, TOKEN_STEPS) { p = p.copy(maxTokens = if (it == 0) null else it) }
      TopKSlider("Top-K", p.topK) { p = p.copy(topK = if (it == 0) null else it) }

      // ── Context ──
      Section("Context")
      HistorySlider("History Messages", p.maxContextMessages) { p = p.copy(maxContextMessages = if (it == 0) null else it) }
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = p.systemPromptOverride ?: "", onValueChange = { p = p.copy(systemPromptOverride = it.ifBlank { null }) },
        label = { Text("System Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)

      Spacer(Modifier.height(16.dp))
      // Test connection
      var testResult by remember { mutableStateOf<String?>(null) }
      var testing by remember { mutableStateOf(false) }
      Button(onClick = {
        testing = true; testResult = null
        scope.launch {
          testResult = testConnection(p)
          testing = false
        }
      }, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
        Text(if (testing) "Testing…" else "Test Connection")
      }
      testResult?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
          color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp))
      }
      Spacer(Modifier.height(8.dp))
      Button(onClick = { onSave(p) }, modifier = Modifier.fillMaxWidth()) { Text("Save & Activate") }
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

@Composable private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
  OutlinedTextField(value = if (value == 0) "" else value.toString(),
    onValueChange = { onChange(it.toIntOrNull() ?: 0) },
    label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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

@Composable private fun TopKSlider(label: String, value: Int?, onChange: (Int) -> Unit) {
  val v = value ?: 0
  Text("$label: ${if (v == 0) "off" else v.toString()}", style = MaterialTheme.typography.bodySmall)
  Slider(value = v.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..200f, steps = 199)
}

@Composable private fun HistorySlider(label: String, value: Int?, onChange: (Int) -> Unit) {
  // 0 = unlimited (last position)
  val idx = if (value == null || value == 0) HISTORY_STEPS.size - 1
            else HISTORY_STEPS.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (HISTORY_STEPS.size - 1)
  val sv = HISTORY_STEPS[idx]
  val display = if (sv == 0) "∞" else sv.toString()
  Text("$label: $display", style = MaterialTheme.typography.bodySmall)
  Slider(value = idx.toFloat(), onValueChange = { onChange(HISTORY_STEPS[it.toInt().coerceIn(0, HISTORY_STEPS.size - 1)]) },
    valueRange = 0f..(HISTORY_STEPS.size - 1).toFloat())
}

@Composable private fun StepSlider(label: String, value: Int?, steps: List<Int>, onChange: (Int) -> Unit) {
  val idx = if (value == null || value == 0) 0
            else steps.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (steps.size - 1)
  val sv = steps[idx]
  val display = if (sv == 0) "off" else if (sv >= 1000) "${sv / 1000}K" else sv.toString()
  Text("$label: $display", style = MaterialTheme.typography.bodySmall)
  Slider(value = idx.toFloat(), onValueChange = { onChange(steps[it.toInt().coerceIn(0, steps.size - 1)]) },
    valueRange = 0f..(steps.size - 1).toFloat())
}


private suspend fun testConnection(p: ProviderProfile): String = withContext(Dispatchers.IO) {
  try {
    var baseUrl = p.effectiveApiBase().trimEnd('/')
    val endpointOverride = p.endpointOverride.trim().removePrefix("/")
    val chatPath = if (endpointOverride.isNotBlank()) endpointOverride
      else if (baseUrl.endsWith("/openai")) "chat/completions"
      else if (baseUrl.endsWith("/v1")) { baseUrl = baseUrl.removeSuffix("/v1"); "v1/chat/completions" }
      else "v1/chat/completions"
    val url = "$baseUrl/$chatPath"
    val model = p.effectiveModel()
    if (model.isBlank()) return@withContext "✗ No model selected"

    // Build test message based on active abilities
    val messages = org.json.JSONArray()
    messages.put(org.json.JSONObject().put("role", "user").put("content", "Reply with exactly: OK"))

    val body = org.json.JSONObject().apply {
      put("model", model)
      put("messages", messages)
      put("max_tokens", 10)
      p.effectiveTemperature()?.let { put("temperature", it.toDouble()) }
    }

    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    if (p.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
    conn.connectTimeout = 15_000; conn.readTimeout = 30_000
    conn.doOutput = true
    conn.outputStream.write(body.toString().toByteArray())

    val code = conn.responseCode
    if (code !in 200..299) {
      val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
      return@withContext "✗ HTTP $code: ${err ?: "Unknown error"}"
    }

    val resp = conn.inputStream.bufferedReader().readText()
    val json = org.json.JSONObject(resp)
    val content = json.optJSONArray("choices")?.optJSONObject(0)
      ?.optJSONObject("message")?.optString("content", "") ?: ""
    val usage = json.optJSONObject("usage")
    val tokens = usage?.optInt("total_tokens", 0) ?: 0

    val results = mutableListOf("✓ Chat: OK ($tokens tok)")

    // Test tools if enabled
    if (p.toolsOverride != false) {
      try {
        val toolMsg = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "What is 2+2? Use the calculator tool."))
        val toolDef = org.json.JSONArray().put(org.json.JSONObject().apply {
          put("type", "function")
          put("function", org.json.JSONObject().apply {
            put("name", "calculator")
            put("description", "Calculate math")
            put("parameters", org.json.JSONObject().put("type", "object")
              .put("properties", org.json.JSONObject().put("expr", org.json.JSONObject().put("type", "string")))
              .put("required", org.json.JSONArray().put("expr")))
          })
        })
        val toolBody = org.json.JSONObject().apply {
          put("model", model); put("messages", toolMsg); put("tools", toolDef); put("max_tokens", 50)
        }
        val tc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        tc.requestMethod = "POST"
        tc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) tc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        tc.connectTimeout = 15_000; tc.readTimeout = 30_000; tc.doOutput = true
        tc.outputStream.write(toolBody.toString().toByteArray())
        if (tc.responseCode in 200..299) {
          val tr = org.json.JSONObject(tc.inputStream.bufferedReader().readText())
          val toolCalls = tr.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONArray("tool_calls")
          results.add(if (toolCalls != null && toolCalls.length() > 0) "✓ Tools: supported" else "⚠ Tools: no tool_calls in response")
        } else results.add("✗ Tools: HTTP ${tc.responseCode}")
      } catch (e: Exception) { results.add("✗ Tools: ${e.message?.take(60)}") }
    }

    // Test vision if enabled
    if (p.visionOverride == true) {
      try {
        val visMsg = org.json.JSONArray().put(org.json.JSONObject().apply {
          put("role", "user")
          put("content", org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "text").put("text", "Describe this image in one word."))
            .put(org.json.JSONObject().put("type", "image_url").put("image_url",
              org.json.JSONObject().put("url", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="))))
        })
        val visBody = org.json.JSONObject().apply { put("model", model); put("messages", visMsg); put("max_tokens", 10) }
        val vc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        vc.requestMethod = "POST"; vc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) vc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        vc.connectTimeout = 15_000; vc.readTimeout = 30_000; vc.doOutput = true
        vc.outputStream.write(visBody.toString().toByteArray())
        results.add(if (vc.responseCode in 200..299) "✓ Vision: supported" else "✗ Vision: HTTP ${vc.responseCode}")
      } catch (e: Exception) { results.add("✗ Vision: ${e.message?.take(60)}") }
    }

    // Test audio if enabled (TTS endpoint)
    if (p.audioOverride == true) {
      try {
        val audioUrl = "$baseUrl/v1/audio/speech"
        val audioBody = org.json.JSONObject().apply {
          put("model", "tts-1"); put("input", "test"); put("voice", "alloy")
        }
        val ac = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        ac.requestMethod = "POST"; ac.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) ac.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        ac.connectTimeout = 10_000; ac.readTimeout = 15_000; ac.doOutput = true
        ac.outputStream.write(audioBody.toString().toByteArray())
        results.add(if (ac.responseCode in 200..299) "✓ Audio: supported" else "⚠ Audio: HTTP ${ac.responseCode}")
      } catch (e: Exception) { results.add("⚠ Audio: ${e.message?.take(60)}") }
    }

    // Test video if enabled (check if model accepts video URL in content)
    if (p.videoOverride == true) {
      try {
        val vidMsg = org.json.JSONArray().put(org.json.JSONObject().apply {
          put("role", "user")
          put("content", org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "text").put("text", "Reply OK."))
            .put(org.json.JSONObject().put("type", "video_url").put("video_url",
              org.json.JSONObject().put("url", "https://example.com/test.mp4"))))
        })
        val vidBody = org.json.JSONObject().apply { put("model", model); put("messages", vidMsg); put("max_tokens", 10) }
        val vdc = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        vdc.requestMethod = "POST"; vdc.setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) vdc.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        vdc.connectTimeout = 15_000; vdc.readTimeout = 30_000; vdc.doOutput = true
        vdc.outputStream.write(vidBody.toString().toByteArray())
        results.add(if (vdc.responseCode in 200..299) "✓ Video: supported" else "⚠ Video: HTTP ${vdc.responseCode}")
      } catch (e: Exception) { results.add("⚠ Video: ${e.message?.take(60)}") }
    }

    results.joinToString("\n")
  } catch (e: Exception) { "✗ ${e.message?.take(100)}" }
}
private suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelDef> = withContext(Dispatchers.IO) {
  try {
    // Normalize: ensure we hit the /models endpoint correctly
    var base = baseUrl.trimEnd('/')
    // For URLs ending in /v1, /models is at /v1/models
    // For URLs ending in /openai, /models is at /openai/models
    // For bare URLs, try /v1/models
    val url = when {
      base.endsWith("/v1") || base.endsWith("/openai") -> "$base/models"
      else -> "$base/v1/models"
    }
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 15_000; conn.readTimeout = 15_000
    val body = conn.inputStream.bufferedReader().readText()
    val json = org.json.JSONObject(body)
    val data = json.optJSONArray("data") ?: return@withContext emptyList()
    (0 until data.length()).map { val o = data.getJSONObject(it)
      val id = o.getString("id")
      val name = o.optString("display_name", "").ifBlank { o.optString("name", id) }
      ModelDef(id, name, o.optInt("context_window"))
    }.sortedBy { it.id }
  } catch (e: Exception) {
    android.util.Log.e("FetchModels", "Failed: ${e.message}")
    emptyList()
  }
}
