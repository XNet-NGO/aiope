package com.aiope2.feature.chat.settings

import android.content.Context
import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.ModelCacheEntity
import com.aiope2.feature.chat.db.ProviderEntity
import com.aiope2.feature.chat.db.SettingsKvEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderStore @Inject constructor(
  @ApplicationContext private val ctx: Context,
  private val dao: ChatDao,
) {
  init {
    if (getAll().isEmpty()) {
      migrateFromPrefs()
      if (getAll().isEmpty()) seedDefault()
    }
  }

  private fun seedDefault() {
    fun mc(id: String, tools: Boolean? = null, vision: Boolean? = null, audio: Boolean? = null, video: Boolean? = null, ctx: Int = 200_000, reasoning: String? = "auto", compact: Boolean = true) = id to ModelConfig(modelId = id, toolsOverride = tools, visionOverride = vision, audioOverride = audio, videoOverride = video, temperature = 0.6f, reasoningEffort = reasoning, contextTokens = ctx, autoCompact = compact)
    val default = ProviderProfile(
      id = "default_gateway",
      builtinId = "aiope_gateway",
      label = "AIOPE Gateway",
      apiKey = com.aiope2.feature.chat.BuildConfig.GATEWAY_KEY,
      apiBase = "https://inf.xnet.ngo/v1",
      selectedModelId = "google-ai-studio/models-gemma-4-31b-it",
      isActive = true,
      modelConfigs = mapOf(
        mc("cline/minimax-minimax-m2.5", tools = true, ctx = 200_000),
        mc("zen/minimax-m2.5-free", tools = true, ctx = 200_000),
        mc("zen/nemotron-3-super-free", tools = true, vision = false, audio = false, video = false, ctx = 1_000_000),
        mc("zen/big-pickle", tools = true, vision = false, audio = false, video = false),
        mc("cline/z-ai-glm-5", tools = true, vision = false, audio = false, video = false, ctx = 200_000),
        mc("google-ai-studio/models-gemma-4-31b-it", tools = true, vision = true, ctx = 256_000),
        mc("google-ai-studio/models-gemma-4-26b-a4b-it", tools = true, vision = true, ctx = 256_000),
        mc("google-ai-studio/models-gemma-3-27b-it", tools = false, vision = true, audio = false, video = false, ctx = 128_000),
        mc("google-ai-studio/models-gemma-3-12b-it", tools = false, vision = true, audio = false, video = false, ctx = 128_000, reasoning = null),
        mc("google-ai-studio/models-gemma-3-4b-it", tools = false, vision = true, audio = false, video = false, ctx = 128_000, reasoning = null),
        mc("google-ai-studio/models-gemma-3-1b-it", tools = false, vision = false, audio = false, video = false, ctx = 32_000, reasoning = null),
        mc("google-ai-studio/models-gemma-3n-e2b-it", tools = false, vision = true, audio = true, video = false, ctx = 128_000),
        mc("google-ai-studio/models-gemma-3n-e4b-it", tools = false, vision = true, audio = true, video = false, ctx = 128_000),
        mc("openrouter/openrouter-free", vision = true, ctx = 128_000),
        mc("pollinations/openai", tools = true, vision = false, audio = false, video = false, ctx = 128_000, compact = false),
        mc("pollinations/openai-fast", tools = true, vision = false, audio = false, video = false, ctx = 128_000),
      ),
    )
    save(default)
    setActive(default.id)
    fetchModelsAsync(default)
    // Default subagent to the efficient MoE model
    val taskStore = com.aiope2.core.network.TaskModelStore(ctx)
    taskStore.setTaskConfig(com.aiope2.core.network.ModelTask.SUBAGENT, com.aiope2.core.network.TaskModelConfig("subagent", default.id, "google-ai-studio/models-gemma-4-26b-a4b-it"))
  }

  /** One-time migration from SharedPreferences */
  private fun migrateFromPrefs() {
    val prefs = ctx.getSharedPreferences("aiope2_providers", Context.MODE_PRIVATE)
    val raw = prefs.getString("profiles", null) ?: return
    try {
      val arr = JSONArray(raw)
      val activeId = prefs.getString("active_id", "") ?: ""
      for (i in 0 until arr.length()) {
        val p = ProviderProfile.fromJson(arr.getJSONObject(i))
        runBlocking {
          dao.upsertProvider(ProviderEntity(p.id, p.toJson().toString(), p.id == activeId))
        }
        // Migrate model cache
        val cacheRaw = prefs.getString("mcache_${p.builtinId}", null)
        val cacheTs = prefs.getLong("mcache_ts_${p.builtinId}", 0)
        if (cacheRaw != null) {
          runBlocking { dao.upsertModelCache(ModelCacheEntity(p.builtinId, cacheRaw, cacheTs)) }
        }
      }
      // Migrate geoapify key
      val geoKey = prefs.getString("geoapify_key", null)
      if (!geoKey.isNullOrBlank()) {
        runBlocking { dao.upsertSetting(SettingsKvEntity("geoapify_key", geoKey)) }
      }
      prefs.edit().clear().apply()
    } catch (_: Exception) {}
  }

  fun getAll(): List<ProviderProfile> = runBlocking {
    dao.getProviders().mapNotNull { runCatching { ProviderProfile.fromJson(JSONObject(it.json)) }.getOrNull() }
  }

  fun getActive(): ProviderProfile = runBlocking {
    dao.getActiveProvider()?.let { runCatching { ProviderProfile.fromJson(JSONObject(it.json)) }.getOrNull() }
  } ?: getAll().firstOrNull()
    ?: ProviderProfile(builtinId = "aiope_gateway", label = "AIOPE Gateway", apiKey = com.aiope2.feature.chat.BuildConfig.GATEWAY_KEY, selectedModelId = "google-ai-studio/models-gemma-4-31b-it")

  fun getById(id: String): ProviderProfile? = getAll().firstOrNull { it.id == id }

  fun save(profile: ProviderProfile) = runBlocking {
    val existing = dao.getActiveProvider()
    val isActive = existing?.id == profile.id && existing.isActive
    dao.upsertProvider(ProviderEntity(profile.id, profile.toJson().toString(), isActive))
  }

  fun delete(id: String) = runBlocking { dao.deleteProvider(id) }

  fun setActive(id: String) = runBlocking {
    dao.clearActiveProvider()
    dao.setActiveProvider(id)
  }

  fun saveModelCache(builtinId: String, models: List<ModelDef>) {
    val arr = JSONArray()
    models.forEach { m ->
      arr.put(
        JSONObject().apply {
          put("id", m.id)
          put("name", m.displayName)
          put("ctx", m.contextWindow)
          put("tools", m.supportsTools)
          put("vision", m.supportsVision)
          put("reasoning", m.supportsReasoning)
          put("maxOutput", m.maxOutput)
          if (m.outputModality != "text") put("outputModality", m.outputModality)
          if (m.family.isNotBlank()) put("family", m.family)
        },
      )
    }
    runBlocking { dao.upsertModelCache(ModelCacheEntity(builtinId, arr.toString())) }
  }

  fun getModelCache(builtinId: String): List<ModelDef>? = runBlocking {
    val e = dao.getModelCache(builtinId) ?: return@runBlocking null
    if (System.currentTimeMillis() - e.cachedAt > 24 * 60 * 60 * 1000) return@runBlocking null
    parseModelCache(e.json)
  }

  fun getModelCacheStale(builtinId: String): List<ModelDef>? = runBlocking {
    dao.getModelCache(builtinId)?.let { parseModelCache(it.json) }
  }

  private fun parseModelCache(raw: String): List<ModelDef>? = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).map {
      val o = arr.getJSONObject(it)
      ModelDef(
        o.getString("id"), o.optString("name", o.getString("id")), o.optInt("ctx"),
        o.optBoolean("tools", true), o.optBoolean("vision"), supportsReasoning = o.optBoolean("reasoning"),
        outputModality = o.optString("outputModality", "text"), maxOutput = o.optInt("maxOutput"), family = o.optString("family", ""),
      )
    }
  }.getOrNull()

  fun getGeoapifyKey(): String = runBlocking { dao.getSetting("geoapify_key") ?: "" }
  fun setGeoapifyKey(key: String) = runBlocking { dao.upsertSetting(SettingsKvEntity("geoapify_key", key)) }

  private fun fetchModelsAsync(profile: ProviderProfile) {
    Thread {
      try {
        val base = profile.effectiveApiBase().trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val data = JSONObject(body).optJSONArray("data") ?: return@Thread
        val models = (0 until data.length()).map {
          val o = data.getJSONObject(it)
          val inputMods = o.optJSONObject("modalities")?.optJSONArray("input")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
          ModelDef(
            o.getString("id"),
            o.optString("display_name", "").ifBlank { o.optString("name", o.getString("id")) },
            o.optInt("context_window"),
            supportsTools = o.optBoolean("tool_call", true),
            supportsVision = "image" in inputMods || o.optBoolean("attachment"),
            supportsReasoning = o.optBoolean("reasoning"),
            maxOutput = o.optInt("max_output"),
            family = o.optString("family", ""),
          )
        }.sortedBy { it.id }
        if (models.isNotEmpty()) saveModelCache(profile.builtinId, models)
      } catch (_: Exception) {}
    }.start()
  }
}
