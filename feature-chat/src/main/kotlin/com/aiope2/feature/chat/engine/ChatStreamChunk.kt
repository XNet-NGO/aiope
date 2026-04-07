package com.aiope2.feature.chat.engine

/** A single chunk from the streaming orchestrator */
data class ChatStreamChunk(
  val content: String = "",
  val reasoning: String? = null,
  val isDone: Boolean = false,
  val toolCalls: List<ToolCallInfo>? = null,
  val toolResults: List<ToolResultInfo>? = null,
  val error: String? = null
)

data class ToolCallInfo(val id: String, val name: String, val arguments: Map<String, Any?>)
data class ToolResultInfo(val id: String, val name: String, val arguments: Map<String, Any?>, val result: String)
