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
internal fun TaskModelScreen(providerStore: ProviderStore, onBack: () -> Unit) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val taskStore = remember { com.aiope2.core.network.TaskModelStore(ctx) }
  val profiles = providerStore.getAll()

  Scaffold(topBar = {
    TopAppBar(
      title = { Text("Default Models per Task") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
  }) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
      item {
        Card(
          Modifier.fillMaxWidth().padding(vertical = 8.dp),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        ) {
          Column(Modifier.padding(16.dp)) {
            Text(
              "Assign different models to different tasks. Each task falls back to the active profile if not configured.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      items(com.aiope2.core.network.ModelTask.configurable) { task ->
        TaskCard(task, taskStore, providerStore, profiles)
        Spacer(Modifier.height(8.dp))
      }

      item { Spacer(Modifier.height(32.dp)) }
    }
  }
}

@Composable
private fun TaskCard(
  task: com.aiope2.core.network.ModelTask,
  taskStore: com.aiope2.core.network.TaskModelStore,
  providerStore: ProviderStore,
  profiles: List<ProviderProfile>,
) {
  var tc by remember { mutableStateOf(taskStore.getTaskConfig(task)) }
  var expanded by remember { mutableStateOf(false) }
  val assignedProfile = tc.profileId?.let { pid -> profiles.firstOrNull { it.id == pid } }
  val displayConfig = assignedProfile?.label ?: "Active profile"
  val displayModel = tc.modelId ?: assignedProfile?.selectedModelId ?: "default"

  Card(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
  ) {
    Column {
      // Header — tap to expand
      Surface(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface,
      ) {
        Column(Modifier.padding(16.dp)) {
          Text(task.label, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
          Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(8.dp))

          // Current assignment
          Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          ) {
            Row(
              Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(Modifier.weight(1f)) {
                Text("Config: $displayConfig", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text("Model: $displayModel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Expanded: profile + model selection
      androidx.compose.animation.AnimatedVisibility(visible = expanded) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          Text(
            "Select configuration",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
          )

          // "Use active profile" option
          val isDefault = tc.profileId == null
          Surface(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
              taskStore.clearTaskConfig(task)
              tc = com.aiope2.core.network.TaskModelConfig(task.id)
              expanded = false
            },
            shape = RoundedCornerShape(8.dp),
            color = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
              if (isDefault) 0.dp else 0.5.dp,
              if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
          ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
              if (isDefault) {
                Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
              }
              Text(
                "Use active profile (default)",
                fontWeight = if (isDefault) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
            }
          }

          // Each profile with its models
          profiles.forEach { profile ->
            var profileExpanded by remember { mutableStateOf(false) }
            val models = providerStore.getModelCache(profile.builtinId)
              ?: providerStore.getModelCacheStale(profile.builtinId)
              ?: com.aiope2.core.network.ProviderTemplates.byId[profile.builtinId]?.defaultModels ?: emptyList()
            val isProfileSelected = tc.profileId == profile.id

            Surface(
              Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                if (models.size > 1) {
                  profileExpanded = !profileExpanded
                } else {
                  val newTc = com.aiope2.core.network.TaskModelConfig(task.id, profile.id, profile.selectedModelId)
                  taskStore.setTaskConfig(task, newTc)
                  tc = newTc
                  expanded = false
                }
              },
              shape = RoundedCornerShape(8.dp),
              color = if (isProfileSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
              border = androidx.compose.foundation.BorderStroke(
                if (isProfileSelected) 0.dp else 0.5.dp,
                if (isProfileSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              ),
            ) {
              Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isProfileSelected && models.size <= 1) {
                  Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                  Text(
                    profile.label,
                    fontWeight = if (isProfileSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (isProfileSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  )
                  if (models.size > 1) {
                    Text("${models.size} models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  } else {
                    Text(profile.selectedModelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                if (models.size > 1) Icon(if (profileExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }

            // Sub-expand: individual models
            androidx.compose.animation.AnimatedVisibility(visible = profileExpanded && models.size > 1) {
              Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)) {
                models.forEach { m ->
                  val isModelSelected = isProfileSelected && tc.modelId == m.id
                  Surface(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                      val newTc = com.aiope2.core.network.TaskModelConfig(task.id, profile.id, m.id)
                      taskStore.setTaskConfig(task, newTc)
                      tc = newTc
                      expanded = false
                      profileExpanded = false
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isModelSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                      if (isModelSelected) {
                        Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                      }
                      Text(
                        m.displayName,
                        fontSize = 13.sp,
                        fontWeight = if (isModelSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (isModelSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
    }
  }
}
internal suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelDef> = withContext(Dispatchers.IO) {
  try {
    var base = baseUrl.trimEnd('/')
    val url = when {
      base.endsWith("/v1") || base.endsWith("/openai") -> "$base/models"
      else -> "$base/v1/models"
    }
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    val body = conn.inputStream.bufferedReader().readText()
    val data = org.json.JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
    (0 until data.length()).map {
      val o = data.getJSONObject(it)
      val id = o.getString("id")
      val name = o.optString("name", id).ifBlank { id }
      val inputMods = o.optJSONObject("modalities")?.optJSONArray("input")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
      val outputMods = o.optJSONObject("modalities")?.optJSONArray("output")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
      val outMod = when {
        "image" in outputMods -> "image"
        "audio" in outputMods -> "audio"
        id.startsWith("cf-image/") -> "image"
        else -> "text"
      }
      ModelDef(
        id, name,
        contextWindow = o.optInt("context_window"),
        supportsTools = o.optBoolean("tool_call", outMod == "text"),
        supportsVision = "image" in inputMods || o.optBoolean("attachment"),
        supportsAudio = "audio" in inputMods,
        supportsVideo = "video" in inputMods,
        supportsReasoning = o.optBoolean("reasoning"),
        outputModality = outMod,
        maxOutput = o.optInt("max_output"),
        family = o.optString("family", ""),
      )
    }.sortedBy { it.id }
  } catch (e: Exception) {
    android.util.Log.e("FetchModels", "Failed: ${e.message}")
    emptyList()
  }
}
