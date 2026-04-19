package com.aiope2.feature.chat.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderListScreen(
  profiles: List<ProviderProfile>,
  activeId: String,
  providerStore: ProviderStore,
  onSelect: (ProviderProfile) -> Unit,
  onEdit: (ProviderProfile) -> Unit,
  onAdd: () -> Unit,
  onBack: () -> Unit,
) {
  val _bgActive = com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground
  Scaffold(
    containerColor = if (_bgActive) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = if (com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.surface),
        title = { Text("Providers") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") } },
      )
    },
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      items(profiles) { p ->
        val builtin = ProviderTemplates.byId[p.builtinId]
        ListItem(
          headlineContent = { Text(p.label.ifBlank { builtin?.displayName ?: "Custom" }) },
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
