package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tool catalog shown to the user, grouped by capability.
 *
 * IMPORTANT: this list must stay in sync with ToolExecutor.buildToolDefs() — every tool the
 * model can call needs a row here, otherwise the user has no way to turn it off (tools default
 * to enabled). Descriptions are user-facing, so keep them plain-language.
 */
private val toolCatalog: List<Pair<String, List<Pair<String, String>>>> = listOf(
  "Agent & planning" to listOf(
    "todo_write" to "Let the agent keep a task list while working",
    "todo_read" to "Read back the agent's task list",
    "orchestrate" to "Run a multi-agent pipeline for complex jobs",
    "datetime_now" to "Current date, time, timezone and day of week",
  ),
  "Scheduling" to listOf(
    "schedule_task" to "Schedule prompts to run automatically in background",
    "list_schedules" to "List scheduled tasks and their next run",
    "cancel_schedule" to "Cancel a scheduled task",
    "set_alarm" to "Set a device alarm",
    "dismiss_alarm" to "Dismiss or cancel alarms",
  ),
  "Files & shell" to listOf(
    "read_file" to "Read file contents",
    "write_file" to "Create or overwrite files",
    "edit_file" to "Find-and-replace inside a file",
    "list_directory" to "List directory contents",
    "search_files" to "Search file contents or names in a folder tree",
    "run_sh" to "Android shell commands",
    "run_proot" to "Alpine proot Linux environment",
  ),
  "Web & network" to listOf(
    "search_web" to "Web search",
    "search_images" to "Image search",
    "fetch_url" to "Fetch and read web pages",
    "http_request" to "Call any HTTP API directly",
    "query_data" to "Live data queries (weather, space, finance, …)",
  ),
  "Browser control" to listOf(
    "browser_navigate" to "Browser navigation",
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
  "Knowledge & memory" to listOf(
    "memory_store" to "Store persistent memories",
    "memory_recall" to "Recall memories",
    "memory_forget" to "Delete memories",
    "skill_list" to "List installed skill playbooks",
    "skill_view" to "Read a skill playbook before acting",
    "skill_save" to "Write a reusable skill after solving something",
    "skill_delete" to "Delete an installed skill",
    "search_messages" to "Search across all past conversations",
    "rag_search" to "Search your on-device knowledge base",
    "goal_set" to "Create & update persistent long-term goals",
    "goal_list" to "List persistent goals",
    "curator_run" to "Curate & clean up persistent memory",
    "rag_index" to "Add a document to the knowledge base",
  ),
  "Media & vision" to listOf(
    "image_generate" to "Generate images from a prompt",
    "analyze_image" to "Describe or read text from an image",
    "media_control" to "Control music playback",
  ),
  "Device & personal data" to listOf(
    "device_info" to "Battery, storage, RAM, network, model",
    "get_location" to "GPS location",
    "search_location" to "Search nearby places",
    "open_intent" to "Open URLs, maps, apps",
    "send_notification" to "Show a notification",
    "clipboard_copy" to "Copy text to clipboard",
    "clipboard_read" to "Read clipboard contents",
    "read_calendar" to "Read calendar events",
    "create_event" to "Create calendar events",
    "delete_event" to "Delete calendar events",
    "read_contacts" to "Read contacts",
    "read_sms" to "Read SMS messages",
    "send_sms" to "Send SMS messages",
    "delete_sms" to "Delete SMS messages",
  ),
)

@Composable
internal fun ToolToggleScreen(toolStore: ToolStore, onBack: () -> Unit) {
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
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        var uiEnabled by remember { mutableStateOf(toolStore.isDynamicUiEnabled()) }
        ListItem(
          headlineContent = { Text("Dynamic UI", fontSize = 14.sp) },
          supportingContent = { Text("Enable CuO rich interactive blocks in responses", style = MaterialTheme.typography.bodySmall) },
          trailingContent = {
            Switch(checked = uiEnabled, onCheckedChange = {
              uiEnabled = it
              toolStore.setDynamicUiEnabled(it)
            })
          },
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
      }
      toolCatalog.forEach { (section, tools) ->
        item {
          Text(
            section.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
          )
        }
        items(tools.size) { i ->
          val (id, desc) = tools[i]
          var enabled by remember { mutableStateOf(toolStore.isToolEnabled(id)) }
          ListItem(
            headlineContent = { Text(id, fontSize = 14.sp) },
            supportingContent = { Text(desc, style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
              Switch(checked = enabled, onCheckedChange = {
                enabled = it
                toolStore.setToolEnabled(id, it)
              })
            },
          )
          if (i < tools.size - 1) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
        }
      }
    }
  }
}
