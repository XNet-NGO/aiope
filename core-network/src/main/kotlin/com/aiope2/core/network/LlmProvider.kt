package com.aiope2.core.network

import org.json.JSONObject

/** Per-model configuration — all settings except provider connection live here */
data class ModelConfig(
  val modelId: String,
  val endpointOverride: String = "",
  // Abilities (null = auto-detect)
  val toolsOverride: Boolean? = null,
  val visionOverride: Boolean? = null,
  val audioOverride: Boolean? = null,
  val videoOverride: Boolean? = null,
  // Parameters (null = off/omit)
  val temperature: Float? = 0.6f,
  val topP: Float? = null,
  val topK: Int? = null,
  val maxTokens: Int? = null,
  // Reasoning (null = off, "auto", "low", "medium", "high")
  val reasoningEffort: String? = null,
  // Context
  val contextTokens: Int = 10_000_000,
  val autoCompact: Boolean = false,
  val systemPromptOverride: String? = null
) {
  fun toJson() = JSONObject().apply {
    put("modelId", modelId)
    if (endpointOverride.isNotBlank()) put("endpointOverride", endpointOverride)
    toolsOverride?.let { put("toolsOverride", it) }
    visionOverride?.let { put("visionOverride", it) }
    audioOverride?.let { put("audioOverride", it) }
    videoOverride?.let { put("videoOverride", it) }
    temperature?.let { put("temperature", it.toDouble()) }
    topP?.let { put("topP", it.toDouble()) }
    topK?.let { put("topK", it) }
    maxTokens?.let { put("maxTokens", it) }
    reasoningEffort?.let { put("reasoningEffort", it) }
    put("contextTokens", contextTokens)
    put("autoCompact", autoCompact)
    systemPromptOverride?.let { put("systemPromptOverride", it) }
  }
  companion object {
    fun fromJson(j: JSONObject) = ModelConfig(
      modelId = j.getString("modelId"),
      endpointOverride = j.optString("endpointOverride", ""),
      toolsOverride = if (j.has("toolsOverride")) j.optBoolean("toolsOverride") else null,
      visionOverride = if (j.has("visionOverride")) j.optBoolean("visionOverride") else null,
      audioOverride = if (j.has("audioOverride")) j.optBoolean("audioOverride") else null,
      videoOverride = if (j.has("videoOverride")) j.optBoolean("videoOverride") else null,
      temperature = if (j.has("temperature")) j.getDouble("temperature").toFloat() else 0.6f,
      topP = if (j.has("topP")) j.getDouble("topP").toFloat() else null,
      topK = if (j.has("topK")) j.getInt("topK") else null,
      maxTokens = if (j.has("maxTokens")) j.getInt("maxTokens") else null,
      reasoningEffort = if (j.has("reasoningEffort")) j.getString("reasoningEffort") else null,
      contextTokens = j.optInt("contextTokens", 10_000_000),
      autoCompact = j.optBoolean("autoCompact", false),
      systemPromptOverride = if (j.has("systemPromptOverride")) j.getString("systemPromptOverride") else null,
    )
  }
}

data class ModelDef(
  val id: String,
  val displayName: String = id,
  val contextWindow: Int = 0,
  val supportsTools: Boolean = true,
  val supportsVision: Boolean = false,
  val supportsAudio: Boolean = false,
  val supportsVideo: Boolean = false
)

/** Provider profile — only connection info + selected model + per-model configs */
data class ProviderProfile(
  val id: String = java.util.UUID.randomUUID().toString(),
  val builtinId: String = "custom",
  val label: String = "",
  val apiKey: String = "",
  val apiBase: String = "",
  val selectedModelId: String = "",
  val isActive: Boolean = false,
  val modelConfigs: Map<String, ModelConfig> = emptyMap()
) {
  fun effectiveModel(): String = selectedModelId
  fun effectiveApiBase(): String = apiBase.ifBlank { ProviderTemplates.byId[builtinId]?.apiBase ?: "" }

  /** Get or create config for the selected model */
  fun activeModelConfig(): ModelConfig =
    modelConfigs[selectedModelId] ?: ModelConfig(modelId = selectedModelId)

  fun toJson() = JSONObject().apply {
    put("id", id); put("builtinId", builtinId); put("label", label)
    put("apiKey", apiKey); put("apiBase", apiBase)
    put("selectedModelId", selectedModelId); put("isActive", isActive)
    if (modelConfigs.isNotEmpty()) {
      val mc = JSONObject()
      modelConfigs.forEach { (k, v) -> mc.put(k, v.toJson()) }
      put("modelConfigs", mc)
    }
  }

  companion object {
    fun fromJson(j: JSONObject): ProviderProfile {
      val mc = j.optJSONObject("modelConfigs")?.let { obj ->
        val map = mutableMapOf<String, ModelConfig>()
        obj.keys().forEach { k -> map[k] = ModelConfig.fromJson(obj.getJSONObject(k)) }
        map
      } ?: emptyMap()
      return ProviderProfile(
        id = j.optString("id"), builtinId = j.optString("builtinId", "custom"),
        label = j.optString("label"), apiKey = j.optString("apiKey"),
        apiBase = j.optString("apiBase"), selectedModelId = j.optString("selectedModelId"),
        isActive = j.optBoolean("isActive"), modelConfigs = mc
      )
    }
  }
}

