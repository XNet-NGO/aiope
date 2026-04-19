package com.aiope2.feature.chat.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.McpServerEntity
import com.aiope2.feature.chat.db.MemoryEntity
import com.aiope2.feature.chat.db.ProviderEntity
import com.aiope2.feature.chat.db.SettingsKvEntity
import com.aiope2.feature.chat.db.ToolToggleEntity
import org.json.JSONArray
import org.json.JSONObject

object SettingsPorter {

  suspend fun export(dao: ChatDao): String {
    val root = JSONObject()
    root.put("version", 1)
    root.put("exported", System.currentTimeMillis())

    // Providers
    val providers = JSONArray()
    dao.getProviders().forEach { providers.put(JSONObject().put("id", it.id).put("json", it.json).put("isActive", it.isActive)) }
    root.put("providers", providers)

    // Tool toggles
    val tools = JSONArray()
    // Read all via a query on each known tool — but simpler: export what's in the table
    // We'll read all toggles by querying the full table
    dao.getToolToggles().forEach { tools.put(JSONObject().put("toolId", it.toolId).put("enabled", it.enabled)) }
    root.put("tool_toggles", tools)

    // MCP servers
    val mcp = JSONArray()
    dao.getMcpServers().forEach { mcp.put(JSONObject().put("id", it.id).put("json", it.json)) }
    root.put("mcp_servers", mcp)

    // Agent settings (settings_kv where key starts with "agent_")
    val agent = JSONArray()
    dao.getSettingsByPrefix("agent_").forEach { agent.put(JSONObject().put("key", it.key).put("value", it.value)) }
    root.put("agent_settings", agent)

    // Memories
    val memories = JSONArray()
    dao.getAllMemories().forEach { m ->
      memories.put(JSONObject().put("key", m.key).put("content", m.content).put("category", m.category))
    }
    root.put("memories", memories)

    return root.toString(2)
  }

  suspend fun import(dao: ChatDao, json: String, replace: Boolean = false) {
    val root = JSONObject(json)

    // Providers
    if (root.has("providers")) {
      if (replace) dao.deleteAllProviders()
      val arr = root.getJSONArray("providers")
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        dao.upsertProvider(ProviderEntity(id = o.getString("id"), json = o.getString("json"), isActive = o.optBoolean("isActive", false)))
      }
    }

    // Tool toggles
    if (root.has("tool_toggles")) {
      val arr = root.getJSONArray("tool_toggles")
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        dao.upsertToolToggle(ToolToggleEntity(toolId = o.getString("toolId"), enabled = o.getBoolean("enabled")))
      }
    }

    // MCP servers
    if (root.has("mcp_servers")) {
      if (replace) dao.deleteAllMcpServers()
      val arr = root.getJSONArray("mcp_servers")
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        dao.upsertMcpServer(McpServerEntity(id = o.getString("id"), json = o.getString("json")))
      }
    }

    // Agent settings
    if (root.has("agent_settings")) {
      val arr = root.getJSONArray("agent_settings")
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        dao.upsertSetting(SettingsKvEntity(key = o.getString("key"), value = o.getString("value")))
      }
    }

    // Memories
    if (root.has("memories")) {
      val arr = root.getJSONArray("memories")
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        dao.upsertMemory(MemoryEntity(key = o.getString("key"), content = o.getString("content"), category = o.optString("category", "general")))
      }
    }
  }

  fun shareExport(ctx: Context, json: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/json"
      putExtra(Intent.EXTRA_TEXT, json)
      putExtra(Intent.EXTRA_SUBJECT, "AIOPE Settings Backup")
    }
    ctx.startActivity(Intent.createChooser(intent, "Export settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  suspend fun importFromUri(ctx: Context, dao: ChatDao, uri: Uri, replace: Boolean = false) {
    val json = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: throw Exception("Cannot read file")
    import(dao, json, replace)
  }
}
