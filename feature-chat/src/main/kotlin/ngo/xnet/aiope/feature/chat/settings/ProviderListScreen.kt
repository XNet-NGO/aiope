package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ngo.xnet.aiope.core.network.ProviderCategory
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.core.network.ProviderTemplates

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderListScreen(
  profiles: List<ProviderProfile>,
  activeTextId: String,
  activeMediaId: String,
  providerStore: ProviderStore,
  onSelectText: (ProviderProfile) -> Unit,
  onSelectMedia: (ProviderProfile) -> Unit,
  onEdit: (ProviderProfile) -> Unit,
  onChangeCategory: (ProviderProfile, ProviderCategory) -> Unit,
  onAdd: () -> Unit,
  onBack: () -> Unit,
) {
  val bgActive = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current.useBackground
  val textProfiles = profiles.filter { it.category == ProviderCategory.TEXT }
  val mediaProfiles = profiles.filter { it.category == ProviderCategory.MEDIA }

  Scaffold(
    containerColor = if (bgActive) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (bgActive) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
        title = { Text("Providers") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") } },
      )
    },
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
      item {
        val cs = MaterialTheme.colorScheme
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val referral = "https://novita.ai/?ref=nwq4otq&utm_source=affiliate"
        fun open(url: String) = runCatching {
          ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
          )
        }
        Card(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        ) {
          Column(Modifier.padding(14.dp)) {
            Text("Need an API key? Try Novita AI", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
              "AIOPE includes a disabled \"Novita AI\" provider below — an OpenAI-compatible " +
                "inference cloud with 100+ open models at low cost. Create an account, generate an " +
                "API key, then open the Novita provider here, paste your key, tap Fetch models to " +
                "auto-detect what's available, pick one, and enable it.",
              style = MaterialTheme.typography.bodySmall,
              color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { open(referral) }) { Text("Sign up for Novita") }
          }
        }
      }
      section(
        title = ProviderCategory.TEXT.displayName,
        subtitle = "Chat, tools, and vision. One active profile drives conversations.",
        profiles = textProfiles,
        activeId = activeTextId,
        emptyHint = "No text providers. Add one or move a provider here.",
        onSelect = onSelectText,
        onEdit = onEdit,
        moveLabel = "Move to Media Generation",
        onMove = { onChangeCategory(it, ProviderCategory.MEDIA) },
      )

      item { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)) }

      section(
        title = ProviderCategory.MEDIA.displayName,
        subtitle = "Image/video generation. One active profile drives media tools.",
        profiles = mediaProfiles,
        activeId = activeMediaId,
        emptyHint = "No media providers. Move an image/video provider here.",
        onSelect = onSelectMedia,
        onEdit = onEdit,
        moveLabel = "Move to Multimodal Text",
        onMove = { onChangeCategory(it, ProviderCategory.TEXT) },
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.section(
  title: String,
  subtitle: String,
  profiles: List<ProviderProfile>,
  activeId: String,
  emptyHint: String,
  onSelect: (ProviderProfile) -> Unit,
  onEdit: (ProviderProfile) -> Unit,
  moveLabel: String,
  onMove: (ProviderProfile) -> Unit,
) {
  item {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  if (profiles.isEmpty()) {
    item {
      Text(emptyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
  } else {
    items(profiles.size) { idx ->
      val p = profiles[idx]
      val builtin = ProviderTemplates.byId[p.builtinId]
      var menuOpen by remember { mutableStateOf(false) }
      ListItem(
        headlineContent = { Text(p.label.ifBlank { builtin?.displayName ?: "Custom" }) },
        supportingContent = {
          Text(
            "${p.effectiveApiBase().removePrefix("https://").removePrefix("http://").take(40)}  •  ${p.selectedModelId.ifBlank { "no model" }}",
            style = MaterialTheme.typography.bodySmall,
          )
        },
        trailingContent = {
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (p.id == activeId) Text("✔", color = MaterialTheme.colorScheme.primary)
            Box {
              IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.SwapHoriz, "Move") }
              DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(moveLabel) }, onClick = { menuOpen = false; onMove(p) })
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit(p) })
              }
            }
          }
        },
        modifier = Modifier.combinedClickable(onClick = { onSelect(p) }, onLongClick = { onEdit(p) }),
      )
    }
  }
}
