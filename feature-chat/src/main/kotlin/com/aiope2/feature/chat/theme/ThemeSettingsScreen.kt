package com.aiope2.feature.chat.theme

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val PRESET_COLORS = listOf(
  0xFF00E5FF, 0xFF2979FF, 0xFF651FFF, 0xFFD500F9, 0xFFFF1744,
  0xFFFF9100, 0xFFFFEA00, 0xFF00E676, 0xFF69F0AE, 0xFFFFFFFF, 0xFF000000,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ThemePrefs(ctx) }
  val scope = rememberCoroutineScope()

  // Collect all state
  val themeMode by prefs.themeMode.collectAsState(initial = "dark")
  val useCustomColors by prefs.useCustomColors.collectAsState(initial = false)
  val primaryColor by prefs.primaryColor.collectAsState(initial = null)
  val secondaryColor by prefs.secondaryColor.collectAsState(initial = null)
  val useBackground by prefs.useBackground.collectAsState(initial = false)
  val backgroundUri by prefs.backgroundUri.collectAsState(initial = null)
  val backgroundMediaType by prefs.backgroundMediaType.collectAsState(initial = "image")
  val backgroundOpacity by prefs.backgroundOpacity.collectAsState(initial = 0.3f)
  val videoMuted by prefs.videoMuted.collectAsState(initial = true)
  val videoLoop by prefs.videoLoop.collectAsState(initial = true)
  val videoRotation by prefs.videoRotation.collectAsState(initial = 0)
  val useCustomBubbles by prefs.useCustomBubbles.collectAsState(initial = false)
  val userBubbleOpacity by prefs.userBubbleOpacity.collectAsState(initial = 1f)
  val aiBubbleOpacity by prefs.aiBubbleOpacity.collectAsState(initial = 1f)
  val userBubbleColor by prefs.userBubbleColor.collectAsState(initial = null)
  val aiBubbleColor by prefs.aiBubbleColor.collectAsState(initial = null)
  val userTextColor by prefs.userTextColor.collectAsState(initial = null)
  val aiTextColor by prefs.aiTextColor.collectAsState(initial = null)
  val showThinking by prefs.showThinking.collectAsState(initial = true)
  val showStatusTags by prefs.showStatusTags.collectAsState(initial = true)
  val showToolActivity by prefs.showToolActivity.collectAsState(initial = true)
  val uiOpacity by prefs.uiOpacity.collectAsState(initial = 1f)
  val useUiColor by prefs.useUiColor.collectAsState(initial = false)
  val uiColor by prefs.uiColor.collectAsState(initial = null)
  val useCustomText by prefs.useCustomText.collectAsState(initial = false)
  val primaryTextColor by prefs.primaryTextColor.collectAsState(initial = null)
  val secondaryTextColor by prefs.secondaryTextColor.collectAsState(initial = null)

  val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val isVideo = ctx.contentResolver.getType(uri)?.startsWith("video") == true
        // Copy to internal storage
        val dir = java.io.File(ctx.filesDir, "theme_bg")
        dir.mkdirs()
        val ext = if (isVideo) "mp4" else "jpg"
        val dest = java.io.File(dir, "background.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
        val internalUri = Uri.fromFile(dest).toString()
        prefs.set(ThemePrefs.BACKGROUND_URI, internalUri)
        prefs.set(ThemePrefs.BACKGROUND_MEDIA_TYPE, if (isVideo) "video" else "image")
        prefs.set(ThemePrefs.USE_BACKGROUND, true)
      }
    }
  }

  Scaffold(
    containerColor = if (prefs.useBackground.collectAsState(initial = false).value) Color.Transparent else MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = if (com.aiope2.feature.chat.theme.LocalThemeState.current.useBackground) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.surface),
        title = { Text("Theme") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // ── Theme Mode ──
      SectionHeader("Theme Mode")
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("light", "system", "dark").forEachIndexed { i, mode ->
          SegmentedButton(selected = themeMode == mode, onClick = { scope.launch { prefs.set(ThemePrefs.THEME_MODE, mode) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
            Text(mode.replaceFirstChar { it.uppercase() }, fontSize = 13.sp)
          }
        }
      }

      // ── Custom Colors ──
      SectionHeader("Accent Colors")
      ToggleRow("Use custom colors", useCustomColors) { scope.launch { prefs.set(ThemePrefs.USE_CUSTOM_COLORS, it) } }
      if (useCustomColors) {
        Text("Primary", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = primaryColor) { scope.launch { prefs.set(ThemePrefs.PRIMARY_COLOR, it) } }
        Spacer(Modifier.height(4.dp))
        Text("Secondary", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = secondaryColor) { scope.launch { prefs.set(ThemePrefs.SECONDARY_COLOR, it) } }
      }

      HorizontalDivider(Modifier.padding(vertical = 4.dp))

      // ── Background ──
      SectionHeader("Background")
      ToggleRow("Background image/video", useBackground) { scope.launch { prefs.set(ThemePrefs.USE_BACKGROUND, it) } }
      if (useBackground) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { mediaPicker.launch("*/*") }) { Text("Pick media") }
          if (backgroundUri != null) {
            Text(if (backgroundMediaType == "video") "Video set" else "Image set", fontSize = 12.sp, color = Color(0xFF4CAF50))
          }
        }
        Text("Opacity", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = backgroundOpacity, onValueChange = { scope.launch { prefs.set(ThemePrefs.BACKGROUND_OPACITY, it) } }, valueRange = 0.05f..1f)
        if (backgroundMediaType == "video") {
          ToggleRow("Mute video", videoMuted) { scope.launch { prefs.set(ThemePrefs.VIDEO_MUTED, it) } }
          ToggleRow("Loop video", videoLoop) { scope.launch { prefs.set(ThemePrefs.VIDEO_LOOP, it) } }
        }
        Text("Rotation", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
          listOf(0, 90, 180, 270).forEachIndexed { i, deg ->
            SegmentedButton(selected = videoRotation == deg, onClick = { scope.launch { prefs.set(ThemePrefs.VIDEO_ROTATION, deg) } }, shape = SegmentedButtonDefaults.itemShape(i, 4)) {
              Text("$deg°", fontSize = 12.sp)
            }
          }
        }
        OutlinedButton(onClick = {
          scope.launch {
            prefs.remove(ThemePrefs.BACKGROUND_URI)
            prefs.set(ThemePrefs.USE_BACKGROUND, false)
          }
        }) { Text("Clear background") }
      }

      HorizontalDivider(Modifier.padding(vertical = 4.dp))

      // ── Bubble Colors ──
      SectionHeader("Bubble Colors")
      ToggleRow("Custom bubble colors", useCustomBubbles) { scope.launch { prefs.set(ThemePrefs.USE_CUSTOM_BUBBLES, it) } }
      if (useCustomBubbles) {
        Text("User bubble", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = userBubbleColor) { scope.launch { prefs.set(ThemePrefs.USER_BUBBLE_COLOR, it) } }
        Text("User text", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = userTextColor) { scope.launch { prefs.set(ThemePrefs.USER_TEXT_COLOR, it) } }
        Spacer(Modifier.height(4.dp))
        Text("AI bubble", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = aiBubbleColor) { scope.launch { prefs.set(ThemePrefs.AI_BUBBLE_COLOR, it) } }
        Text("AI text", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = aiTextColor) { scope.launch { prefs.set(ThemePrefs.AI_TEXT_COLOR, it) } }
        Spacer(Modifier.height(4.dp))
        Text("User bubble opacity: ${(userBubbleOpacity * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = userBubbleOpacity, onValueChange = { scope.launch { prefs.set(ThemePrefs.USER_BUBBLE_OPACITY, it) } }, valueRange = 0.05f..1f)
        Text("AI bubble opacity: ${(aiBubbleOpacity * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = aiBubbleOpacity, onValueChange = { scope.launch { prefs.set(ThemePrefs.AI_BUBBLE_OPACITY, it) } }, valueRange = 0.05f..1f)
      }

      HorizontalDivider(Modifier.padding(vertical = 4.dp))

      // ── Display Toggles ──
      SectionHeader("Display")
      ToggleRow("Show thinking process", showThinking) { scope.launch { prefs.set(ThemePrefs.SHOW_THINKING, it) } }
      ToggleRow("Show status tags", showStatusTags) { scope.launch { prefs.set(ThemePrefs.SHOW_STATUS_TAGS, it) } }
      ToggleRow("Show tool activity", showToolActivity) { scope.launch { prefs.set(ThemePrefs.SHOW_TOOL_ACTIVITY, it) } }
      Spacer(Modifier.height(4.dp))
      Text("UI opacity: ${(uiOpacity * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Slider(value = uiOpacity, onValueChange = { scope.launch { prefs.set(ThemePrefs.UI_OPACITY, it) } }, valueRange = 0.1f..1f)
      Spacer(Modifier.height(4.dp))
      ToggleRow("Custom UI color", useUiColor) { scope.launch { prefs.set(ThemePrefs.USE_UI_COLOR, it) } }
      if (useUiColor) {
        ColorRow(selected = uiColor) { scope.launch { prefs.set(ThemePrefs.UI_COLOR, it) } }
      }
      Spacer(Modifier.height(4.dp))
      ToggleRow("Custom text colors", useCustomText) { scope.launch { prefs.set(ThemePrefs.USE_CUSTOM_TEXT, it) } }
      if (useCustomText) {
        Text("Primary text", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = primaryTextColor) { scope.launch { prefs.set(ThemePrefs.PRIMARY_TEXT_COLOR, it) } }
        Text("Secondary text", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ColorRow(selected = secondaryTextColor) { scope.launch { prefs.set(ThemePrefs.SECONDARY_TEXT_COLOR, it) } }
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(text, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onToggle)
  }
}

@Composable
private fun ColorRow(selected: Int?, onPick: (Int) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
    PRESET_COLORS.forEach { c ->
      val color = Color(c.toInt() or 0xFF000000.toInt())
      val isSelected = selected == color.toArgb()
      Box(
        Modifier.size(32.dp).clip(CircleShape).background(color)
          .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
          .clickable { onPick(color.toArgb()) },
      )
    }
  }
}
