package com.aiope2.feature.chat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.SettingsKvEntity
import kotlinx.coroutines.launch

internal const val AGENT_PROMPT_KEY = "agent_prompt" // kept for migration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentScreen(dao: ChatDao, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val values = remember { mutableStateMapOf<String, String>() }
  var loaded by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    AGENT_SECTIONS.forEach { section ->
      section.subsections.forEach { sub ->
        val key = "$AGENT_PREFIX${sub.key}"
        values[key] = dao.getSetting(key) ?: sub.default
      }
    }
    loaded = true
  }

  fun save(key: String, value: String) {
    values[key] = value
    scope.launch { dao.upsertSetting(SettingsKvEntity(key, value)) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Agent") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        actions = {
          IconButton(onClick = {
            AGENT_SECTIONS.forEach { section ->
              section.subsections.forEach { sub ->
                save("$AGENT_PREFIX${sub.key}", sub.default)
              }
            }
          }) { Icon(Icons.Default.Refresh, "Reset all to defaults") }
        },
      )
    },
  ) { padding ->
    if (!loaded) return@Scaffold
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      AGENT_SECTIONS.forEach { section ->
        item(key = "header_${section.key}") {
          Row(Modifier.padding(top = 16.dp, bottom = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
              Text(section.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { section.subsections.forEach { sub -> save("$AGENT_PREFIX${sub.key}", sub.default) } }) {
              Icon(Icons.Default.Refresh, "Reset ${section.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        section.subsections.forEach { sub ->
          item(key = "field_${sub.key}") {
            val key = "$AGENT_PREFIX${sub.key}"
            OutlinedTextField(
              value = values[key] ?: "",
              onValueChange = { save(key, it) },
              label = { Text(sub.label) },
              placeholder = { Text(sub.hint, fontSize = 12.sp) },
              modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
              minLines = 2,
              maxLines = 8,
            )
          }
        }
      }
    }
  }
}

/** Concatenate all agent sections into a single system prompt. */
internal suspend fun buildAgentPrompt(dao: ChatDao): String = buildString {
  AGENT_SECTIONS.forEach { section ->
    val parts = section.subsections.mapNotNull { sub ->
      val v = dao.getSetting("$AGENT_PREFIX${sub.key}") ?: sub.default
      v.takeIf { it.isNotBlank() }
    }
    if (parts.isNotEmpty()) {
      append("## ${section.title}\n")
      append(parts.joinToString("\n\n"))
      append("\n\n")
    }
  }
}.trimEnd()
