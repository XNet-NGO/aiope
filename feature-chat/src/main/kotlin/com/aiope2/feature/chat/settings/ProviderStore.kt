package com.aiope2.feature.chat.settings

import android.content.Context
import android.content.SharedPreferences
import com.aiope2.core.network.LlmProvider
import com.aiope2.core.network.ProviderDefaults
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderStore @Inject constructor(@ApplicationContext ctx: Context) {
  private val prefs: SharedPreferences = ctx.getSharedPreferences("providers", Context.MODE_PRIVATE)

  fun getActiveProvider(): LlmProvider {
    val name = prefs.getString("active_name", null) ?: return ProviderDefaults.providers.first()
    return ProviderDefaults.providers.firstOrNull { it.name == name }
      ?: LlmProvider(
        name = name,
        baseUrl = prefs.getString("active_url", "") ?: "",
        apiKey = prefs.getString("active_key", "") ?: "",
        defaultModel = prefs.getString("active_model", "") ?: ""
      )
  }

  fun setActiveProvider(provider: LlmProvider) {
    prefs.edit()
      .putString("active_name", provider.name)
      .putString("active_url", provider.baseUrl)
      .putString("active_key", provider.apiKey)
      .putString("active_model", provider.defaultModel)
      .apply()
  }

  fun getApiKey(providerName: String): String =
    prefs.getString("key_$providerName", "") ?: ""

  fun setApiKey(providerName: String, key: String) {
    prefs.edit().putString("key_$providerName", key).apply()
  }

  fun getModel(providerName: String): String =
    prefs.getString("model_$providerName", "") ?: ""

  fun setModel(providerName: String, model: String) {
    prefs.edit().putString("model_$providerName", model).apply()
  }
}
