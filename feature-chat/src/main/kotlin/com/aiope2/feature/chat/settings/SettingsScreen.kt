package com.aiope2.feature.chat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiope2.core.network.LlmProvider
import com.aiope2.core.network.ProviderDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  providerStore: ProviderStore,
  onBack: () -> Unit
) {
  var activeProvider by remember { mutableStateOf(providerStore.getActiveProvider()) }
  var editingProvider by remember { mutableStateOf<LlmProvider?>(null) }

  if (editingProvider != null) {
    ProviderEditor(
      provider = editingProvider!!,
      providerStore = providerStore,
      onSave = { updated ->
        providerStore.setActiveProvider(updated)
        if (updated.apiKey.isNotBlank()) providerStore.setApiKey(updated.name, updated.apiKey)
        if (updated.defaultModel.isNotBlank()) providerStore.setModel(updated.name, updated.defaultModel)
        activeProvider = updated
        editingProvider = null
      },
      onBack = { editingProvider = null }
    )
    return
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        }
      )
    }
  ) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
      item {
        Text("LLM Provider", style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
      }
      items(ProviderDefaults.providers) { provider ->
        val isActive = provider.name == activeProvider.name
        ListItem(
          headlineContent = { Text(provider.name) },
          supportingContent = { Text(provider.defaultModel, style = MaterialTheme.typography.bodySmall) },
          trailingContent = {
            if (isActive) Text("✓", color = MaterialTheme.colorScheme.primary)
          },
          modifier = Modifier.clickable {
            val key = providerStore.getApiKey(provider.name)
            val model = providerStore.getModel(provider.name)
            val p = provider.copy(
              apiKey = key.ifBlank { provider.apiKey },
              defaultModel = model.ifBlank { provider.defaultModel }
            )
            providerStore.setActiveProvider(p)
            activeProvider = p
          }
        )
      }
      item {
        TextButton(
          onClick = { editingProvider = activeProvider },
          modifier = Modifier.padding(16.dp)
        ) { Text("Edit API Key / Model") }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(
  provider: LlmProvider,
  providerStore: ProviderStore,
  onSave: (LlmProvider) -> Unit,
  onBack: () -> Unit
) {
  var apiKey by remember { mutableStateOf(providerStore.getApiKey(provider.name).ifBlank { provider.apiKey }) }
  var model by remember { mutableStateOf(providerStore.getModel(provider.name).ifBlank { provider.defaultModel }) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(provider.name) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        }
      )
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
      OutlinedTextField(
        value = apiKey, onValueChange = { apiKey = it },
        label = { Text("API Key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
      )
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = model, onValueChange = { model = it },
        label = { Text("Model") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
      )
      Spacer(Modifier.height(16.dp))
      Button(onClick = {
        onSave(provider.copy(apiKey = apiKey, defaultModel = model))
      }) { Text("Save") }
    }
  }
}
