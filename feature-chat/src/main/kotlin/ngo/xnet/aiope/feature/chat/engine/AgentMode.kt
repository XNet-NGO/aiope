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

  /** Tools disabled in this mode */
  val disabledTools: Set<String>
    get() = when (this) {
      PLAN -> setOf(
        "run_sh", "run_proot", "write_file", "send_sms", "send_notification",
        "create_event", "delete_event", "set_alarm", "dismiss_alarm", "delete_sms",
        "clipboard_copy", "open_intent", "image_generate",
        "browser_click", "ssh_exec", "browser_fill", "browser_eval",
      )

      else -> emptySet()
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
