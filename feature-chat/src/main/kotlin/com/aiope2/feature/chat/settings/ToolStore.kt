package com.aiope2.feature.chat.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolStore @Inject constructor(@ApplicationContext ctx: Context) {
  private val prefs = ctx.getSharedPreferences("aiope2_tools", Context.MODE_PRIVATE)

  fun isToolEnabled(toolId: String): Boolean = prefs.getBoolean("tool_$toolId", true)
  fun setToolEnabled(toolId: String, enabled: Boolean) = prefs.edit().putBoolean("tool_$toolId", enabled).apply()

  fun isDynamicUiEnabled(): Boolean = prefs.getBoolean("dynamic_ui_enabled", true)
  fun setDynamicUiEnabled(enabled: Boolean) = prefs.edit().putBoolean("dynamic_ui_enabled", enabled).apply()

  fun getMcpServers(): List<McpServerConfig> {
    val json = prefs.getString("mcp_servers", null) ?: return emptyList()
    return try {
      val arr = JSONArray(json)
      (0 until arr.length()).map { McpServerConfig.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun saveMcpServers(servers: List<McpServerConfig>) {
    val arr = JSONArray()
    servers.forEach { arr.put(it.toJson()) }
    prefs.edit().putString("mcp_servers", arr.toString()).apply()
  }

  fun addMcpServer(server: McpServerConfig) = saveMcpServers(getMcpServers() + server)
  fun removeMcpServer(id: String) = saveMcpServers(getMcpServers().filter { it.id != id })
  fun updateMcpServer(server: McpServerConfig) = saveMcpServers(getMcpServers().map { if (it.id == server.id) server else it })
  fun toggleMcpServer(id: String, enabled: Boolean) = saveMcpServers(getMcpServers().map { if (it.id == id) it.copy(enabled = enabled) else it })

  /** Import from standard MCP JSON format: {"mcpServers":{"id":{"name":"...","type":"...","baseUrl":"..."}}} */
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
