package ngo.xnet.aiope.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ngo.xnet.aiope.core.network.ModelDef
import ngo.xnet.aiope.feature.chat.db.ConversationEntity
import ngo.xnet.aiope.feature.chat.engine.AgentMode

/*
 * Chat shell chrome, arranged the way the mainstream assistant apps do it:
 *
 * - one **top bar** with three things only — drawer, model, overflow — instead of ten icons;
 * - everything session-scoped (conversations, destinations) lives in a **drawer**;
 * - everything panel-scoped (browser, terminal, agents, file server, scanner) lives in the
 *   **overflow menu**, so the bar doesn't grow an icon per feature;
 * - the **mode selector** sits directly above the composer, next to the text you're about to send.
 *
 * The original layout put all of those in a single 10-icon strip plus a hanging pill, which is why
 * nothing was findable. Chrome here uses [GlassSurface]; message content deliberately does not.
 */

/** Compact top bar: drawer + model picker + overflow. */
@Composable
fun ChatTopBar(
  modelLabel: String,
  onOpenDrawer: () -> Unit,
  onNewChat: () -> Unit,
  onGetModels: () -> List<ModelDef>,
  onGetActiveModelId: () -> String,
  onSwitchModel: (String) -> Unit,
  browserVisible: Boolean,
  terminalVisible: Boolean,
  agentPanelVisible: Boolean,
  autoRun: Boolean,
  onAutoRunChange: (Boolean) -> Unit,
  onToggleBrowser: () -> Unit,
  onToggleTerminal: () -> Unit,
  onToggleAgentPanel: () -> Unit,
  onFileServer: () -> Unit,
  onScanner: () -> Unit,
  onShareChat: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showModels by remember { mutableStateOf(false) }
  var showOverflow by remember { mutableStateOf(false) }
  val cs = MaterialTheme.colorScheme

  GlassSurface(
    modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    shape = RoundedCornerShape(CuORadius.xl),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Menu, "Open menu", Modifier.size(20.dp), tint = cs.onSurface)
      }

      // Model picker: the one thing worth a permanent slot, as in ChatGPT/Claude.
      Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Row(
          Modifier
            .clip(RoundedCornerShape(CuORadius.sm))
            .clickable { showModels = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            modelLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = cs.onSurface,
          )
          Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        }
        DropdownMenu(expanded = showModels, onDismissRequest = { showModels = false }) {
          val models = onGetModels()
          val active = onGetActiveModelId()
          if (models.isEmpty()) {
            DropdownMenuItem(text = { Text("No models — fetch in Settings", fontSize = 12.sp) }, onClick = { showModels = false })
          }
          models.forEach { m ->
            val selected = m.id == active
            DropdownMenuItem(
              text = {
                Text(
                  m.displayName,
                  fontSize = 13.sp,
                  fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                  color = if (selected) cs.primary else cs.onSurface,
                )
              },
              onClick = {
                onSwitchModel(m.id)
                showModels = false
              },
            )
          }
        }
      }

      IconButton(onClick = onNewChat, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Add, "New chat", Modifier.size(20.dp), tint = cs.onSurface)
      }

      Box {
        IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(40.dp)) {
          val anyPanelOpen = browserVisible || terminalVisible || agentPanelVisible
          Icon(
            Icons.Default.MoreVert,
            "More",
            Modifier.size(20.dp),
            tint = if (anyPanelOpen) cs.primary else cs.onSurface,
          )
        }
        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
          PanelItem("Browser", Icons.Default.Language, browserVisible) {
            onToggleBrowser()
            showOverflow = false
          }
          PanelItem("Terminal", Icons.Default.Terminal, terminalVisible) {
            onToggleTerminal()
            showOverflow = false
          }
          PanelItem("Agents", Icons.Default.SmartToy, agentPanelVisible) {
            onToggleAgentPanel()
            showOverflow = false
          }
          HorizontalDivider()
          PanelItem("File server", Icons.Default.Dns, false) {
            onFileServer()
            showOverflow = false
          }
          PanelItem("Network scanner", Icons.Default.NetworkCheck, false) {
            onScanner()
            showOverflow = false
          }
          PanelItem("Share chat", Icons.Default.Share, false) {
            onShareChat()
            showOverflow = false
          }
          HorizontalDivider()
          DropdownMenuItem(
            text = { Text("Auto-run tools", fontSize = 13.sp) },
            trailingIcon = {
              Switch(checked = autoRun, onCheckedChange = onAutoRunChange)
            },
            onClick = { onAutoRunChange(!autoRun) },
          )
          PanelItem("Settings", Icons.Default.Settings, false) {
            onOpenSettings()
            showOverflow = false
          }
        }
      }
    }
  }
}

@Composable
private fun PanelItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  DropdownMenuItem(
    leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = if (active) cs.primary else cs.onSurfaceVariant) },
    text = {
      Text(
        label,
        fontSize = 13.sp,
        color = if (active) cs.primary else cs.onSurface,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
      )
    },
    onClick = onClick,
  )
}

/**
 * Drawer contents: new chat, conversation history, then the app destinations.
 * This replaces the old bottom-sheet conversation list, which was reachable only from one small
 * toolbar icon.
 */
