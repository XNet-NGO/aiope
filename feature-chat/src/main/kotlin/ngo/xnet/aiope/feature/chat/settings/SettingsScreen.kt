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
  var screen by remember { mutableStateOf<CuoDestination>(CuoDestination.List) }
  var profiles by remember { mutableStateOf(providerStore.getAll()) }
  var activeId by remember { mutableStateOf(providerStore.getActive().id) }
  fun refresh() {
    profiles = providerStore.getAll()
    activeId = providerStore.getActive().id
  }

  Box(Modifier.fillMaxSize()) {
    ChatBackground(theme)
    Box(Modifier.fillMaxSize().alpha(theme.uiOpacity)) {
      when (val dest = screen) {
        CuoDestination.List -> ProfileList(
          providerStore, chatDao,
          onAgent = { screen = CuoDestination.Agent }, onTasks = { screen = CuoDestination.Tasks }, onTools = { screen = CuoDestination.Tools }, onMcp = { screen = CuoDestination.Mcp }, onServers = { screen = CuoDestination.Servers }, onVoice = { screen = CuoDestination.Voice }, onTheme = { screen = CuoDestination.Theme }, onProviders = { screen = CuoDestination.Providers }, onRag = { screen = CuoDestination.Rag }, onBack = onBack,
        )

        CuoDestination.Voice -> VoiceSettingsScreen(onBack = { screen = CuoDestination.List })

        CuoDestination.Theme -> ngo.xnet.aiope.feature.chat.theme.ThemeSettingsScreen(onBack = { screen = CuoDestination.List })

        CuoDestination.Tools -> ToolToggleScreen(toolStore, onBack = { screen = CuoDestination.List })

        CuoDestination.Agent -> AgentScreen(dao = chatDao, onBack = { screen = CuoDestination.List })

        CuoDestination.Rag -> RagScreen(onBack = { screen = CuoDestination.List })

        CuoDestination.Mcp -> McpServerScreen(toolStore, onBack = { screen = CuoDestination.List })

        CuoDestination.Servers -> serversContent?.invoke { screen = CuoDestination.List }

        CuoDestination.Pick -> TemplatePicker(onPick = { b ->
          val p = ProviderProfile(builtinId = b.id, label = b.displayName, apiBase = b.apiBase ?: "", selectedModelId = b.defaultModels.firstOrNull()?.id ?: "")
          providerStore.save(p)
          providerStore.setActive(p.id)
          // Copy model cache from sibling provider with same template
          val sibling = profiles.firstOrNull { it.builtinId == b.id }
          if (sibling != null) {
            val cache = providerStore.getModelCacheStale(sibling.id)
            if (!cache.isNullOrEmpty()) providerStore.saveModelCache(p.id, cache)
          }
          refresh()
          screen = CuoDestination.Edit(p.id)
        }, onBack = { screen = CuoDestination.List })

        is CuoDestination.Edit -> providerStore.getById(dest.profileId)?.let { profile ->
          ProfileEditor(
            profile,
            providerStore,
            onSave = {
              providerStore.save(it)
              providerStore.setActive(it.id)
              refresh()
              screen = CuoDestination.List
            },
            onDelete = {
              providerStore.delete(profile.id)
              refresh()
              screen = CuoDestination.List
            },
            onBack = { screen = CuoDestination.List },
          )
        }

        CuoDestination.Tasks -> TaskModelScreen(providerStore, onBack = { screen = CuoDestination.List })

        CuoDestination.Providers -> ProviderListScreen(
          profiles,
          activeId,
          providerStore,
          onSelect = {
            providerStore.setActive(it.id)
            activeId = it.id
          },
          onEdit = {
            screen = CuoDestination.Edit(it.id)
          },
          onAdd = { screen = CuoDestination.Pick },
          onBack = { screen = CuoDestination.List },
        )

        // Not part of the settings tree: Home root and Home-only screens.
        CuoDestination.Home, CuoDestination.Scanner, CuoDestination.FileServer -> {}
      }
    }
  }
}
