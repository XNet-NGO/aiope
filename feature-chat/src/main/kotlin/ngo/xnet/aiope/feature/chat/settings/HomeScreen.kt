package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.fileserver.FileServerScreen
import ngo.xnet.aiope.feature.chat.scanner.ScannerScreen
import ngo.xnet.aiope.feature.chat.theme.ChatBackground
import ngo.xnet.aiope.feature.chat.theme.LocalThemeState
import ngo.xnet.aiope.feature.chat.theme.ThemeSettingsScreen
import ngo.xnet.aiope.feature.chat.ui.CuORadius
import ngo.xnet.aiope.feature.chat.ui.SettingsGroup
import ngo.xnet.aiope.feature.chat.ui.SettingsRow
import ngo.xnet.aiope.feature.chat.ui.SettingsRowDivider

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

/**
 * Home's primary shortcuts. Twelve equal cards meant nothing was primary, so the grid is cut to the
 * four things a harness user reaches for mid-task; everything else lives one tap deeper in Settings,
 * which is now grouped and scannable.
 */
private val quickCards = listOf(
  FeatureCard("Agents", "Roster & pipelines", Icons.Default.SmartToy, CuoDestination.Agent),
  FeatureCard("Tools", "62 available", Icons.Default.Build, CuoDestination.Tools),
  FeatureCard("Knowledge", "Indexed documents", Icons.AutoMirrored.Filled.MenuBook, CuoDestination.Rag),
  FeatureCard("Servers", "SSH & MCP hosts", Icons.Default.Dns, CuoDestination.Servers),
)

@Composable
private fun HomeDashboard(providerStore: ProviderStore, activeId: String, onNewChat: () -> Unit, open: (CuoDestination) -> Unit) {
  val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
  val greeting = when {
    hour < 12 -> "Good morning"
    hour < 18 -> "Good afternoon"
    else -> "Good evening"
  }
  val active = remember(activeId) { providerStore.getById(activeId) ?: providerStore.getActive() }
  val modelDisplay = remember(active.id) {
    // Raw slugs like "openrouter/openrouter-free" expose an internal id and read like a bug when the
    // vendor segment repeats. The provider's cached displayName is often the same slug, so humanise
    // whichever string we end up with rather than trusting the cache to be presentable.
    val raw = providerStore.getModelCacheStale(active.id)?.firstOrNull { it.id == active.selectedModelId }?.displayName
      ?.takeIf { it.isNotBlank() }
      ?: active.selectedModelId
    raw.substringAfterLast('/')
      .replace('-', ' ')
      .replace('_', ' ')
      .split(' ')
      .filter { it.isNotBlank() }
      .distinct()
      .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
      .ifBlank { "No model" }
  }
  val hasModel = active.selectedModelId.isNotBlank()
  val cs = MaterialTheme.colorScheme

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp),
  ) {
    Spacer(Modifier.height(24.dp))
    // The old letter-spaced "CuO" over the greeting read as a corrupted string, and the greeting
    // itself was the loudest element while carrying no information. One line, product name first.
    Text("CuO Agentic", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
    Text(greeting, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))

    // Primary action stands alone: opening the app to start a chat shouldn't require reading a
    // metadata card first. Radius `xl` matches the composer capsule on the chat screen.
    Button(
      onClick = onNewChat,
      modifier = Modifier.fillMaxWidth().height(54.dp),
      shape = RoundedCornerShape(CuORadius.xl),
    ) {
      Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text("New chat", style = MaterialTheme.typography.titleMedium)
    }

    Spacer(Modifier.height(8.dp))

    // Provider/model becomes a compact tappable status strip. The dot is labelled — an unlabelled
    // coloured dot is decoration, not status.
    Row(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(CuORadius.md))
        .background(cs.surfaceContainerHigh)
        .border(BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.5f)), RoundedCornerShape(CuORadius.md))
        .clickable { open(CuoDestination.Providers) }
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(if (hasModel) cs.tertiary else cs.error),
      )
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(
          if (hasModel) "Connected · ${active.label.ifBlank { "provider" }}" else "No model selected",
          style = MaterialTheme.typography.titleMedium,
          color = cs.onSurface,
          maxLines = 1,
        )
        Text(
          modelDisplay,
          style = MaterialTheme.typography.labelSmall,
          color = cs.onSurfaceVariant.copy(alpha = 0.92f),
          maxLines = 1,
        )
      }
      Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = cs.onSurfaceVariant.copy(alpha = 0.9f),
        modifier = Modifier.size(20.dp),
      )
    }

    Spacer(Modifier.height(24.dp))
    Text(
      "SHORTCUTS",
      style = MaterialTheme.typography.labelMedium,
      color = cs.onSurfaceVariant.copy(alpha = 0.95f),
      modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
    )
    // Fixed 2-up grid instead of an adaptive one: four items always land as 2x2, so the layout
    // doesn't reflow into a lonely orphan row on wider screens.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      HomeQuickCard(quickCards[0], Modifier.weight(1f)) { open(quickCards[0].destination) }
      HomeQuickCard(quickCards[1], Modifier.weight(1f)) { open(quickCards[1].destination) }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      HomeQuickCard(quickCards[2], Modifier.weight(1f)) { open(quickCards[2].destination) }
      HomeQuickCard(quickCards[3], Modifier.weight(1f)) { open(quickCards[3].destination) }
    }

    Spacer(Modifier.height(22.dp))
    // Everything else: one row to the grouped settings screen, plus the two device utilities that
    // have no other home.
    Text(
      "UTILITIES",
      style = MaterialTheme.typography.labelMedium,
      color = cs.onSurfaceVariant.copy(alpha = 0.95f),
      modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
    )
    SettingsGroup {
      SettingsRow("All settings", "Providers, agent, tools, theme, backup", Icons.Default.Settings, onClick = { open(CuoDestination.List) })
      SettingsRowDivider()
      SettingsRow("File server", "Share files over HTTP(S)", Icons.Default.Folder, onClick = { open(CuoDestination.FileServer) })
      SettingsRowDivider()
      SettingsRow("Network scanner", "Find devices on this LAN", Icons.Default.NetworkCheck, onClick = { open(CuoDestination.Scanner) })
    }
    Spacer(Modifier.height(28.dp))
  }
}

/**
 * One quick-access tile. Square-ish, icon over label, no subtitle competition — at this size the
 * subtitle is a single quiet line so the title stays the read.
 *
 * Radius: tile `md` (16dp) with 12dp padding → the icon tile inside is `md − 12 = 4dp`, which is too
 * square to read as a rounded chip, so it's optically bumped to `xs` (8dp).
 */
@Composable
private fun HomeQuickCard(card: FeatureCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier
      .clip(RoundedCornerShape(CuORadius.md))
      .background(cs.surfaceContainerHigh)
      .border(BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.5f)), RoundedCornerShape(CuORadius.md))
      .clickable(onClick = onClick)
      .height(104.dp)
      .padding(12.dp),
  ) {
    Box(
      Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(CuORadius.xs))
        .background(cs.primary.copy(alpha = 0.14f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(card.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.weight(1f))
    Text(card.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
    Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant.copy(alpha = 0.92f), maxLines = 1)
  }
}
