package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.fileserver.FileServerScreen
import ngo.xnet.aiope.feature.chat.scanner.ScannerScreen
import ngo.xnet.aiope.feature.chat.theme.ChatBackground
import ngo.xnet.aiope.feature.chat.theme.LocalThemeState
import ngo.xnet.aiope.feature.chat.theme.ThemeSettingsScreen

/**
 * CuO Home — the app dashboard. Greets the user, shows the active provider/model chip, offers the
 * primary "New Chat" action and a grid of feature cards. Each card opens an existing screen via a
 * typed [CuoDestination] router with its own back stack (subscreens opened from the settings menu
 * return to the menu, everything else returns to Home, matching the previous behavior).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao, serversContent: (@Composable (onBack: () -> Unit) -> Unit)? = null, onNewChat: () -> Unit = {}) {
  val theme = LocalThemeState.current
  var screen by remember { mutableStateOf<CuoDestination>(CuoDestination.Home) }
  val backStack = remember { mutableStateListOf<CuoDestination>() }
  fun open(dest: CuoDestination) {
    backStack.add(screen)
    screen = dest
  }
  fun back() {
    screen = if (backStack.isEmpty()) CuoDestination.Home else backStack.removeAt(backStack.lastIndex)
  }
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
        CuoDestination.Home -> HomeDashboard(
          providerStore = providerStore,
          activeId = activeId,
          onNewChat = onNewChat,
          open = ::open,
        )

        CuoDestination.List -> ProfileList(
          providerStore, chatDao,
          onAgent = { open(CuoDestination.Agent) }, onTasks = { open(CuoDestination.Tasks) }, onTools = { open(CuoDestination.Tools) }, onMcp = { open(CuoDestination.Mcp) }, onServers = { open(CuoDestination.Servers) }, onVoice = { open(CuoDestination.Voice) }, onTheme = { open(CuoDestination.Theme) }, onProviders = { open(CuoDestination.Providers) }, onRag = { open(CuoDestination.Rag) }, onBack = ::back,
        )

        CuoDestination.Voice -> VoiceSettingsScreen(onBack = ::back)

        CuoDestination.Theme -> ThemeSettingsScreen(onBack = ::back)

        CuoDestination.Tools -> ToolToggleScreen(toolStore, onBack = ::back)

        CuoDestination.Agent -> AgentScreen(dao = chatDao, onBack = ::back)

        CuoDestination.Rag -> RagScreen(onBack = ::back)

        CuoDestination.Mcp -> McpServerScreen(toolStore, onBack = ::back)

        CuoDestination.Servers -> serversContent?.invoke(::back)

        CuoDestination.Scanner -> ScannerScreen(onBack = ::back)

        CuoDestination.FileServer -> FileServerScreen(onBack = ::back)

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
          open(CuoDestination.Edit(p.id))
        }, onBack = ::back)

        is CuoDestination.Edit -> providerStore.getById(dest.profileId)?.let { profile ->
          ProfileEditor(
            profile,
            providerStore,
            onSave = {
              providerStore.save(it)
              providerStore.setActive(it.id)
              refresh()
              back()
            },
            onDelete = {
              providerStore.delete(profile.id)
              refresh()
              back()
            },
            onBack = ::back,
          )
        }

        CuoDestination.Tasks -> TaskModelScreen(providerStore, onBack = ::back)

        CuoDestination.Providers -> ProviderListScreen(
          profiles,
          activeId,
          providerStore,
          onSelect = {
            providerStore.setActive(it.id)
            activeId = it.id
          },
          onEdit = {
            open(CuoDestination.Edit(it.id))
          },
          onAdd = { open(CuoDestination.Pick) },
          onBack = ::back,
        )
      }
    }
  }
}

private data class FeatureCard(val title: String, val subtitle: String, val icon: ImageVector, val destination: CuoDestination)

private val featureCards = listOf(
  FeatureCard("Agents", "Builtin & custom agents", Icons.Default.SmartToy, CuoDestination.Agent),
  FeatureCard("Tools", "Enable or disable tools", Icons.Default.Build, CuoDestination.Tools),
  FeatureCard("MCP Servers", "External tool servers", Icons.Default.Extension, CuoDestination.Mcp),
  FeatureCard("Remote Servers", "SSH dev servers", Icons.Default.Dns, CuoDestination.Servers),
  FeatureCard("Knowledge", "RAG document library", Icons.AutoMirrored.Filled.MenuBook, CuoDestination.Rag),
  FeatureCard("Voice", "Speech & live calls", Icons.Default.Mic, CuoDestination.Voice),
  FeatureCard("Theme", "Colors & background", Icons.Default.Palette, CuoDestination.Theme),
  FeatureCard("Models per Task", "Task-specific defaults", Icons.Default.TaskAlt, CuoDestination.Tasks),
  FeatureCard("Scanner", "Scan LAN devices", Icons.Default.NetworkCheck, CuoDestination.Scanner),
  FeatureCard("File Server", "Share files over HTTP(S)", Icons.Default.Folder, CuoDestination.FileServer),
  FeatureCard("Providers", "API providers & models", Icons.Default.Cloud, CuoDestination.Providers),
  FeatureCard("Settings", "All settings in one list", Icons.Default.Settings, CuoDestination.List),
)

@Composable
private fun HomeDashboard(providerStore: ProviderStore, activeId: String, onNewChat: () -> Unit, open: (CuoDestination) -> Unit) {
  val theme = LocalThemeState.current
  val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
  val greeting = when {
    hour < 12 -> "Good morning"
    hour < 18 -> "Good afternoon"
    else -> "Good evening"
  }
  val active = remember(activeId) { providerStore.getById(activeId) ?: providerStore.getActive() }
  val modelDisplay = remember(active.id) {
    val fromCache = providerStore.getModelCacheStale(active.id)?.firstOrNull { it.id == active.selectedModelId }?.displayName
    fromCache?.takeIf { it.isNotBlank() } ?: active.selectedModelId.substringAfterLast('/').ifBlank { "no model" }
  }

  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
    Spacer(Modifier.height(28.dp))
    Text("CuO", fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text(greeting, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(8.dp)) {}
      Spacer(Modifier.width(8.dp))
      Text(
        "${active.label.ifBlank { "Provider" }}  ·  $modelDisplay",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
    Spacer(Modifier.height(20.dp))
    Button(
      onClick = onNewChat,
      modifier = Modifier.fillMaxWidth().height(56.dp),
      shape = RoundedCornerShape(16.dp),
    ) {
      Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text("New Chat", fontSize = 16.sp)
    }
    Spacer(Modifier.height(20.dp))
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 150.dp),
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(featureCards) { card ->
        HomeFeatureCard(card, useBackground = theme.useBackground) { open(card.destination) }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeFeatureCard(card: FeatureCard, useBackground: Boolean, onClick: () -> Unit) {
  val container = if (useBackground) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceContainer
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(116.dp),
    colors = CardDefaults.cardColors(containerColor = container),
  ) {
    Column(Modifier.padding(14.dp)) {
      Icon(card.icon, contentDescription = card.title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
      Spacer(Modifier.height(10.dp))
      Text(card.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
      Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
  }
}
