package com.aiope2.feature.chat.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class ThemeState(
  val mode: String = "dark",
  val isDark: Boolean = true,
  val primaryColor: Color? = null,
  val secondaryColor: Color? = null,
  val useCustomColors: Boolean = false,
  val useBackground: Boolean = false,
  val backgroundUri: String? = null,
  val backgroundMediaType: String = "image",
  val backgroundOpacity: Float = 0.3f,
  val videoMuted: Boolean = true,
  val videoLoop: Boolean = true,
  val videoRotation: Int = 0,
  val userBubbleColor: Color? = null,
  val aiBubbleColor: Color? = null,
  val userTextColor: Color? = null,
  val aiTextColor: Color? = null,
  val useCustomBubbles: Boolean = false,
  val userBubbleOpacity: Float = 1f,
  val aiBubbleOpacity: Float = 1f,
  val showThinking: Boolean = true,
  val showStatusTags: Boolean = true,
  val showToolActivity: Boolean = true,
  val uiOpacity: Float = 1f,
  val uiColor: Color? = null,
  val useUiColor: Boolean = false,
  val primaryTextColor: Color? = null,
  val secondaryTextColor: Color? = null,
  val useCustomText: Boolean = false,
)

val LocalThemeState = compositionLocalOf { ThemeState() }

@Composable
fun ThemeProvider(content: @Composable () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ThemePrefs(ctx) }

  val mode = prefs.themeMode.collectAsState(initial = "dark").value
  val sysDark = isSystemInDarkTheme()
  val isDark = when (mode) {
    "light" -> false
    "dark" -> true
    else -> sysDark
  }

  val useCustomColors = prefs.useCustomColors.collectAsState(initial = false).value
  val primaryColor = prefs.primaryColor.collectAsState(initial = null).value?.let { Color(it) }
  val secondaryColor = prefs.secondaryColor.collectAsState(initial = null).value?.let { Color(it) }

  val colorScheme: ColorScheme = if (useCustomColors && primaryColor != null) {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    base.copy(
      primary = primaryColor,
      secondary = secondaryColor ?: primaryColor,
      primaryContainer = primaryColor.copy(alpha = 0.3f),
      secondaryContainer = (secondaryColor ?: primaryColor).copy(alpha = 0.3f),
    )
  } else if (isDark) {
    darkColorScheme()
  } else {
    lightColorScheme()
  }

  val useUiColor = prefs.useUiColor.collectAsState(initial = false).value
  val uiColor = prefs.uiColor.collectAsState(initial = null).value?.let { Color(it) }
  val useCustomText = prefs.useCustomText.collectAsState(initial = false).value
  val pTextColor = prefs.primaryTextColor.collectAsState(initial = null).value?.let { Color(it) }
  val sTextColor = prefs.secondaryTextColor.collectAsState(initial = null).value?.let { Color(it) }
  var finalScheme = colorScheme
  if (useUiColor && uiColor != null) {
    finalScheme = finalScheme.copy(
      surface = uiColor,
      background = uiColor,
      surfaceVariant = uiColor.copy(alpha = 0.7f),
      surfaceContainer = uiColor,
      surfaceContainerHigh = uiColor,
      surfaceContainerLow = uiColor,
    )
  }
  if (useCustomText && pTextColor != null) {
    finalScheme = finalScheme.copy(
      onSurface = pTextColor,
      onBackground = pTextColor,
      onSurfaceVariant = sTextColor ?: pTextColor.copy(alpha = 0.7f),
    )
  }

  val state = ThemeState(
    mode = mode, isDark = isDark,
    primaryColor = primaryColor, secondaryColor = secondaryColor, useCustomColors = useCustomColors,
    useBackground = prefs.useBackground.collectAsState(initial = false).value,
    backgroundUri = prefs.backgroundUri.collectAsState(initial = null).value,
    backgroundMediaType = prefs.backgroundMediaType.collectAsState(initial = "image").value,
    backgroundOpacity = prefs.backgroundOpacity.collectAsState(initial = 0.3f).value,
    videoMuted = prefs.videoMuted.collectAsState(initial = true).value,
    videoLoop = prefs.videoLoop.collectAsState(initial = true).value,
    videoRotation = prefs.videoRotation.collectAsState(initial = 0).value,
    userBubbleColor = prefs.userBubbleColor.collectAsState(initial = null).value?.let { Color(it) },
    aiBubbleColor = prefs.aiBubbleColor.collectAsState(initial = null).value?.let { Color(it) },
    userTextColor = prefs.userTextColor.collectAsState(initial = null).value?.let { Color(it) },
    aiTextColor = prefs.aiTextColor.collectAsState(initial = null).value?.let { Color(it) },
    useCustomBubbles = prefs.useCustomBubbles.collectAsState(initial = false).value,
    userBubbleOpacity = prefs.userBubbleOpacity.collectAsState(initial = 1f).value,
    aiBubbleOpacity = prefs.aiBubbleOpacity.collectAsState(initial = 1f).value,
    showThinking = prefs.showThinking.collectAsState(initial = true).value,
    showStatusTags = prefs.showStatusTags.collectAsState(initial = true).value,
    showToolActivity = prefs.showToolActivity.collectAsState(initial = true).value,
    uiOpacity = prefs.uiOpacity.collectAsState(initial = 1f).value,
    uiColor = prefs.uiColor.collectAsState(initial = null).value?.let { Color(it) },
    useUiColor = prefs.useUiColor.collectAsState(initial = false).value,
    primaryTextColor = prefs.primaryTextColor.collectAsState(initial = null).value?.let { Color(it) },
    secondaryTextColor = prefs.secondaryTextColor.collectAsState(initial = null).value?.let { Color(it) },
    useCustomText = prefs.useCustomText.collectAsState(initial = false).value,
  )

  CompositionLocalProvider(LocalThemeState provides state) {
    MaterialTheme(colorScheme = finalScheme, content = content)
  }
}
