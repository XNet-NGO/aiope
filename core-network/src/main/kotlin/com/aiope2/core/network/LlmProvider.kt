package com.aiope2.core.network

data class LlmProvider(
  val name: String,
  val baseUrl: String,
  val apiKey: String = "",
  val defaultModel: String = "",
  val path: String = "/chat/completions",
  val enabled: Boolean = true,
  val displayId: String = "",
  val staticModels: List<String> = emptyList()
)
