package com.aiope2.feature.chat.settings

import android.content.Context
import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderStore @Inject constructor(@ApplicationContext ctx: Context) {
  private val prefs = ctx.getSharedPreferences("aiope2_providers", Context.MODE_PRIVATE)

  init {
    if (getAll().isEmpty()) {
      val default = ProviderProfile(
        id = "default_gateway",
        builtinId = "aiope_gateway",
        label = "AIOPE Gateway",
        apiKey = "aiope-app-383cd9ac-c1bf-487b-9ba1-e0e454ad86fc",
        apiBase = "https://inf.xnet.ngo/v1",
        selectedModelId = "llama/qwen3.5-2b-heretic",
        isActive = true,
        modelConfigs = mapOf(
          "llama/qwen3.5-2b-heretic" to ModelConfig(
            modelId = "llama/qwen3.5-2b-heretic",
            toolsOverride = true,
            visionOverride = true,
            reasoningEffort = "auto",
            contextTokens = 128_000,
            autoCompact = true,
            maxTokens = 16_000,
          ),
        ),
      )
      save(default)
      setActive(default.id)
      // Auto-fetch models in background
      Thread {
        try {
          val base = default.effectiveApiBase().trimEnd('/')
          val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
          val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
          conn.setRequestProperty("Authorization", "Bearer ${default.apiKey}")
          conn.connectTimeout = 10_000
          conn.readTimeout = 10_000
          val body = conn.inputStream.bufferedReader().readText()
          conn.disconnect()
          val data = org.json.JSONObject(body).optJSONArray("data") ?: return@Thread
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
          if (models.isNotEmpty()) saveModelCache(default.builtinId, models)
        } catch (_: Exception) {}
      }.start()
    }
  }

  fun getAll(): List<ProviderProfile> {
    val raw = prefs.getString("profiles", "[]") ?: "[]"
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { runCatching { ProviderProfile.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun getActive(): ProviderProfile = getAll().firstOrNull { it.id == prefs.getString("active_id", "") } ?: getAll().firstOrNull()
    ?: ProviderProfile(builtinId = "aiope_gateway", label = "AIOPE Gateway", apiKey = "aiope-app-383cd9ac-c1bf-487b-9ba1-e0e454ad86fc", selectedModelId = "llama/qwen3.5-2b-heretic")

  fun getById(id: String): ProviderProfile? = getAll().firstOrNull { it.id == id }

  fun save(profile: ProviderProfile) {
    val list = getAll().toMutableList()
    val idx = list.indexOfFirst { it.id == profile.id }
    if (idx >= 0) list[idx] = profile else list.add(profile)
    persist(list)
  }

  fun delete(id: String) {
    persist(getAll().filter { it.id != id })
    if (prefs.getString("active_id", "") == id) prefs.edit().remove("active_id").apply()
  }

  fun setActive(id: String) {
    prefs.edit().putString("active_id", id).apply()
  }

  // Model cache with TTL
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
    prefs.edit().putString("mcache_$builtinId", arr.toString()).putLong("mcache_ts_$builtinId", System.currentTimeMillis()).apply()
  }

  fun getModelCache(builtinId: String): List<ModelDef>? {
    val raw = prefs.getString("mcache_$builtinId", null) ?: return null
    val ts = prefs.getLong("mcache_ts_$builtinId", 0)
    if (System.currentTimeMillis() - ts > 24 * 60 * 60 * 1000) return null
    return parseModelCache(raw)
  }

  fun getModelCacheStale(builtinId: String): List<ModelDef>? = prefs.getString("mcache_$builtinId", null)?.let { parseModelCache(it) }

  private fun parseModelCache(raw: String): List<ModelDef>? = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).map {
      val o = arr.getJSONObject(it)
      ModelDef(
        o.getString(
          "id",
        ),
        o.optString(
          "name",
          o.getString("id"),
        ),
        o.optInt(
          "ctx",
        ),
        o.optBoolean(
          "tools",
          true,
        ),
        o.optBoolean(
          "vision",
        ),
        supportsReasoning = o.optBoolean("reasoning"), outputModality = o.optString("outputModality", "text"), maxOutput = o.optInt("maxOutput"), family = o.optString("family", ""),
      )
    }
  }.getOrNull()

  private fun persist(list: List<ProviderProfile>) {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    prefs.edit().putString("profiles", arr.toString()).apply()
  }

  fun getGeoapifyKey(): String = prefs.getString("geoapify_key", "") ?: ""
  fun setGeoapifyKey(key: String) = prefs.edit().putString("geoapify_key", key).apply()
}
