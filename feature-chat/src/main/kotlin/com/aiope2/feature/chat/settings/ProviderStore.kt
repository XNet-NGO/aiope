package com.aiope2.feature.chat.settings

import android.content.Context
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderStore @Inject constructor(@ApplicationContext ctx: Context) {
  private val prefs = ctx.getSharedPreferences("provider_profiles", Context.MODE_PRIVATE)

  fun getProfiles(): List<ProviderProfile> {
    val json = prefs.getString("profiles", null) ?: return listOf(defaultProfile())
    return try {
      val arr = JSONArray(json)
      (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { listOf(defaultProfile()) }
  }

  fun saveProfiles(profiles: List<ProviderProfile>) {
    val arr = JSONArray()
    profiles.forEach { arr.put(toJson(it)) }
    prefs.edit().putString("profiles", arr.toString()).apply()
  }

  fun getActiveId(): String = prefs.getString("active_id", null) ?: getProfiles().firstOrNull()?.id ?: ""

  fun setActiveId(id: String) { prefs.edit().putString("active_id", id).apply() }

  fun getActive(): ProviderProfile {
    val id = getActiveId()
    return getProfiles().firstOrNull { it.id == id } ?: getProfiles().firstOrNull() ?: defaultProfile()
  }

  fun addProfile(profile: ProviderProfile) {
    val profiles = getProfiles().toMutableList()
    profiles.add(profile)
    saveProfiles(profiles)
  }

  fun updateProfile(profile: ProviderProfile) {
    val profiles = getProfiles().toMutableList()
    val idx = profiles.indexOfFirst { it.id == profile.id }
    if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
    saveProfiles(profiles)
  }

  fun deleteProfile(id: String) {
    val profiles = getProfiles().filter { it.id != id }
    saveProfiles(profiles)
    if (getActiveId() == id) setActiveId(profiles.firstOrNull()?.id ?: "")
  }

  private fun defaultProfile() = ProviderTemplates.templates.first().copy(id = "default")

  private fun toJson(p: ProviderProfile) = JSONObject().apply {
    put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl)
    put("endpointOverride", p.endpointOverride); put("apiKey", p.apiKey)
    put("selectedModel", p.selectedModel); put("customModel", p.customModel)
    put("supportsVision", p.supportsVision); put("supportsAudio", p.supportsAudio)
    put("supportsVideo", p.supportsVideo); put("supportsTools", p.supportsTools)
    put("autoDetectAbilities", p.autoDetectAbilities)
    put("temperature", p.temperature.toDouble()); put("topP", p.topP.toDouble())
    put("topK", p.topK); put("maxTokens", p.maxTokens)
    put("contextLength", p.contextLength); put("systemPrompt", p.systemPrompt)
    put("availableModels", JSONArray(p.availableModels))
  }

  private fun fromJson(j: JSONObject) = ProviderProfile(
    id = j.optString("id"), name = j.optString("name"), baseUrl = j.optString("baseUrl"),
    endpointOverride = j.optString("endpointOverride"), apiKey = j.optString("apiKey"),
    selectedModel = j.optString("selectedModel"), customModel = j.optString("customModel"),
    supportsVision = j.optBoolean("supportsVision"), supportsAudio = j.optBoolean("supportsAudio"),
    supportsVideo = j.optBoolean("supportsVideo"), supportsTools = j.optBoolean("supportsTools", true),
    autoDetectAbilities = j.optBoolean("autoDetectAbilities", true),
    temperature = j.optDouble("temperature", 0.7).toFloat(), topP = j.optDouble("topP", 1.0).toFloat(),
    topK = j.optInt("topK"), maxTokens = j.optInt("maxTokens", 4096),
    contextLength = j.optInt("contextLength", 10), systemPrompt = j.optString("systemPrompt", "You are a helpful AI assistant."),
    availableModels = j.optJSONArray("availableModels")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
  )
}
