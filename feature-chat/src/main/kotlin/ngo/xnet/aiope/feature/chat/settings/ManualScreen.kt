package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluid.compose.MarkdownTheme
import com.fluid.compose.UniversalMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-browsable AIOPE manual. Lists the bundled manual pages
 * (the bundled assets/manual markdown files — the same source the `introspect`
 * tool searches) and
 * renders a selected page with the app's markdown renderer. Read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualScreen(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
  val bg = theme.useBackground

  // Discover bundled pages once. Each entry: file name + display title (from the
  // first "# " heading, else a title-cased file name).
  var pages by remember { mutableStateOf<List<ManualPage>>(emptyList()) }
  var openContent by remember { mutableStateOf<String?>(null) }
  var openTitle by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    pages = withContext(Dispatchers.IO) { loadManualIndex(ctx) }
  }

  Scaffold(
    containerColor = if (bg) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (bg) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
        title = { Text(if (openContent == null) "Manual" else openTitle) },
        navigationIcon = {
          IconButton(onClick = { if (openContent != null) openContent = null else onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
      )
    },
  ) { pad ->
    val content = openContent
    if (content == null) {
      // Index
      LazyColumn(Modifier.fillMaxSize().padding(pad)) {
        item {
          Text(
            "AIOPE's built-in manual — the same pages the assistant reads. Tap a topic to read it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
          )
          HorizontalDivider()
        }
        items(pages) { page ->
          ListItem(
            headlineContent = { Text(page.title, fontSize = 15.sp, fontWeight = FontWeight.Medium) },
            modifier = Modifier.clickable {
              openTitle = page.title
              openContent = "" // show loading immediately
              // read the file body
              loadPageAsync(ctx, page.file) { body ->
                openContent = body ?: "_Could not load ${page.file}._"
              }
            },
          )
          HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
        }
        item { Spacer(Modifier.height(24.dp)) }
      }
    } else {
      // Reader
      Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
      ) {
        if (content.isEmpty()) {
          Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          val cs = MaterialTheme.colorScheme
          val isDark = theme.isDark
          val mdTheme = remember(cs, isDark) {
            MarkdownTheme(
              textColor = cs.onSurface,
              headingColor = cs.onSurface,
              linkColor = cs.primary,
              listBulletColor = cs.onSurfaceVariant,
              codeTextColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFE0E0E0) else androidx.compose.ui.graphics.Color(0xFF1A1A1A),
              codeBgColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF111111) else androidx.compose.ui.graphics.Color(0xFFF0F0F0),
              codeBorderColor = cs.outlineVariant,
              inlineCodeTextColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFFFB300) else androidx.compose.ui.graphics.Color(0xFFE65100),
              inlineCodeBgColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF1A1A1A) else androidx.compose.ui.graphics.Color(0xFFEEEEEE),
              blockQuoteBorderColor = cs.outlineVariant,
              blockQuoteTextColor = cs.onSurfaceVariant,
              tableHeaderBgColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF1A1A1A) else androidx.compose.ui.graphics.Color(0xFFE8E8E8),
              tableBodyBgColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF111111) else androidx.compose.ui.graphics.Color(0xFFF5F5F5),
              tableBorderColor = cs.outlineVariant,
              tableHeaderTextColor = cs.onSurface,
            )
          }
          UniversalMarkdown(
            content = content,
            theme = mdTheme,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

internal data class ManualPage(val file: String, val title: String)

private fun loadManualIndex(ctx: android.content.Context): List<ManualPage> {
  val am = ctx.assets
  val files = runCatching { am.list("manual")?.filter { it.endsWith(".md") } ?: emptyList() }
    .getOrDefault(emptyList())
    .sorted()
  return files.map { name ->
    val title = runCatching {
      am.open("manual/$name").bufferedReader().use { r ->
        r.lineSequence().firstOrNull { it.trimStart().startsWith("# ") }
          ?.trimStart()?.removePrefix("# ")?.trim()
      }
    }.getOrNull() ?: name.removeSuffix(".md").replace('-', ' ').replaceFirstChar { it.uppercase() }
    ManualPage(file = name, title = title)
  }.sortedBy { it.title }
}

private fun loadPageAsync(ctx: android.content.Context, file: String, cb: (String?) -> Unit) {
  Thread {
    val body = runCatching {
      ctx.assets.open("manual/$file").bufferedReader().use { it.readText() }
    }.getOrNull()
    android.os.Handler(android.os.Looper.getMainLooper()).post { cb(body) }
  }.apply { isDaemon = true; start() }
}
