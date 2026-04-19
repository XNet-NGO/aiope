package com.aiope2.core.network

import android.content.Context
import org.json.JSONObject

/**
 * Task-based model routing. Each task can use a different provider profile.
 * Falls back to the active profile if no task-specific override is set.
 */
enum class ModelTask(val id: String, val label: String, val description: String) {
  CHAT("chat", "Chat", "Set by toolbar model selector"),
  SUMMARY("summary", "Summary", "Conversation summarization and compaction"),
  TITLE("title", "Title Generation", "Auto-generate conversation titles"),
  TRANSLATION("translation", "Translation", "Text translation between languages"),
  IMAGE_RECOGNITION("image", "Image Recognition", "Describe and analyze images"),
  AUDIO_RECOGNITION("audio", "Audio Recognition", "Transcribe and understand audio"),
  VIDEO_RECOGNITION("video", "Video Recognition", "Analyze video content"),
  SUBAGENT("subagent", "Subagent (Task Tool)", "Model used by spawned subagents for research and background tasks"),
  IMAGE_GENERATION("image_gen", "Image Generation", "Generate images from text prompts"),
  AUDIO_GENERATION("audio_gen", "Audio Generation", "Generate speech and audio"),
  VIDEO_GENERATION("video_gen", "Video Generation", "Generate video from prompts"),
  ;

  companion object {
    /** Tasks shown in the settings UI (excludes CHAT) */
    val configurable = entries.filter { it != CHAT }
  }
}

data class TaskModelConfig(val taskId: String, val profileId: String? = null, val modelId: String? = null)

class TaskModelStore(context: Context) {
  private val prefs = context.getSharedPreferences("task_models", Context.MODE_PRIVATE)

  fun getTaskConfig(task: ModelTask): TaskModelConfig {
    val json = prefs.getString("task_${task.id}", null) ?: return TaskModelConfig(task.id)
    return try {
      val j = JSONObject(json)
      TaskModelConfig(
        taskId = task.id,
        profileId = j.optString("profileId", "").ifBlank { null },
        modelId = j.optString("modelId", "").ifBlank { null },
      )
    } catch (_: Exception) {
      TaskModelConfig(task.id)
    }
  }

  fun setTaskConfig(task: ModelTask, config: TaskModelConfig) {
    val j = JSONObject().apply {
      config.profileId?.let { put("profileId", it) }
      config.modelId?.let { put("modelId", it) }
    }
    prefs.edit().putString("task_${task.id}", j.toString()).apply()
  }

  fun clearTaskConfig(task: ModelTask) {
    prefs.edit().remove("task_${task.id}").apply()
  }

  /** Resolve which profile + model to use for a given task */
  fun resolve(task: ModelTask, providerStore: Any): Pair<String?, String?> {
    val tc = getTaskConfig(task)
    return tc.profileId to tc.modelId
  }
}
