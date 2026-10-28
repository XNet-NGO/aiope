package ngo.xnet.aiope.feature.chat.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ngo.xnet.aiope.core.network.ModelConfig
import ngo.xnet.aiope.core.network.ModelDef
import ngo.xnet.aiope.core.network.ProviderCategory
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.core.network.ProviderTemplates
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.db.ModelCacheEntity
import ngo.xnet.aiope.feature.chat.db.ProviderEntity
import ngo.xnet.aiope.feature.chat.db.SettingsKvEntity
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_ACTIVE_MEDIA = "active_media_provider"

@Singleton
class ProviderStore @Inject constructor(
  @ApplicationContext private val ctx: Context,
  private val dao: ChatDao,
) {
  init {
    migrateRemoveGateway()
    if (getAll().isEmpty()) {
      migrateFromPrefs()
      if (getAll().isEmpty()) seedDefault()
    }
    seedTaskDefaults()
    ensureLiveModel()
    seedNovitaByok()
  }

  /**
   * One-time migration (BYOK): the AIOPE Gateway is deprecated and shut down. Remove any
   * previously-seeded gateway providers from existing installs, clear active slots that pointed at
   * them, and drop task-model configs that referenced them, so stale rows don't keep hitting a
   * dead endpoint. New installs never seed the gateway (see seedDefault), so this is a no-op there.
   * Guarded by a settings flag so it runs exactly once.
   */
  private fun migrateRemoveGateway() = runBlocking(Dispatchers.IO) {
    val migratedKey = "migrated_remove_gateway_v1"
    if (dao.getSetting(migratedKey)?.toBooleanStrictOrNull() == true) return@runBlocking
    val gatewayIds = getAll().filter {
      it.builtinId == "aiope_gateway" ||
        it.apiBase.contains("inf.xnet.ngo", ignoreCase = true)
    }.map { it.id }.toSet()
    if (gatewayIds.isNotEmpty()) {
      // Clear active text slot if it pointed at a gateway provider.
      val activeId = dao.getActiveProvider()?.let { runCatching { ProviderProfile.fromJson(JSONObject(it.json)).id }.getOrNull() }
      if (activeId in gatewayIds) dao.clearActiveProvider()
      // Clear active media slot if it pointed at a gateway provider.
      val activeMediaId = dao.getSetting(KEY_ACTIVE_MEDIA)
      if (activeMediaId in gatewayIds) dao.upsertSetting(SettingsKvEntity(KEY_ACTIVE_MEDIA, ""))
      // Drop task-model configs routed to a gateway provider.
      val taskStore = ngo.xnet.aiope.core.network.TaskModelStore(ctx)
      for (task in ngo.xnet.aiope.core.network.ModelTask.entries) {
        if (taskStore.getTaskConfig(task).profileId in gatewayIds) taskStore.clearTaskConfig(task)
      }
      // Finally delete the gateway provider rows.
      gatewayIds.forEach { dao.deleteProvider(it) }
    }
    dao.upsertSetting(SettingsKvEntity(migratedKey, "true"))
  }

  /**
   * Seed a disabled, empty-key Novita provider once (BYOK). Users paste their own Novita API key
   * to activate it. No key is shipped, and NO models are pre-seeded: the provider editor
   * auto-detects models from Novita's live /models endpoint (base ends in /openai) once a key is
   * present, so the model list and per-model capabilities come from the real API rather than a
   * guessed default. Guarded by a settings flag so user deletion is respected.
   */
  private fun seedNovitaByok() = runBlocking(Dispatchers.IO) {
    val seededKey = "seeded_novita_byok"
    if (dao.getSetting(seededKey)?.toBooleanStrictOrNull() == true) return@runBlocking
    if (getAll().none { it.id == "novita_byok" }) {
      val novita = ProviderProfile(
        id = "novita_byok",
        builtinId = "custom",
        label = "Novita AI",
        apiKey = "", // BYOK — user supplies their own key
        apiBase = "https://api.novita.ai/v3/openai", // OpenAI-compatible; /models is auto-detected
        selectedModelId = "", // chosen by the user after models auto-load
        isActive = false,
        modelConfigs = emptyMap(), // no guessed defaults — detected from the live API
      )
      save(novita)
    }
    dao.upsertSetting(SettingsKvEntity(seededKey, "true"))
  }

  private fun ensureLiveModel() {
    val liveId = "google-ai-studio/gemini-3.1-flash-live-preview"
    val gw = getAll().firstOrNull { it.builtinId == "aiope_gateway" } ?: return
    if (gw.modelConfigs.containsKey(liveId)) return
    val cfg = ModelConfig(modelId = liveId, audioOverride = true, toolsOverride = true, contextTokens = 131_072)
    val updated = gw.copy(modelConfigs = gw.modelConfigs + (liveId to cfg))
    save(updated)
  }

  private fun seedTaskDefaults() {
    val taskStore = ngo.xnet.aiope.core.network.TaskModelStore(ctx)
    val gw = getAll().firstOrNull { it.builtinId == "aiope_gateway" } ?: return
    fun seed(task: ngo.xnet.aiope.core.network.ModelTask, model: String) {
      if (taskStore.getTaskConfig(task).profileId == null) {
        taskStore.setTaskConfig(task, ngo.xnet.aiope.core.network.TaskModelConfig(task.id, gw.id, model))
      }
    }
    seed(ngo.xnet.aiope.core.network.ModelTask.RAG, "google-ai-studio/models-gemini-embedding-2")
    seed(ngo.xnet.aiope.core.network.ModelTask.REALTIME_SPEECH, "google-ai-studio/gemini-3.1-flash-live-preview")
    seed(ngo.xnet.aiope.core.network.ModelTask.SUMMARY, "google-ai-studio/models-gemma-4-31b-it")
    seed(ngo.xnet.aiope.core.network.ModelTask.TRANSLATION, "google-ai-studio/models-gemma-4-26b-a4b-it")
    seed(ngo.xnet.aiope.core.network.ModelTask.TITLE, "google-ai-studio/models-gemma-4-26b-a4b-it")
    seed(ngo.xnet.aiope.core.network.ModelTask.SUBAGENT, "google-ai-studio/models-gemma-4-31b-it")
    seed(ngo.xnet.aiope.core.network.ModelTask.IMAGE_RECOGNITION, "google-ai-studio/models-gemma-4-26b-a4b-it")
    seed(ngo.xnet.aiope.core.network.ModelTask.IMAGE_GENERATION, "cloudflare/@cf-black-forest-labs-flux-1-schnell")
  }

  /**
   * BYOK-only: the deprecated AIOPE Gateway is no longer seeded as a default provider. We seed a
   * set of common OpenAI-compatible providers in a DISABLED, empty-key state so the user can pick
   * one and paste their own key. No key is shipped and none is active until the user supplies one.
   * (The Novita BYOK entry is seeded separately in seedNovitaByok.) Model lists are auto-detected
   * from each provider's live /models endpoint once a key is present.
   */
  private fun seedDefault() {
    fun byok(id: String, label: String, base: String) = ProviderProfile(
      id = id,
      builtinId = "custom",
      label = label,
      apiKey = "", // BYOK — user supplies their own key
      apiBase = base,
      selectedModelId = "", // chosen by the user after models auto-load
      isActive = false,
      modelConfigs = emptyMap(), // detected from the live API
    )
    // Common OpenAI-compatible endpoints. All disabled; user enables by adding a key.
    byok("openai_byok", "OpenAI", "https://api.openai.com/v1").let { save(it) }
    byok("openrouter_byok", "OpenRouter", "https://openrouter.ai/api/v1").let { save(it) }
    byok("groq_byok", "Groq", "https://api.groq.com/openai/v1").let { save(it) }
    byok("deepseek_byok", "DeepSeek", "https://api.deepseek.com/v1").let { save(it) }
    byok("ollama_byok", "Ollama (local)", "http://localhost:11434/v1").let { save(it) }
    // No provider is set active and no media provider is seeded — the user chooses after adding a
    // key. Media generation and per-task routing are configured once a BYOK provider is active.
  }

  /** One-time migration from SharedPreferences */
  private fun migrateFromPrefs() {
    val prefs = ctx.getSharedPreferences("aiope_providers", Context.MODE_PRIVATE)
    val raw = prefs.getString("profiles", null) ?: return
    try {
      val arr = JSONArray(raw)
      val activeId = prefs.getString("active_id", "") ?: ""
      for (i in 0 until arr.length()) {
        val p = ProviderProfile.fromJson(arr.getJSONObject(i))
        runBlocking(Dispatchers.IO) {
          dao.upsertProvider(ProviderEntity(p.id, p.toJson().toString(), p.id == activeId))
        }
        // Migrate model cache (old key was builtinId, new key is provider id)
        val cacheRaw = prefs.getString("mcache_${p.builtinId}", null)
        val cacheTs = prefs.getLong("mcache_ts_${p.builtinId}", 0)
        if (cacheRaw != null) {
          runBlocking(Dispatchers.IO) { dao.upsertModelCache(ModelCacheEntity(p.id, cacheRaw, cacheTs)) }
        }
      }
      // Migrate geoapify key
      val geoKey = prefs.getString("geoapify_key", null)
      if (!geoKey.isNullOrBlank()) {
        runBlocking(Dispatchers.IO) { dao.upsertSetting(SettingsKvEntity("geoapify_key", geoKey)) }
      }
      prefs.edit().clear().apply()
    } catch (e: Exception) {
      android.util.Log.w("ProviderStore", "op failed: ${e.message}")
    }
  }

  fun getAll(): List<ProviderProfile> = runBlocking(Dispatchers.IO) {
    dao.getProviders().mapNotNull { runCatching { ProviderProfile.fromJson(JSONObject(it.json)) }.getOrNull() }
  }

  fun getActive(): ProviderProfile = runBlocking(Dispatchers.IO) {
    dao.getActiveProvider()?.let { runCatching { ProviderProfile.fromJson(JSONObject(it.json)) }.getOrNull() }
  } ?: getAll().firstOrNull { it.isActive }
    ?: getAll().firstOrNull()
    // BYOK: no gateway fallback. If the user hasn't configured a provider yet, return an empty
    // custom profile — inference calls will surface a "configure a provider" error rather than
    // silently hitting a dead gateway.
    ?: ProviderProfile(builtinId = "custom", label = "No provider", apiKey = "", apiBase = "", selectedModelId = "")

  fun getById(id: String): ProviderProfile? = getAll().firstOrNull { it.id == id }

  fun save(profile: ProviderProfile) = runBlocking(Dispatchers.IO) {
    val existing = dao.getProviderById(profile.id)
    val isActive = existing?.isActive ?: profile.isActive
    dao.upsertProvider(ProviderEntity(profile.id, profile.toJson().toString(), isActive))
  }

  fun delete(id: String) = runBlocking(Dispatchers.IO) { dao.deleteProvider(id) }

  fun setActive(id: String) = runBlocking(Dispatchers.IO) {
    dao.clearActiveProvider()
    dao.setActiveProvider(id)
  }

  /**
   * Media-generation active slot. Stored in settings_kv (no DB migration) so it is independent
   * of the text active slot (which remains the DB `isActive` flag used by chat/tools).
   */
  fun getActiveMedia(): ProviderProfile? = runBlocking(Dispatchers.IO) {
    val id = dao.getSetting(KEY_ACTIVE_MEDIA)
    val all = getAll()
    (id?.let { mid -> all.firstOrNull { it.id == mid } })
      ?: all.firstOrNull { it.category == ProviderCategory.MEDIA }
  }

  fun setActiveMedia(id: String) = runBlocking(Dispatchers.IO) {
    dao.upsertSetting(SettingsKvEntity(KEY_ACTIVE_MEDIA, id))
  }

  fun getByCategory(category: ProviderCategory): List<ProviderProfile> =
    getAll().filter { it.category == category }

  fun saveModelCache(cacheKey: String, models: List<ModelDef>) {
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
    runBlocking(Dispatchers.IO) { dao.upsertModelCache(ModelCacheEntity(cacheKey, arr.toString())) }
  }

  fun getModelCache(cacheKey: String): List<ModelDef>? = runBlocking(Dispatchers.IO) {
    val e = dao.getModelCache(cacheKey) ?: return@runBlocking null
    if (System.currentTimeMillis() - e.cachedAt > 24 * 60 * 60 * 1000) return@runBlocking null
    parseModelCache(e.json)
  }

  fun getModelCacheStale(cacheKey: String): List<ModelDef>? = runBlocking(Dispatchers.IO) {
    dao.getModelCache(cacheKey)?.let { parseModelCache(it.json) }
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

  fun getGeoapifyKey(): String = runBlocking(Dispatchers.IO) { dao.getSetting("geoapify_key") ?: "" }
  fun setGeoapifyKey(key: String) = runBlocking(Dispatchers.IO) { dao.upsertSetting(SettingsKvEntity("geoapify_key", key)) }

  private fun fetchModelsAsync(profile: ProviderProfile) {
    Thread {
      try {
        val base = profile.effectiveApiBase().trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        val client = ngo.xnet.aiope.feature.chat.engine.SafeOkHttp.builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer ${profile.apiKey.trim()}").build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
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
        if (models.isNotEmpty()) saveModelCache(profile.id, models)
      } catch (e: Exception) {
        android.util.Log.w("ProviderStore", "op failed: ${e.message}")
      }
    }.start()
  }
}