data class BuiltinProvider(
  val id: String,
  val displayName: String,
  val icon: String,
  val apiBase: String? = null,
  val apiKeyHint: String = "",
  val requiresApiKey: Boolean = true,
  val defaultModels: List<ModelDef> = emptyList()
)

object ProviderTemplates {
  val ALL = listOf(
    BuiltinProvider("pollinations", "Pollinations", "🌸", "https://text.pollinations.ai/openai", "(no key)", false, listOf(
      ModelDef("openai-fast", "GPT-OSS Fast", 32_768), ModelDef("openai", "GPT-OSS", 32_768), ModelDef("openai-large", "GPT-OSS Large", 32_768))),
    BuiltinProvider("aiope_gateway", "AIOPE Gateway", "A", "https://inf.xnet.ngo/v1", apiKeyHint = "Gateway key", defaultModels = listOf(
      ModelDef("llama/qwen3.5-2b-heretic", "Qwen 3.5 2B Heretic", 32_768))),
    BuiltinProvider("openai", "OpenAI", "🤖", apiKeyHint = "sk-…", defaultModels = listOf(
      ModelDef("gpt-4o", "GPT-4o", 128_000, true, true), ModelDef("gpt-4o-mini", "GPT-4o mini", 128_000, true, true), ModelDef("o4-mini", "o4-mini", 200_000, true, true))),
    BuiltinProvider("anthropic", "Anthropic", "🧠", apiKeyHint = "sk-ant-…", defaultModels = listOf(
      ModelDef("claude-sonnet-4-20250514", "Claude Sonnet 4", 200_000, true, true), ModelDef("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 200_000, true, true))),
    BuiltinProvider("google_ai_studio", "Google AI Studio", "✨", "https://generativelanguage.googleapis.com/v1beta/openai", "AIza…", defaultModels = listOf(
      ModelDef("gemini-2.0-flash", "Gemini 2.0 Flash", 1_000_000, true, true))),
    BuiltinProvider("deepseek", "DeepSeek", "🔍", apiKeyHint = "sk-…", defaultModels = listOf(
      ModelDef("deepseek-chat", "DeepSeek V3", 64_000), ModelDef("deepseek-reasoner", "DeepSeek R1", 64_000, false))),
    BuiltinProvider("openrouter", "OpenRouter", "🔀", "https://openrouter.ai/api/v1", "sk-or-…", defaultModels = listOf(
      ModelDef("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash (free)", 1_000_000), ModelDef("deepseek/deepseek-chat-v3-0324:free", "DeepSeek V3 (free)", 64_000))),
    BuiltinProvider("github_models", "GitHub Models", "🐙", "https://models.github.ai/inference", "github_pat_…", defaultModels = listOf(
      ModelDef("gpt-4o", "GPT-4o", 131_072, true, true), ModelDef("DeepSeek-R1", "DeepSeek R1", 128_000, false))),
    BuiltinProvider("groq", "Groq", "⚡", "https://api.groq.com/openai/v1", "gsk_…", defaultModels = listOf(
      ModelDef("llama-3.3-70b-versatile", "Llama 3.3 70B", 128_000))),
    BuiltinProvider("ollama", "Ollama (local)", "🦙", "http://localhost:11434/v1", "(no key)", false, defaultModels = listOf(
      ModelDef("llama3.2", "Llama 3.2", 128_000), ModelDef("qwen2.5", "Qwen 2.5", 128_000))),
    BuiltinProvider("custom", "Custom", "⚙️", apiKeyHint = "API key", requiresApiKey = false),
  )
  val byId = ALL.associateBy { it.id }
}
