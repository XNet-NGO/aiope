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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(providerStore: ProviderStore, onBack: () -> Unit) {
  var screen by remember { mutableStateOf<String>("list") } // list, pick_template, edit
  var editingProfile by remember { mutableStateOf<ProviderProfile?>(null) }
  var profiles by remember { mutableStateOf(providerStore.getProfiles()) }
  var activeId by remember { mutableStateOf(providerStore.getActiveId()) }

  fun refresh() { profiles = providerStore.getProfiles(); activeId = providerStore.getActiveId() }

  when (screen) {
    "list" -> ProviderListScreen(
      profiles = profiles, activeId = activeId,
      onSelect = { providerStore.setActiveId(it.id); activeId = it.id },
      onEdit = { editingProfile = it; screen = "edit" },
      onAdd = { screen = "pick_template" },
      onBack = onBack
    )
    "pick_template" -> TemplatePickerScreen(
      onPick = { template ->
        val profile = template.copy(id = java.util.UUID.randomUUID().toString())
        editingProfile = profile; screen = "edit"
      },
      onCustom = {
        editingProfile = ProviderProfile(); screen = "edit"
      },
      onBack = { screen = "list" }
    )
    "edit" -> editingProfile?.let { profile ->
      ProfileEditorScreen(
        profile = profile,
        providerStore = providerStore,
        onSave = { saved ->
          providerStore.updateProfile(saved)
          providerStore.setActiveId(saved.id)
          refresh(); screen = "list"
        },
        onDelete = if (profiles.any { it.id == profile.id }) {{ providerStore.deleteProfile(profile.id); refresh(); screen = "list" }} else null,
        onBack = { screen = "list" }
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProviderListScreen(
  profiles: List<ProviderProfile>, activeId: String,
  onSelect: (ProviderProfile) -> Unit, onEdit: (ProviderProfile) -> Unit,
  onAdd: () -> Unit, onBack: () -> Unit
) {
  Scaffold(topBar = {
    TopAppBar(title = { Text("Providers") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") } })
  }) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
      items(profiles) { profile ->
        ListItem(
          headlineContent = { Text(profile.name) },
          supportingContent = { Text("${profile.effectiveModel()} · ${profile.baseUrl.take(40)}", style = MaterialTheme.typography.bodySmall) },
          trailingContent = { if (profile.id == activeId) Text("✓", color = MaterialTheme.colorScheme.primary) },
          modifier = Modifier.clickable { onSelect(profile) }.combinedClickable(onLongClick = { onEdit(profile) }, onClick = { onSelect(profile) })
        )
      }
      if (profiles.isEmpty()) {
        item { Text("No providers configured. Tap + to add one.", modifier = Modifier.padding(16.dp)) }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePickerScreen(onPick: (ProviderProfile) -> Unit, onCustom: () -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = {
    TopAppBar(title = { Text("Add Provider") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
  }) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
      item { Text("From template", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp)) }
      items(ProviderTemplates.templates) { t ->
        ListItem(
          headlineContent = { Text(t.name) },
          supportingContent = { Text(t.baseUrl, style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onPick(t) }
        )
      }
      item {
        ListItem(
          headlineContent = { Text("Custom Provider") },
          supportingContent = { Text("Configure from scratch", style = MaterialTheme.typography.bodySmall) },
          modifier = Modifier.clickable { onCustom() }
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorScreen(
  profile: ProviderProfile, providerStore: ProviderStore,
  onSave: (ProviderProfile) -> Unit, onDelete: (() -> Unit)?, onBack: () -> Unit
) {
  var p by remember { mutableStateOf(profile) }
  val scope = rememberCoroutineScope()
  var loadingModels by remember { mutableStateOf(false) }

  Scaffold(topBar = {
    TopAppBar(title = { Text(if (p.name.isBlank()) "New Provider" else p.name) },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = { onDelete?.let { IconButton(onClick = it) { Icon(Icons.Default.Delete, "Delete") } } })
  }) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {

      // ── Profile Name ──
      OutlinedTextField(value = p.name, onValueChange = { p = p.copy(name = it) },
        label = { Text("Profile Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Spacer(Modifier.height(12.dp))

      // ── Connection ──
      Text("Connection", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      OutlinedTextField(value = p.baseUrl, onValueChange = { p = p.copy(baseUrl = it) },
        label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("https://api.openai.com/v1") })
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = p.endpointOverride, onValueChange = { p = p.copy(endpointOverride = it) },
        label = { Text("Endpoint Override (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("/chat/completions") })
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = p.apiKey, onValueChange = { p = p.copy(apiKey = it) },
        label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Spacer(Modifier.height(12.dp))

      // ── Model ──
      Text("Model", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      Row(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
          if (p.baseUrl.isNotBlank()) {
            loadingModels = true
            scope.launch {
              val models = loadModels(p.baseUrl, p.apiKey)
              p = p.copy(availableModels = models)
              loadingModels = false
            }
          }
        }, enabled = !loadingModels) {
          Text(if (loadingModels) "Loading…" else "Load Models")
        }
      }
      if (p.availableModels.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        p.availableModels.take(20).forEach { model ->
          Row(modifier = Modifier.fillMaxWidth().clickable { p = p.copy(selectedModel = model) }.padding(8.dp, 4.dp)) {
            Text(if (model == p.selectedModel) "● " else "○ ", color = MaterialTheme.colorScheme.primary)
            Text(model, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = p.customModel, onValueChange = { p = p.copy(customModel = it) },
        label = { Text("Custom Model (overrides selection)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Spacer(Modifier.height(12.dp))

      // ── Abilities ──
      Text("Abilities", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      LabeledSwitch("Auto-detect", p.autoDetectAbilities) { p = p.copy(autoDetectAbilities = it) }
      if (!p.autoDetectAbilities) {
        LabeledSwitch("Vision", p.supportsVision) { p = p.copy(supportsVision = it) }
        LabeledSwitch("Audio", p.supportsAudio) { p = p.copy(supportsAudio = it) }
        LabeledSwitch("Video", p.supportsVideo) { p = p.copy(supportsVideo = it) }
        LabeledSwitch("Tool Calling", p.supportsTools) { p = p.copy(supportsTools = it) }
      }
      Spacer(Modifier.height(12.dp))

      // ── Parameters ──
      Text("Parameters", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      SliderField("Temperature", p.temperature, 0f, 2f) { p = p.copy(temperature = it) }
      SliderField("Top-P", p.topP, 0f, 1f) { p = p.copy(topP = it) }
      OutlinedTextField(value = p.topK.toString(), onValueChange = { p = p.copy(topK = it.toIntOrNull() ?: 0) },
        label = { Text("Top-K (0 = disabled)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = p.maxTokens.toString(), onValueChange = { p = p.copy(maxTokens = it.toIntOrNull() ?: 4096) },
        label = { Text("Max Tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Spacer(Modifier.height(12.dp))

      // ── Context ──
      Text("Context", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      OutlinedTextField(value = p.contextLength.toString(), onValueChange = { p = p.copy(contextLength = it.toIntOrNull() ?: 10) },
        label = { Text("History Messages") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = p.systemPrompt, onValueChange = { p = p.copy(systemPrompt = it) },
        label = { Text("System Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
      Spacer(Modifier.height(16.dp))

      Button(onClick = { onSave(p) }, modifier = Modifier.fillMaxWidth()) { Text("Save & Activate") }
      Spacer(Modifier.height(32.dp))
    }
  }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, modifier = Modifier.alignByBaseline())
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun SliderField(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
  Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
  Slider(value = value, onValueChange = onChange, valueRange = min..max)
}

private suspend fun loadModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
  try {
    val url = "${baseUrl.trimEnd('/')}/models"
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
    val body = conn.inputStream.bufferedReader().readText()
    val json = org.json.JSONObject(body)
    val data = json.optJSONArray("data") ?: return@withContext emptyList()
    (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
  } catch (e: Exception) { emptyList() }
}
