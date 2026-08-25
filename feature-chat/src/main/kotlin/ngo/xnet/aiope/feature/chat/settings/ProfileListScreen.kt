package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ngo.xnet.aiope.core.network.BuiltinProvider
import ngo.xnet.aiope.core.network.ProviderTemplates
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.ui.SettingsGroup
import ngo.xnet.aiope.feature.chat.ui.SettingsGroupSpacer
import ngo.xnet.aiope.feature.chat.ui.SettingsRow
import ngo.xnet.aiope.feature.chat.ui.SettingsRowDivider
import ngo.xnet.aiope.feature.chat.ui.SettingsSectionLabel

/**
 * Settings, grouped by what the user is actually trying to change.
 *
 * The previous version was a single flat list of eighteen `ListItem`s with a divider after each
 * one, so "Theme" and "Import Settings" looked equally important and nothing was findable. Rows are
 * now grouped into labelled cards (Model, Agent, Capabilities, Appearance, Data) with a tinted icon
 * per row, which is what makes a long settings list scannable. Every destination and side effect
 * from the old screen is preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileList(
  providerStore: ProviderStore,
  chatDao: ChatDao? = null,
  onAgent: () -> Unit,
  onTasks: () -> Unit,
  onTools: () -> Unit,
  onMcp: () -> Unit,
  onServers: () -> Unit = {},
  onVoice: () -> Unit = {},
  onTheme: () -> Unit = {},
  onProviders: () -> Unit = {},
  onRag: () -> Unit = {},
  onBack: () -> Unit,
) {
  val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
  val transparent = theme.useBackground
  val scaffoldColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background
  Scaffold(
    containerColor = scaffoldColor,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        // The largest text on screen shouldn't also be the lightest: titleLarge is W700 in our
        // scale, so it outweighs the W600 row titles below instead of inverting the hierarchy.
        title = {
          // titleLarge and the row titles are both SemiBold, so at 18sp the app bar read as
          // *lighter* than the 15sp rows next to it. Bold is what actually separates them.
          Text("Settings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background,
        ),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        Column(Modifier.padding(horizontal = 14.dp)) {
          SettingsSectionLabel("Model")
          SettingsGroup {
            SettingsRow("Providers", "API endpoints, keys, and models", Icons.Default.Cloud, onClick = onProviders)
            SettingsRowDivider()
            SettingsRow("Models per task", "Different models for chat, titles, agents", Icons.Default.Category, onClick = onTasks)
          }

          SettingsSectionLabel("Agent")
          SettingsGroup {
            SettingsRow("Behavior & prompt", "Identity, rules, self-improvement", Icons.Default.SmartToy, onClick = onAgent)
            SettingsRowDivider()
            SettingsRow("Tools", "Enable or disable individual tools", Icons.Default.Build, onClick = onTools)
            SettingsRowDivider()
            SettingsRow("Knowledge base", "Documents for on-device retrieval", Icons.Default.Book, onClick = onRag)
          }

          SettingsSectionLabel("Connections")
          SettingsGroup {
            SettingsRow("MCP servers", "External tool servers", Icons.Default.Extension, onClick = onMcp)
            SettingsRowDivider()
            SettingsRow("Remote servers", "SSH machines this agent can drive", Icons.Default.Dns, onClick = onServers)
            SettingsRowDivider()
            PlaceSearchKeyRow(providerStore)
          }

          SettingsSectionLabel("Experience")
          SettingsGroup {
            SettingsRow("Theme", "Colors, background, bubbles", Icons.Default.Palette, onClick = onTheme)
            SettingsRowDivider()
            SettingsRow("Voice", "Speech and live call settings", Icons.Default.Mic, onClick = onVoice)
          }

          SettingsSectionLabel("System")
          SettingsGroup {
            AlpineRow()
            if (chatDao != null) {
              SettingsRowDivider()
              BackupRows(chatDao)
            }
          }
          // Clear the gesture pill so the last card isn't sliced by the nav bar.
          Spacer(Modifier.height(48.dp))
        }
      }
    }
  }
}

/**
 * Geoapify key for `search_location`. Without it the tool only works when the active gateway
 * proxies `/v1/data?q=places`, so the key has to be reachable from the UI.
 */
