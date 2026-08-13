package ngo.xnet.aiope.feature.chat.engine

/** A single chunk from the streaming orchestrator */
data class ChatStreamChunk(
  val content: String = "",
  val reasoning: String? = null,
  val isDone: Boolean = false,
  val toolCalls: List<ToolCallInfo>? = null,
  val toolResults: List<ToolResultInfo>? = null,
  val error: String? = null,
  /** When non-null, replace the entire accumulated content with this value (strips tool markup) */
  val contentReplace: String? = null,
  /** Token usage from the final SSE chunk */
  val usage: UsageInfo? = null,
)

data class UsageInfo(val inputTokens: Int = 0, val outputTokens: Int = 0)

data class ToolCallInfo(val id: String, val name: String, val arguments: Map<String, Any?>)
data class ToolResultInfo(val id: String, val name: String, val arguments: Map<String, Any?>, val result: String)
