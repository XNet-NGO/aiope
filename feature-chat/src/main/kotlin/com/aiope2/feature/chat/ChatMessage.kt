package com.aiope2.feature.chat

data class ChatMessage(
  val id: String = java.util.UUID.randomUUID().toString(),
  val role: Role = Role.USER,
  val content: String = "",
  val reasoning: String = "",
  val toolCalls: List<String> = emptyList(),  // "🔧 name(args)"
  val toolResults: List<String> = emptyList(), // "result text"
  val timestamp: Long = System.currentTimeMillis()
)

enum class Role(val value: String) {
  USER("user"), ASSISTANT("assistant"), SYSTEM("system"), TOOL("tool");
  companion object {
    fun from(s: String) = entries.firstOrNull { it.value == s } ?: USER
  }
}
