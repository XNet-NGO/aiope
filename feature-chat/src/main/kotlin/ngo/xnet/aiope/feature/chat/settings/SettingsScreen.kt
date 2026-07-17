package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.theme.ChatBackground
import ngo.xnet.aiope.feature.chat.theme.LocalThemeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao, onBack: () -> Unit, serversContent: (@Composable (onBack: () -> Unit) -> Unit)? = null) {
  val theme = LocalThemeState.current
  var screen by remember { mutableStateOf("list") }
  var editId by remember { mutableStateOf<String?>(null) }
  var profiles by remember { mutableStateOf(providerStore.getAll()) }
  var activeId by remember { mutableStateOf(providerStore.getActive().id) }
  fun refresh() {
    profiles = providerStore.getAll()
    activeId = providerStore.getActive().id
  }

  Box(Modifier.fillMaxSize()) {
    ChatBackground(theme)
    Box(Modifier.fillMaxSize().alpha(theme.uiOpacity)) {
      when (screen) {
        "list" -> ProfileList(
          providerStore, chatDao,
          onAgent = { screen = "agent" }, onTasks = { screen = "tasks" }, onTools = { screen = "tools" }, onMcp = { screen = "mcp" }, onServers = { screen = "servers" }, onVoice = { screen = "voice" }, onTheme = { screen = "theme" }, onProviders = { screen = "providers" }, onRag = { screen = "rag" }, onBack = onBack,
        )

        "voice" -> VoiceSettingsScreen(onBack = { screen = "list" })

        "theme" -> ngo.xnet.aiope.feature.chat.theme.ThemeSettingsScreen(onBack = { screen = "list" })

        "tools" -> ToolToggleScreen(toolStore, onBack = { screen = "list" })

        "agent" -> AgentScreen(dao = chatDao, onBack = { screen = "list" })

        "rag" -> RagScreen(onBack = { screen = "list" })

        "mcp" -> McpServerScreen(toolStore, onBack = { screen = "list" })

        "servers" -> serversContent?.invoke { screen = "list" }

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

        "providers" -> ProviderListScreen(
          profiles,
          activeId,
          providerStore,
          onSelect = {
            providerStore.setActive(it.id)
            activeId = it.id
          },
          onEdit = {
            editId = it.id
            screen = "edit"
          },
          onAdd = { screen = "pick" },
          onBack = { screen = "list" },
        )
      }
    }
  }
}
