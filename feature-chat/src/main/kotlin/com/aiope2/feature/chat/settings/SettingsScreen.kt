package com.aiope2.feature.chat.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import com.aiope2.core.network.ProviderProfile
import com.aiope2.feature.chat.db.ChatDao

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao, onBack: () -> Unit) {
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
      onAdd = { screen = "pick" }, onAgent = { screen = "agent" }, onTasks = { screen = "tasks" }, onTools = { screen = "tools" }, onMcp = { screen = "mcp" }, onBack = onBack,
    )

    "tools" -> ToolToggleScreen(toolStore, onBack = { screen = "list" })

    "agent" -> AgentScreen(dao = chatDao, onBack = { screen = "list" })

    "mcp" -> McpServerScreen(toolStore, onBack = { screen = "list" })

    "pick" -> TemplatePicker(onPick = { b ->
      val p = ProviderProfile(builtinId = b.id, label = b.displayName, apiBase = b.apiBase ?: "", selectedModelId = b.defaultModels.firstOrNull()?.id ?: "")
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
