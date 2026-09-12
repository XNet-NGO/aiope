package ngo.xnet.aiope.feature.chat.engine

import ngo.xnet.aiope.core.network.ProviderCategory

enum class AgentMode(val label: String) {
  CHAT("Chat"),
  PLAN("Plan"),
  BUILD("Build"),
  MEDIA("Media"),
  ;

  /** Which provider category this mode operates against (drives the model picker + generation). */
  val providerCategory: ProviderCategory
    get() = when (this) {
      MEDIA -> ProviderCategory.MEDIA
      else -> ProviderCategory.TEXT
    }

  /** When true, NO tools are exposed to the model in this mode. */
  val disablesAllTools: Boolean
    get() = this == MEDIA

  /** Stable key used for per-mode tool-enablement settings. */
  val key: String get() = name.lowercase()

  companion object {
    /**
     * DEFAULT per-mode tool enablement — used ONLY to seed the settings the
     * first time, and as the fallback when a tool has no explicit per-mode
     * setting. Users override these per-tool-per-mode in Settings → Tools.
     *
     *   CHAT  — everyday phone assistant: reads, search, browsing, memory,
     *           image gen, and light non-destructive device actions.
     *   PLAN  — CHAT + research/authoring/task-setting (file writes, http,
     *           rag_index, todo, scheduling, orchestrate).
     *   BUILD — everything (default-on for every tool).
     *   MEDIA — nothing.
     */
    val CHAT_DEFAULT: Set<String> = setOf(
      "read_file", "list_directory", "get_location", "device_info", "datetime_now",
      "fetch_url", "query_data", "search_location", "search_web", "search_images",
      "browser_navigate", "browser_content", "browser_elements", "browser_click",
      "browser_fill", "browser_eval", "browser_back", "browser_scroll",
      "browser_open", "browser_close", "browser_maximize",
      "memory_store", "memory_recall", "memory_forget", "rag_search",
      "analyze_image", "image_generate",
      "read_calendar", "create_event", "delete_event", "set_alarm", "dismiss_alarm",
      "read_contacts", "send_notification", "clipboard_copy", "clipboard_read",
      "read_sms", "send_sms", "delete_sms", "media_control", "open_intent",
    )

    val PLAN_EXTRA: Set<String> = setOf(
      "write_file", "edit_file", "search_files", "http_request",
      "rag_index", "todo_write", "todo_read",
      "schedule_task", "cancel_schedule", "list_schedules", "orchestrate",
    )

    val PLAN_DEFAULT: Set<String> = CHAT_DEFAULT + PLAN_EXTRA

    /** Whether a tool defaults to ON in the given mode (when no explicit setting). */
    fun toolDefaultEnabled(mode: AgentMode, toolId: String): Boolean = when (mode) {
      BUILD -> true
      MEDIA -> false
      CHAT -> toolId in CHAT_DEFAULT
      PLAN -> toolId in PLAN_DEFAULT
    }
  }

  /** Extra system prompt prefix injected before the agent prompt */
  val systemPrefix: String
    get() = when (this) {
      CHAT -> ""
      PLAN -> """You are in PLAN mode. Analyze the request, explore relevant context, and produce a clear numbered plan. Do NOT execute any changes — only outline what should be done. Use read-only tools (read_file, list_directory, search_web, fetch_url, etc.) to gather information. Output a structured plan with steps the user can review before switching to Build mode."""
      BUILD -> "Execute autonomously. Do not ask for confirmation. Chain tools to complete the goal. If a step fails, adapt. Report progress briefly."
      MEDIA -> "You are in MEDIA mode. The request is a description of visual media to generate. Respond by producing the media directly via the media generation model — no tools are available in this mode. Refine and iterate on the prompt when the user asks for changes."
    }
}
