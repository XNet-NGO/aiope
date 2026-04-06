package com.aiope2.core.network

data class ModelDef(
  val id: String,
  val displayName: String = id
)

/** A fully user-configurable provider profile */
data class ProviderProfile(
  val id: String = java.util.UUID.randomUUID().toString(),
  val name: String = "New Provider",
  val baseUrl: String = "",
  val endpointOverride: String = "", // e.g. /v1/chat/completions
  val apiKey: String = "",
  val selectedModel: String = "",
  val customModel: String = "",
  // Abilities
  val supportsVision: Boolean = false,
  val supportsAudio: Boolean = false,
  val supportsVideo: Boolean = false,
  val supportsTools: Boolean = true,
  val autoDetectAbilities: Boolean = true,
  // Parameters
  val temperature: Float = 0.7f,
  val topP: Float = 1.0f,
  val topK: Int = 0,
  val maxTokens: Int = 4096,
  // Context
  val contextLength: Int = 10, // number of history messages to include
  val systemPrompt: String = "You are a helpful AI assistant.",
  // Cached model list from /models endpoint
  val availableModels: List<String> = emptyList()
) {
  fun effectiveModel(): String = customModel.ifBlank { selectedModel }
  fun effectiveEndpoint(): String = endpointOverride.ifBlank { "/chat/completions" }
}

/** Preconfigured templates — user can edit everything */
object ProviderTemplates {
  val templates = listOf(
    ProviderProfile(id = "t_pollinations", name = "Pollinations", baseUrl = "https://text.pollinations.ai/openai",
      apiKey = "", selectedModel = "openai-fast", supportsTools = true, autoDetectAbilities = false,
      systemPrompt = "You are AIOPE, an AI coding assistant on Android. Use tools when asked to run commands or manage files."),
    ProviderProfile(id = "t_openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1",
      selectedModel = "gpt-4o-mini", supportsVision = true, supportsTools = true),
    ProviderProfile(id = "t_anthropic", name = "Anthropic", baseUrl = "https://api.anthropic.com/v1",
      selectedModel = "claude-sonnet-4-20250514", supportsVision = true, supportsTools = true),
    ProviderProfile(id = "t_google", name = "Google AI Studio", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
      selectedModel = "gemini-2.0-flash", supportsVision = true, supportsTools = true),
    ProviderProfile(id = "t_openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1",
      selectedModel = "deepseek/deepseek-chat-v3-0324:free", supportsVision = true, supportsTools = true),
    ProviderProfile(id = "t_github", name = "GitHub Models", baseUrl = "https://models.github.ai/inference",
      selectedModel = "gpt-4o", supportsVision = true, supportsTools = true),
    ProviderProfile(id = "t_deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1",
      selectedModel = "deepseek-chat", supportsTools = true),
    ProviderProfile(id = "t_groq", name = "Groq", baseUrl = "https://api.groq.com/openai/v1",
      selectedModel = "llama-3.3-70b-versatile", supportsTools = true),
    ProviderProfile(id = "t_ollama", name = "Ollama (local)", baseUrl = "http://localhost:11434/v1",
      selectedModel = "llama3.2", supportsTools = true, autoDetectAbilities = false),
  )
}
