package com.aiope2.feature.chat.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiope2.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ToolToggleScreen(toolStore: ToolStore, onBack: () -> Unit) {
  val tools = listOf(
    "run_sh" to "Android shell commands",
    "run_proot" to "Alpine proot Linux environment",
    "read_file" to "Read file contents",
    "write_file" to "Write files",
    "list_directory" to "List directory contents",
    "get_location" to "GPS location",
    "search_location" to "Search places",
    "open_intent" to "Open URLs, maps, apps",
    "fetch_url" to "Fetch web pages",
    "query_data" to "Live data queries",
    "search_web" to "Web search",
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
    "memory_store" to "Store persistent memories",
    "memory_recall" to "Recall memories",
    "memory_forget" to "Delete memories",
    "generate_image" to "Generate images (Pollinations)",
  )
  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Tools") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        var uiEnabled by remember { mutableStateOf(toolStore.isDynamicUiEnabled()) }
        ListItem(
          headlineContent = { Text("Dynamic UI", fontSize = 14.sp) },
          supportingContent = { Text("Enable aiope-ui rich interactive blocks in responses", style = MaterialTheme.typography.bodySmall) },
          trailingContent = {
            Switch(checked = uiEnabled, onCheckedChange = {
              uiEnabled = it
              toolStore.setDynamicUiEnabled(it)
            })
          },
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
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
