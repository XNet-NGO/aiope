package com.aiope2.feature.chat.settings

import com.aiope2.feature.chat.engine.StreamingOrchestrator
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class McpManager(private val toolStore: ToolStore) {
  private val sessions = mutableMapOf<String, String?>()
  private val toolCache = mutableMapOf<String, List<McpToolMeta>>()
  private val toolServerMap = mutableMapOf<String, McpServerConfig>()
  private var reqId = 0
  private var heartbeatTimer: java.util.Timer? = null

  fun startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = java.util.Timer("mcp-heartbeat", true).apply {
      scheduleAtFixedRate(
        object : java.util.TimerTask() {
          override fun run() {
            pingConnectedServers()
          }
        },
        15_000L,
        15_000L,
      )
    }
  }

  fun stopHeartbeat() {
    heartbeatTimer?.cancel()
    heartbeatTimer = null
  }

  private fun pingConnectedServers() {
    toolStore.getMcpServers().filter { it.enabled && it.status == McpStatus.CONNECTED }.forEach { server ->
      try {
        sendRequest(server, "tools/list")
      } catch (_: Exception) {
        toolStore.updateMcpServer(server.copy(status = McpStatus.ERROR, error = "Connection lost"))
      }
    }
  }

  data class McpToolMeta(val name: String, val description: String, val inputSchema: JSONObject)

  fun getToolDefs(serverId: String): List<StreamingOrchestrator.ToolDef> = (toolCache[serverId] ?: emptyList()).map { StreamingOrchestrator.ToolDef(it.name, it.description, it.inputSchema) }

  fun getCachedTools(serverId: String): List<McpToolMeta> = toolCache[serverId] ?: emptyList()

  /** Discover tools and return status. Updates toolStore with toolCount. */
  fun discoverTools(server: McpServerConfig): Result<List<McpToolMeta>> {
    return try {
      clearSession(server.id)
      initialize(server)
      val resp = sendRequest(server, "tools/list")
      val err = resp.optJSONObject("error")
      if (err != null) return Result.failure(Exception(err.optString("message", "Unknown error")))
      val tools = resp.optJSONObject("result")?.optJSONArray("tools") ?: return Result.success(emptyList())
      val result = (0 until tools.length()).map { i ->
        val t = tools.getJSONObject(i)
        McpToolMeta(
          t.getString("name"),
          t.optString("description", ""),
          t.optJSONObject("inputSchema") ?: JSONObject("""{"type":"object","properties":{}}"""),
        )
      }
      toolCache[server.id] = result
      result.forEach { toolServerMap[it.name] = server }
      toolStore.updateMcpServer(server.copy(toolCount = result.size, status = McpStatus.CONNECTED, error = null))
      Result.success(result)
    } catch (e: Exception) {
      toolStore.updateMcpServer(server.copy(status = McpStatus.ERROR, error = e.message))
      Result.failure(e)
    }
  }

  fun executeTool(name: String, args: Map<String, Any?>): String? {
    val server = toolServerMap[name] ?: return null
    return try {
      val argsJson = JSONObject()
      args.forEach { (k, v) -> argsJson.put(k, v) }
      val resp = sendRequest(server, "tools/call", JSONObject().put("name", name).put("arguments", argsJson))
      val result = resp.optJSONObject("result") ?: return resp.toString()
      val content = result.optJSONArray("content") ?: return result.toString()
      (0 until content.length()).mapNotNull { content.getJSONObject(it).optString("text") }.joinToString("\n")
    } catch (e: Exception) {
      "MCP error: ${e.message}"
    }
  }

  private fun initialize(server: McpServerConfig) {
    val params = JSONObject().apply {
      put("protocolVersion", "2024-11-05")
      put("capabilities", JSONObject())
      put("clientInfo", JSONObject().put("name", "AIOPE2").put("version", "1.0"))
    }
    sendRequest(server, "initialize", params)
    sessions[server.id] = sessions[server.id] // preserve session from response
    sendNotification(server, "notifications/initialized")
  }

  private fun sendRequest(server: McpServerConfig, method: String, params: JSONObject? = null): JSONObject {
    val body = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", ++reqId)
      put("method", method)
      params?.let { put("params", it) }
    }
    val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", if (server.transport == McpTransport.SSE) "text/event-stream" else "application/json, text/event-stream")
      sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
      server.headers.forEach { (k, v) -> setRequestProperty(k, v) }
      connectTimeout = 10_000
      readTimeout = 60_000
    }
    conn.outputStream.write(body.toString().toByteArray())
    val code = conn.responseCode
    if (code !in 200..299) {
      val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: "HTTP $code"
      conn.disconnect()
      throw Exception("HTTP $code: $err")
    }
    conn.getHeaderField("Mcp-Session-Id")?.let { sessions[server.id] = it }
    val contentType = conn.getHeaderField("Content-Type") ?: ""
    val responseText = if (contentType.contains("text/event-stream")) {
      // SSE: read all data lines
      val reader = BufferedReader(InputStreamReader(conn.inputStream))
      val sb = StringBuilder()
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        if (line!!.startsWith("data: ")) sb.appendLine(line)
      }
      reader.close()
      conn.disconnect()
      parseSse(sb.toString()).toString()
    } else {
      val text = conn.inputStream.bufferedReader().readText()
      conn.disconnect()
      text
    }
    return JSONObject(responseText)
  }

  private fun sendNotification(server: McpServerConfig, method: String) {
    try {
      val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
      val conn = (URL(server.url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        sessions[server.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
        server.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        connectTimeout = 5_000
        readTimeout = 5_000
      }
      conn.outputStream.write(body.toString().toByteArray())
      conn.responseCode
      conn.disconnect()
    } catch (_: Exception) {}
  }

  private fun parseSse(text: String): JSONObject {
    text.lines().filter { it.startsWith("data: ") }.forEach { line ->
      try {
        return JSONObject(line.removePrefix("data: ").trim())
      } catch (_: Exception) {}
    }
    return JSONObject()
  }

  fun clearSession(serverId: String) {
    sessions.remove(serverId)
    toolCache.remove(serverId)
    toolServerMap.entries.removeAll { it.value.id == serverId }
  }
}
