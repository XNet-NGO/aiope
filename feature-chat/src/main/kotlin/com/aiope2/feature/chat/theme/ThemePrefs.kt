package com.aiope2.feature.chat.theme

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")

class ThemePrefs(private val ctx: Context) {
  companion object {
    // Theme mode
    val THEME_MODE = stringPreferencesKey("theme_mode") // "light", "dark", "system"
    val PRIMARY_COLOR = intPreferencesKey("primary_color")
    val SECONDARY_COLOR = intPreferencesKey("secondary_color")
    val USE_CUSTOM_COLORS = booleanPreferencesKey("use_custom_colors")

    // Background
    val USE_BACKGROUND = booleanPreferencesKey("use_background")
    val BACKGROUND_URI = stringPreferencesKey("background_uri")
    val BACKGROUND_MEDIA_TYPE = stringPreferencesKey("background_media_type") // "image", "video"
    val BACKGROUND_OPACITY = floatPreferencesKey("background_opacity")
    val VIDEO_MUTED = booleanPreferencesKey("video_muted")
    val VIDEO_LOOP = booleanPreferencesKey("video_loop")
    val VIDEO_ROTATION = intPreferencesKey("video_rotation") // 0, 90, 180, 270

    // Bubble colors
    val USER_BUBBLE_COLOR = intPreferencesKey("user_bubble_color")
    val AI_BUBBLE_COLOR = intPreferencesKey("ai_bubble_color")
    val USER_TEXT_COLOR = intPreferencesKey("user_text_color")
    val AI_TEXT_COLOR = intPreferencesKey("ai_text_color")
    val USE_CUSTOM_BUBBLES = booleanPreferencesKey("use_custom_bubbles")
    val USER_BUBBLE_OPACITY = floatPreferencesKey("user_bubble_opacity")
    val AI_BUBBLE_OPACITY = floatPreferencesKey("ai_bubble_opacity")

    // Display toggles
    val SHOW_THINKING = booleanPreferencesKey("show_thinking")
    val SHOW_STATUS_TAGS = booleanPreferencesKey("show_status_tags")
    val SHOW_TOOL_ACTIVITY = booleanPreferencesKey("show_tool_activity")
  }

  private val ds = ctx.themeDataStore

  // Flows
  val themeMode: Flow<String> = ds.data.map { it[THEME_MODE] ?: "dark" }
  val primaryColor: Flow<Int?> = ds.data.map { it[PRIMARY_COLOR] }
  val secondaryColor: Flow<Int?> = ds.data.map { it[SECONDARY_COLOR] }
  val useCustomColors: Flow<Boolean> = ds.data.map { it[USE_CUSTOM_COLORS] ?: false }

  val useBackground: Flow<Boolean> = ds.data.map { it[USE_BACKGROUND] ?: false }
  val backgroundUri: Flow<String?> = ds.data.map { it[BACKGROUND_URI] }
  val backgroundMediaType: Flow<String> = ds.data.map { it[BACKGROUND_MEDIA_TYPE] ?: "image" }
  val backgroundOpacity: Flow<Float> = ds.data.map { it[BACKGROUND_OPACITY] ?: 0.3f }
  val videoMuted: Flow<Boolean> = ds.data.map { it[VIDEO_MUTED] ?: true }
  val videoLoop: Flow<Boolean> = ds.data.map { it[VIDEO_LOOP] ?: true }
  val videoRotation: Flow<Int> = ds.data.map { it[VIDEO_ROTATION] ?: 0 }

  val userBubbleColor: Flow<Int?> = ds.data.map { it[USER_BUBBLE_COLOR] }
  val aiBubbleColor: Flow<Int?> = ds.data.map { it[AI_BUBBLE_COLOR] }
  val userTextColor: Flow<Int?> = ds.data.map { it[USER_TEXT_COLOR] }
  val aiTextColor: Flow<Int?> = ds.data.map { it[AI_TEXT_COLOR] }
  val useCustomBubbles: Flow<Boolean> = ds.data.map { it[USE_CUSTOM_BUBBLES] ?: false }
  val userBubbleOpacity: Flow<Float> = ds.data.map { it[USER_BUBBLE_OPACITY] ?: 1f }
  val aiBubbleOpacity: Flow<Float> = ds.data.map { it[AI_BUBBLE_OPACITY] ?: 1f }

  val showThinking: Flow<Boolean> = ds.data.map { it[SHOW_THINKING] ?: true }
  val showStatusTags: Flow<Boolean> = ds.data.map { it[SHOW_STATUS_TAGS] ?: true }
  val showToolActivity: Flow<Boolean> = ds.data.map { it[SHOW_TOOL_ACTIVITY] ?: true }

  suspend fun <T> set(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
    ds.edit { it[key] = value }
  }

  suspend fun <T> remove(key: androidx.datastore.preferences.core.Preferences.Key<T>) {
    ds.edit { it.remove(key) }
  }
}
