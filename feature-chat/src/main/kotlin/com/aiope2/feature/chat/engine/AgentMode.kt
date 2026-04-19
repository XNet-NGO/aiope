package com.aiope2.feature.chat.engine

enum class AgentMode(val label: String) {
  CHAT("Chat"),
  PLAN("Plan"),
  BUILD("Build"),
  ;

  /** Tools disabled in this mode */
  val disabledTools: Set<String>
    get() = when (this) {
      PLAN -> setOf(
        "run_sh", "run_proot", "write_file", "send_sms", "send_notification",
        "create_event", "delete_event", "set_alarm", "dismiss_alarm", "delete_sms",
        "clipboard_copy", "open_intent", "image_generate",
        "browser_click", "browser_fill", "browser_eval",
      )

      else -> emptySet()
    }

  /** Extra system prompt prefix injected before the agent prompt */
  val systemPrefix: String
    get() = when (this) {
      CHAT -> ""
      PLAN -> """You are in PLAN mode. Analyze the request, explore relevant context, and produce a clear numbered plan. Do NOT execute any changes — only outline what should be done. Use read-only tools (read_file, list_directory, search_web, fetch_url, etc.) to gather information. Output a structured plan with steps the user can review before switching to Build mode."""
      BUILD -> """You are in BUILD mode. Execute tasks autonomously without asking for confirmation. Chain tools as needed to complete the goal. If a step fails, adapt and retry. Report progress as you go. Be thorough and complete the entire task."""
    }
}
