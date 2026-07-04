package ngo.xnet.aiope.feature.chat.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "voice_settings"
private const val KEY_VOICE = "voice_name"
private const val DEFAULT_VOICE = "Aoede"

private val VOICES = listOf(
  "Aoede" to "♀ Breezy", "Puck" to "♂ Upbeat", "Kore" to "♀ Firm", "Charon" to "♂ Informative",
  "Fenrir" to "♂ Excitable", "Leda" to "♀ Youthful", "Zephyr" to "♀ Bright", "Orus" to "♂ Firm",
  "Callirrhoe" to "♀ Easy-going", "Enceladus" to "♂ Breathy", "Iapetus" to "♂ Clear",
  "Umbriel" to "♂ Easy-going", "Algieba" to "♂ Smooth", "Despina" to "♀ Smooth",
  "Autonoe" to "♀ Bright", "Erinome" to "♀ Clear", "Algenib" to "♂ Gravelly",
  "Rasalgethi" to "♂ Informative", "Laomedeia" to "♀ Upbeat", "Achernar" to "♀ Soft",
  "Alnilam" to "♂ Firm", "Schedar" to "♂ Even", "Gacrux" to "♀ Mature",
  "Pulcherrima" to "♀ Forward", "Achird" to "♂ Friendly", "Zubenelgenubi" to "♂ Casual",
  "Vindemiatrix" to "♀ Gentle", "Sadachbia" to "♂ Lively", "Sadaltager" to "♂ Knowledgeable",
  "Sulafat" to "♀ Warm",
)

private val DISPLAY_NAMES = mapOf(
  "Aoede" to "Aria", "Puck" to "Jake", "Kore" to "Maya", "Charon" to "Marcus",
  "Fenrir" to "Tyler", "Leda" to "Lily", "Zephyr" to "Zoe", "Orus" to "Owen",
  "Callirrhoe" to "Chloe", "Enceladus" to "Ethan", "Iapetus" to "Ian",
  "Umbriel" to "Blake", "Algieba" to "Alex", "Despina" to "Dana",
  "Autonoe" to "Amber", "Erinome" to "Emma", "Algenib" to "Grant",
  "Rasalgethi" to "Ryan", "Laomedeia" to "Layla", "Achernar" to "Ava",
  "Alnilam" to "Nathan", "Schedar" to "Scott", "Gacrux" to "Grace",
  "Pulcherrima" to "Paige", "Achird" to "Adam", "Zubenelgenubi" to "Zane",
  "Vindemiatrix" to "Violet", "Sadachbia" to "Sam", "Sadaltager" to "Sean",
  "Sulafat" to "Sophie",
)

fun getVoiceName(context: Context): String {
  return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    .getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
  var selected by remember { mutableStateOf(prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE) }
  val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
  val scaffoldColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background

  Scaffold(
    containerColor = scaffoldColor,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        title = { Text("Voice") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    }
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
      item {
        Spacer(Modifier.height(8.dp))
        Text("Voice", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text("Select the voice used for live voice conversations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
      }
      items(VOICES.size) { i ->
        val (apiName, style) = VOICES[i]
        val displayName = DISPLAY_NAMES[apiName] ?: apiName
        val isSelected = apiName == selected
        ListItem(
          headlineContent = { Text(displayName) },
          supportingContent = { Text(style, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
          trailingContent = {
            RadioButton(selected = isSelected, onClick = null)
          },
          modifier = Modifier.clickable {
            selected = apiName
            prefs.edit().putString(KEY_VOICE, apiName).apply()
          },
        )
        if (i < VOICES.size - 1) HorizontalDivider()
      }
    }
  }
}