@Composable
private fun PlaceSearchKeyRow(providerStore: ProviderStore) {
  var showDialog by remember { mutableStateOf(false) }
  var draft by remember { mutableStateOf("") }
  var hasKey by remember { mutableStateOf(providerStore.getGeoapifyKey().isNotBlank()) }
  SettingsRow(
    title = "Place search key",
    subtitle = if (hasKey) "Geoapify key set — search_location enabled" else "Not set — falls back to the gateway",
    icon = Icons.Default.Place,
    onClick = {
      draft = providerStore.getGeoapifyKey()
      showDialog = true
    },
  )
  if (showDialog) {
    AlertDialog(
      onDismissRequest = { showDialog = false },
      title = { Text("Geoapify API key") },
      text = {
        Column {
          Text(
            "Used by search_location to find nearby places. Free keys at geoapify.com. Leave empty to rely on the active gateway.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(12.dp))
          OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = {
        TextButton(onClick = {
          providerStore.setGeoapifyKey(draft.trim())
          hasKey = draft.isNotBlank()
          showDialog = false
        }) { Text("Save") }
      },
      dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
    )
  }
}

/** Export/import of providers, tools, agent prompt and memories as one JSON document. */
@Composable
private fun BackupRows(chatDao: ChatDao) {
  val scope = rememberCoroutineScope()
  val ctx = androidx.compose.ui.platform.LocalContext.current
  var status by remember { mutableStateOf("") }
  var exportJson by remember { mutableStateOf("") }
  val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.GetContent(),
  ) { uri ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        try {
          SettingsPorter.importFromUri(ctx, chatDao, uri, replace = false)
          withContext(Dispatchers.Main) { status = "Imported successfully" }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) { status = "Error: ${e.message?.take(40)}" }
        }
      }
    }
  }
  val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
  ) { uri ->
    if (uri != null && exportJson.isNotBlank()) {
      scope.launch(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportJson) }
        withContext(Dispatchers.Main) { status = "Exported successfully" }
      }
    }
  }
  SettingsRow(
    title = "Export settings",
    subtitle = "Back up providers, tools, agent, memories",
    icon = Icons.Default.Upload,
    onClick = {
      scope.launch(Dispatchers.IO) {
        val json = SettingsPorter.export(chatDao)
        withContext(Dispatchers.Main) {
          exportJson = json
          exportLauncher.launch("cuo-settings.json")
        }
      }
    },
  )
  SettingsRowDivider()
  SettingsRow(
    title = "Import settings",
    subtitle = status.ifBlank { "Restore from a backup file" },
    icon = Icons.Default.Download,
    onClick = { importLauncher.launch("application/json") },
  )
}

/** Alpine proot rootfs: deploy or redeploy the Linux environment `run_proot` executes in. */
@Composable
private fun AlpineRow() {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  var installed by remember { mutableStateOf(ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.isInstalled(ctx)) }
  var running by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf(if (installed) "Installed" else "Not installed") }
  val scope = rememberCoroutineScope()
  SettingsRow(
    title = "Alpine (proot)",
    subtitle = status,
    icon = Icons.Default.Terminal,
    showChevron = false,
    trailing = {
      // A bare TextButton's hit target was ~11dp tall and read like a link in a column of
      // navigation chevrons. A tonal button makes "acts now" distinct from "navigates".
      androidx.compose.material3.FilledTonalButton(
        enabled = !running,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        onClick = {
          if (!running) {
            running = true
            status = "Downloading…"
            scope.launch(Dispatchers.IO) {
              try {
                // Redeploy wipes the existing rootfs so setup re-downloads it.
                if (installed) {
                  status = "Removing old install…"
                  val envDir = ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.envDir(ctx)
                  envDir.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.delete() }
                  ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.rootfsDir(ctx).deleteRecursively()
                  status = "Old install removed"
                }
                ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.setup(ctx) { msg -> status = msg }
                installed = ngo.xnet.aiope.core.terminal.shell.ProotBootstrap.isInstalled(ctx)
                status = if (installed) "Installed" else "Failed"
              } catch (e: Exception) {
                status = "Error: ${e.message?.take(40)}"
              }
              running = false
            }
          }
        },
      ) {
        Text(
          when {
            running -> "Deploying…"
            installed -> "Redeploy"
            else -> "Deploy"
          },
        )
      }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplatePicker(onPick: (BuiltinProvider) -> Unit, onBack: () -> Unit) {
  val transparent = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current.useBackground
  Scaffold(
    containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        title = { Text("Add provider", style = MaterialTheme.typography.headlineSmall) },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background,
        ),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        Column(Modifier.padding(horizontal = 14.dp)) {
          SettingsSectionLabel("Templates")
          SettingsGroup {
            ProviderTemplates.ALL.forEachIndexed { index, b ->
              if (index > 0) SettingsRowDivider()
              SettingsRow(
                title = "${b.icon} ${b.displayName}",
                subtitle = b.apiBase ?: "Custom endpoint",
                onClick = { onPick(b) },
              )
            }
          }
          SettingsGroupSpacer()
          Spacer(Modifier.height(32.dp))
        }
      }
    }
  }
}
