package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ngo.xnet.aiope.feature.chat.engine.AgentMode

/**
 * Tools settings with per-mode granularity.
 *
 * Each tool has a MASTER switch (off = disabled everywhere) plus per-mode
 * toggles for Chat / Plan / Build (Media exposes no tools). Per-mode toggles
 * fall back to sane defaults (AgentMode.toolDefaultEnabled) until the user
 * overrides them. Trimming a mode's tool set shrinks the request tool schema.
 */
@Composable
internal fun ToolToggleScreen(toolStore: ToolStore, onBack: () -> Unit) {
  // Canonical tool list (id -> human description). Grouped for readability.
  val groups: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "System & Files" to listOf(
      "run_sh" to "Android shell commands",
      "run_proot" to "Alpine proot Linux environment",
      "read_file" to "Read file contents",
      "write_file" to "Write files",
      "edit_file" to "Find/replace edit a file",
      "list_directory" to "List directory contents",
      "search_files" to "Search files by content/name",
      "device_info" to "Battery, storage, RAM, network",
      "media_control" to "Play/pause/next/previous",
      "clipboard_copy" to "Copy to clipboard",
      "clipboard_read" to "Read clipboard",
      "datetime_now" to "Current date/time",
    ),
    "Web & Data" to listOf(
      "search_web" to "Web search",
      "search_images" to "Image search",
      "fetch_url" to "Fetch web pages",
      "http_request" to "Generic HTTP/API calls",
      "query_data" to "Live real-time data feeds",
      "get_location" to "GPS location",
      "search_location" to "Search places/addresses",
      "open_intent" to "Open URLs, maps, dialer, apps",
    ),
    "Browser" to listOf(
      "browser_navigate" to "Navigate the in-app browser",
      "browser_content" to "Read browser page",
      "browser_elements" to "List browser elements",
      "browser_click" to "Click browser elements",
      "browser_fill" to "Fill browser inputs",
      "browser_eval" to "Run JavaScript in browser",
      "browser_back" to "Browser back",
      "browser_scroll" to "Scroll browser",
      "browser_open" to "Open browser panel",
      "browser_close" to "Close browser panel",
      "browser_maximize" to "Maximize browser",
    ),
    "Communication" to listOf(
      "read_sms" to "Read SMS",
      "send_sms" to "Send SMS",
      "delete_sms" to "Delete SMS",
      "read_contacts" to "Read contacts",
      "send_notification" to "Post notifications",
      "read_calendar" to "Read calendar",
      "create_event" to "Create calendar event",
      "delete_event" to "Delete calendar event",
      "set_alarm" to "Set alarm",
      "dismiss_alarm" to "Dismiss alarm",
    ),
    "AI & Knowledge" to listOf(
      "memory_store" to "Store persistent memories",
      "memory_recall" to "Recall memories",
      "memory_forget" to "Delete memories",
      "rag_search" to "Search knowledge base",
      "rag_index" to "Index into knowledge base",
      "introspect" to "Answer questions about AIOPE itself (built-in manual)",
      "image_generate" to "Generate images",
      "analyze_image" to "Vision / image analysis",
      "orchestrate" to "Multi-agent pipelines",
    ),
    "Tasks" to listOf(
      "todo_write" to "Write task list",
      "todo_read" to "Read task list",
      "schedule_task" to "Schedule background task",
      "cancel_schedule" to "Cancel scheduled task",
      "list_schedules" to "List scheduled tasks",
    ),
  )

  // Modes with tools (Media has none).
  val modes = listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.BUILD)

  val bgActive = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current.useBackground
  Scaffold(
    containerColor = if (bgActive) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (bgActive) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
        title = { Text("Tools") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
      item {
        var uiEnabled by remember { mutableStateOf(toolStore.isDynamicUiEnabled()) }
        ListItem(
          headlineContent = { Text("Dynamic UI", fontSize = 14.sp) },
          supportingContent = { Text("Enable aiope-ui rich interactive blocks in responses", style = MaterialTheme.typography.bodySmall) },
          trailingContent = {
            Switch(checked = uiEnabled, onCheckedChange = { uiEnabled = it; toolStore.setDynamicUiEnabled(it) })
          },
        )
        Text(
          "Master switch disables a tool everywhere. Chat / Plan / Build toggle it per mode (Media has no tools).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
      }

      groups.forEach { (groupName, tools) ->
        item {
          Text(
            groupName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
          )
        }
        items(tools.size) { i ->
          val (id, desc) = tools[i]
          ToolRow(toolStore, id, desc, modes)
          if (i < tools.size - 1) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.25f))
        }
      }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun ToolRow(toolStore: ToolStore, id: String, desc: String, modes: List<AgentMode>) {
  var master by remember { mutableStateOf(toolStore.isToolEnabled(id)) }
  Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(id, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked = master, onCheckedChange = { master = it; toolStore.setToolEnabled(id, it) })
    }
    // Per-mode chips (only meaningful when the master switch is on).
    Row(
      Modifier.padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      modes.forEach { mode ->
        var on by remember(id, mode) { mutableStateOf(toolStore.isToolEnabledForMode(id, mode)) }
        FilterChip(
          selected = on && master,
          enabled = master,
          onClick = {
            val newVal = !on
            on = newVal
            toolStore.setToolEnabledForMode(id, mode, newVal)
          },
          label = { Text(mode.label, fontSize = 12.sp) },
          shape = RoundedCornerShape(8.dp),
        )
      }
    }
  }
}
