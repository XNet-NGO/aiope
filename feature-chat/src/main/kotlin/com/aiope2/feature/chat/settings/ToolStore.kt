package com.aiope2.feature.chat.settings

import android.content.Context
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.McpServerEntity
import com.aiope2.feature.chat.db.SettingsKvEntity
import com.aiope2.feature.chat.db.ToolToggleEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolStore @Inject constructor(
  @ApplicationContext private val ctx: Context,
  private val dao: ChatDao,
) {
  init {
    migrateFromPrefs()
  }

  private fun migrateFromPrefs() {
    val prefs = ctx.getSharedPreferences("aiope2_tools", Context.MODE_PRIVATE)
    if (prefs.all.isEmpty()) return
    runBlocking {
      // Migrate tool toggles
      prefs.all.forEach { (k, v) ->
        if (k.startsWith("tool_") && v is Boolean) {
          dao.upsertToolToggle(ToolToggleEntity(k.removePrefix("tool_"), v))
        }
      }
      // Migrate dynamic UI setting
      if (prefs.contains("dynamic_ui_enabled")) {
        dao.upsertSetting(SettingsKvEntity("dynamic_ui_enabled", prefs.getBoolean("dynamic_ui_enabled", true).toString()))
      }
      // Migrate MCP servers
      val mcpJson = prefs.getString("mcp_servers", null)
      if (mcpJson != null) {
        try {
          val arr = JSONArray(mcpJson)
          for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val cfg = McpServerConfig.fromJson(obj)
            dao.upsertMcpServer(McpServerEntity(cfg.id, obj.toString()))
          }
        } catch (_: Exception) {}
      }
    }
    prefs.edit().clear().apply()
  }

  private val defaultOff = setOf("generate_image")

  fun isToolEnabled(toolId: String): Boolean = runBlocking {
    dao.getToolToggle(toolId)?.enabled ?: (toolId !in defaultOff)
  }

  fun setToolEnabled(toolId: String, enabled: Boolean) = runBlocking {
    dao.upsertToolToggle(ToolToggleEntity(toolId, enabled))
  }

  fun isDynamicUiEnabled(): Boolean = runBlocking {
    dao.getSetting("dynamic_ui_enabled")?.toBooleanStrictOrNull() ?: true
  }

  fun setDynamicUiEnabled(enabled: Boolean) = runBlocking {
    dao.upsertSetting(SettingsKvEntity("dynamic_ui_enabled", enabled.toString()))
  }

  fun getMcpServers(): List<McpServerConfig> = runBlocking {
    dao.getMcpServers().mapNotNull { runCatching { McpServerConfig.fromJson(JSONObject(it.json)) }.getOrNull() }
  }

  fun saveMcpServers(servers: List<McpServerConfig>) = runBlocking {
    dao.deleteAllMcpServers()
    servers.forEach { dao.upsertMcpServer(McpServerEntity(it.id, it.toJson().toString())) }
  }

  fun addMcpServer(server: McpServerConfig) = runBlocking {
    dao.upsertMcpServer(McpServerEntity(server.id, server.toJson().toString()))
  }

  fun removeMcpServer(id: String) = runBlocking { dao.deleteMcpServer(id) }

  fun updateMcpServer(server: McpServerConfig) = runBlocking {
    dao.upsertMcpServer(McpServerEntity(server.id, server.toJson().toString()))
  }

  fun toggleMcpServer(id: String, enabled: Boolean) {
    val servers = getMcpServers()
    val updated = servers.map { if (it.id == id) it.copy(enabled = enabled) else it }
    saveMcpServers(updated)
  }

  fun importFromJson(json: String): Int {
    val root = JSONObject(json)
    val serversObj = root.optJSONObject("mcpServers") ?: throw IllegalArgumentException("Missing mcpServers key")
    val existing = getMcpServers().associateBy { it.id }.toMutableMap()
    var count = 0
    serversObj.keys().forEach { id ->
      val cfg = serversObj.getJSONObject(id)
      val transport = when (cfg.optString("type", "").lowercase()) {
        "streamablehttp", "http" -> McpTransport.HTTP
        else -> McpTransport.SSE
      }
      val headers = mutableMapOf<String, String>()
      cfg.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
      existing[id] = McpServerConfig(
        id = id,
        name = cfg.optString("name", id),
        url = cfg.optString("baseUrl", ""),
        transport = transport,
        headers = headers,
        enabled = cfg.optBoolean("isActive", true),
      )
      count++
    }
    saveMcpServers(existing.values.toList())
    return count
  }
}

enum class McpTransport { HTTP, SSE }

data class McpServerConfig(
  val id: String = java.util.UUID.randomUUID().toString().take(8),
  val name: String,
  val url: String,
  val transport: McpTransport = McpTransport.HTTP,
  val headers: Map<String, String> = emptyMap(),
  val enabled: Boolean = true,
  val toolCount: Int = 0,
  val status: McpStatus = McpStatus.IDLE,
  val error: String? = null,
) {
  fun toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("url", url)
    put("enabled", enabled)
    put("transport", transport.name.lowercase())
    put("headers", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
    put("toolCount", toolCount)
    put("status", status.name.lowercase())
    error?.let { put("error", it) }
  }
  companion object {
    fun fromJson(j: JSONObject) = McpServerConfig(
      id = j.optString("id", java.util.UUID.randomUUID().toString().take(8)),
      name = j.optString("name"), url = j.optString("url"),
      enabled = j.optBoolean("enabled", true),
      transport = if (j.optString("transport") == "sse") McpTransport.SSE else McpTransport.HTTP,
      headers = j.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap(),
      toolCount = j.optInt("toolCount", 0),
      status = try {
        McpStatus.valueOf(j.optString("status", "idle").uppercase())
      } catch (_: Exception) {
        McpStatus.IDLE
      },
      error = j.optString("error", "").ifBlank { null },
    )
  }
}

enum class McpStatus { IDLE, CONNECTING, CONNECTED, ERROR }