@Composable
fun ChatDrawerContent(
  conversations: List<ConversationEntity>,
  activeConversationId: String?,
  onNewChat: () -> Unit,
  onOpenConversation: (String) -> Unit,
  onDeleteConversation: (String) -> Unit,
  onOpenHome: () -> Unit,
  onOpenSettings: () -> Unit,
  onSearch: (String) -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
    Text(
      "CuO",
      fontSize = 18.sp,
      fontWeight = FontWeight.SemiBold,
      color = cs.primary,
      modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp),
    )
    NavigationDrawerItem(
      icon = { Icon(Icons.Default.Add, null, Modifier.size(20.dp)) },
      label = { Text("New chat") },
      selected = false,
      onClick = onNewChat,
      colors = NavigationDrawerItemDefaults.colors(),
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    var searchQuery by remember { mutableStateOf("") }
    OutlinedTextField(
      value = searchQuery,
      onValueChange = {
        searchQuery = it
        if (it.length >= 2) onSearch(it)
      },
      placeholder = { Text("Search all chats…", fontSize = 13.sp) },
      singleLine = true,
      shape = RoundedCornerShape(CuORadius.sm),
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = {
            searchQuery = ""
            onSearch("")
          }) {
            Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(16.dp))
          }
        }
      },
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
      "Recent",
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
    )
    if (conversations.isEmpty()) {
      Text(
        "No conversations yet.",
        fontSize = 13.sp,
        color = cs.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
      )
    }
    LazyColumn(Modifier.weight(1f, fill = false)) {
      items(conversations, key = { it.id }) { conv ->
        NavigationDrawerItem(
          label = { Text(conv.title.ifBlank { "New chat" }, maxLines = 1, fontSize = 14.sp) },
          selected = conv.id == activeConversationId,
          onClick = { onOpenConversation(conv.id) },
          badge = {
            IconButton(onClick = { onDeleteConversation(conv.id) }, modifier = Modifier.size(28.dp)) {
              Icon(Icons.Default.Delete, "Delete conversation", Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
          },
        )
      }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    NavigationDrawerItem(
      icon = { Icon(Icons.Default.Home, null, Modifier.size(20.dp)) },
      label = { Text("CuO Home") },
      selected = false,
      onClick = onOpenHome,
    )
    NavigationDrawerItem(
      icon = { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) },
      label = { Text("Settings") },
      selected = false,
      onClick = onOpenSettings,
    )
    Spacer(Modifier.height(16.dp))
  }
}

/** Chat / Plan / Build as a segmented control, sitting with the composer rather than the top bar. */
@Composable
fun ModeSelector(
  agentMode: AgentMode,
  onModeChange: (AgentMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  GlassSurface(modifier = modifier, shape = RoundedCornerShape(CuORadius.sm + 2.dp), tintAlpha = 0.55f) {
    Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      AgentMode.entries.forEach { mode ->
        val selected = mode == agentMode
        Text(
          mode.label,
          fontSize = 11.sp,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
          modifier = Modifier
            .clip(RoundedCornerShape(CuORadius.sm - 1.dp))
            .background(if (selected) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onModeChange(mode) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        )
      }
    }
  }
}

/** Greeting + tappable starter prompts, in place of the old bare "What can I help you with?". */
@Composable
fun ChatEmptyState(
  onSend: (String, List<String>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  val suggestions = listOf(
    "Summarise what this device is doing" to "Use device_info and list_schedules to tell me what this device is doing right now.",
    "Plan a multi-step task" to "Switch to Plan mode thinking: draft a plan for automating my daily reminders with schedule_task.",
    "Search and cite" to "Search the web for today's top AI news and give me three cited bullets.",
    "Work with my files" to "List the files in /sdcard/Download and tell me what's there.",
  )
  Column(
    modifier.fillMaxWidth().padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("CuO", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
    Text(
      "What can I help you with?",
      fontSize = 15.sp,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
    )
    suggestions.forEach { (title, prompt) ->
      GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(CuORadius.md),
        tintAlpha = 0.55f,
      ) {
        Text(
          title,
          fontSize = 13.sp,
          color = cs.onSurface,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onSend(prompt, emptyList()) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        )
      }
    }
  }
}

/** Vertical scroll rail for long threads (top / middle / bottom). */
@Composable
fun ScrollRail(
  onTop: () -> Unit,
  onMiddle: () -> Unit,
  onBottom: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cs = MaterialTheme.colorScheme
  GlassSurface(modifier = modifier, shape = RoundedCornerShape(CuORadius.lg), tintAlpha = 0.6f) {
    Column(Modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      IconButton(onClick = onTop, modifier = Modifier.size(30.dp)) {
        Text("\u25B2", fontSize = 11.sp, color = cs.onSurfaceVariant)
      }
      IconButton(onClick = onMiddle, modifier = Modifier.size(30.dp)) {
        Text("\u2022", fontSize = 14.sp, color = cs.onSurfaceVariant)
      }
      IconButton(onClick = onBottom, modifier = Modifier.size(30.dp)) {
        Text("\u25BC", fontSize = 11.sp, color = cs.onSurfaceVariant)
      }
    }
  }
}

/** Small spacer used by the composer rows. */
@Composable
fun RowGap(width: Int = 2) = Spacer(Modifier.width(width.dp))

/** Padding helper shared by the shell. */
val ShellContentPadding = PaddingValues(horizontal = 8.dp)
