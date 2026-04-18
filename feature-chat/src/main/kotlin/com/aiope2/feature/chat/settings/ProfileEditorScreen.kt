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

internal val TOKEN_STEPS = listOf(0, 256, 512, 1024, 2048, 4096, 8192, 16000, 32000, 64000, 128000, 200000, 500000, 1000000)
internal val HISTORY_STEPS = listOf(1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000, 200000, 256000, 384000, 500000, 750000, 1000000, 2000000, 5000000, 10000000)
internal val ENDPOINT_PRESETS = listOf("/chat/completions", "/completions", "/responses", "/embeddings", "/rerank", "/audio/speech", "/audio/transcriptions", "/images/generations", "/moderations")

@Composable
internal fun ProfileEditor(
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
