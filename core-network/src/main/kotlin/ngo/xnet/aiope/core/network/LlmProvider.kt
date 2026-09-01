package ngo.xnet.aiope.core.network

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
  val systemPromptOverride: String? = null,
  // Truncation limits
  val shellOutputLimit: Int = 12000,
  val fetchLimit: Int = 30000,
  val fileReadLimit: Int = 50000,
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
    if (shellOutputLimit != 12000) put("shellOutputLimit", shellOutputLimit)
    if (fetchLimit != 30000) put("fetchLimit", fetchLimit)
    if (fileReadLimit != 50000) put("fileReadLimit", fileReadLimit)
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
      systemPromptOverride = if (j.has(
          "systemPromptOverride",
        )
      ) {
        j.getString("systemPromptOverride")
      } else {
        null
      },
      shellOutputLimit = j.optInt("shellOutputLimit", 12000),
      fetchLimit = j.optInt("fetchLimit", 30000),
      fileReadLimit = j.optInt("fileReadLimit", 50000),
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
  val supportsVideo: Boolean = false,
  val supportsReasoning: Boolean = false,
  val outputModality: String = "text",
  val maxOutput: Int = 0,
  val family: String = "",
  // Realtime voice
  val useStreaming: Boolean = false,
  val audioInputType: String = "NONE",
  val sampleRate: Int = 16000,
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
  val modelConfigs: Map<String, ModelConfig> = emptyMap(),
) {
  fun effectiveModel(): String = selectedModelId
  fun effectiveApiBase(): String = apiBase.ifBlank {
    ProviderTemplates.byId[builtinId]?.apiBase
      ?: ""
  }

  /** Get or create config for the selected model */
  fun activeModelConfig(): ModelConfig = modelConfigs[selectedModelId] ?: ModelConfig(modelId = selectedModelId)

  fun toJson() = JSONObject().apply {
    put("id", id)
    put("builtinId", builtinId)
    put("label", label)
    put("apiKey", apiKey)
    put("apiBase", apiBase)
    put("selectedModelId", selectedModelId)
    put("isActive", isActive)
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
        id = j.optString("id"),
        builtinId = j.optString("builtinId", "custom"),
        label = j.optString("label"),
        apiKey = j.optString("apiKey").trim(),
        apiBase = j.optString("apiBase"),
        selectedModelId = j.optString("selectedModelId"),
        isActive = j.optBoolean("isActive"),
        modelConfigs = mc,
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
  val defaultModels: List<ModelDef> = emptyList(),
)

object ProviderTemplates {
  val ALL = listOf(
    BuiltinProvider(
      "aiope_gateway",
      "AIOPE Gateway",
      "",
      "https://inf.xnet.ngo/v1",
      apiKeyHint = "Gateway key",
      defaultModels = listOf(
        ModelDef("google-ai-studio/models-gemma-4-31b-it", "Gemma 4 31B IT", 256_000),
        ModelDef(
          "cf-image/flux-1-schnell",
          "FLUX Schnell",
          outputModality = "image",
          supportsTools = false,
        ),
        ModelDef(
          "cf-image/flux-2-dev",
          "FLUX 2 Dev",
          outputModality = "image",
          supportsTools = false,
        ),
        ModelDef(
          "cf-image/sdxl-lightning",
          "SDXL Lightning",
          outputModality = "image",
          supportsTools = false,
        ),
        ModelDef(
          "cf-image/dreamshaper-8",
          "Dreamshaper 8",
          outputModality = "image",
          supportsTools = false,
        ),
        ModelDef(
          "cf-image/leonardo-phoenix",
          "Leonardo Phoenix",
          outputModality = "image",
          supportsTools = false,
        ),
        ModelDef(
          "google/gemini-2.5-flash-native-audio",
          "Gemini 2.5 Flash (Voice)",
          contextWindow = 131072,
          supportsAudio = true,
          useStreaming = true,
          audioInputType = "LINEAR_PCM",
          sampleRate = 16000,
        ),
        ModelDef(
          "google-ai-studio/gemini-3.1-flash-live-preview",
          "Gemini 3 Flash Live",
          contextWindow = 131072,
          supportsAudio = true,
          useStreaming = true,
          audioInputType = "LINEAR_PCM",
          sampleRate = 16000,
        ),
      ),
    ),
    BuiltinProvider("custom", "Custom", "", apiKeyHint = "API key", requiresApiKey = false),
    BuiltinProvider(
      "cloudflare_ai",
      "Cloudflare Workers AI",
      "",
      "https://api.cloudflare.com/client/v4/accounts/e9f193b23b2f822c3425a000357c543a/ai/v1",
      apiKeyHint = "Cloudflare API token",
      defaultModels = listOf(
        ModelDef("@cf/meta/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B", 131_072),
        ModelDef("@cf/meta/llama-3.3-70b-instruct-fp8-fast", "Llama 3.3 70B", 131_072),
        ModelDef("@cf/meta/llama-3.1-8b-instruct", "Llama 3.1 8B", 131_072),
        ModelDef("@cf/deepseek-ai/deepseek-r1-distill-qwen-32b", "DeepSeek R1 32B", 131_072),
        ModelDef("@cf/qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B", 131_072),
        ModelDef("@cf/google/gemma-7b-it-lora", "Gemma 7B", 8192),
        ModelDef("@cf/mistralai/mistral-7b-instruct-v0.2-lora", "Mistral 7B", 32_768),
      ),
    ),
    BuiltinProvider(
      "google_ai_studio",
      "Google AI Studio",
      "",
      "https://generativelanguage.googleapis.com/v1beta/openai",
      apiKeyHint = "Google AI API key",
      defaultModels = listOf(
        ModelDef("models/gemini-2.5-flash", "Gemini 2.5 Flash", 1_000_000),
        ModelDef("models/gemini-2.5-pro", "Gemini 2.5 Pro", 1_000_000),
        ModelDef("models/gemini-3.5-flash", "Gemini 3.5 Flash", 1_000_000),
        ModelDef("models/gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", 1_000_000),
        ModelDef("models/gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", 1_000_000),
        ModelDef("models/gemma-4-31b-it", "Gemma 4 31B", 256_000),
        ModelDef("models/gemma-4-26b-a4b-it", "Gemma 4 26B", 256_000),
        ModelDef(
          "models/gemini-3.1-flash-live-preview",
          "Gemini 3.1 Flash Live",
          contextWindow = 131_072,
          supportsAudio = true,
          useStreaming = true,
          audioInputType = "LINEAR_PCM",
          sampleRate = 16000,
        ),
      ),
    ),
  )
  val byId = ALL.associateBy { it.id }
}
