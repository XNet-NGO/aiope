package com.aiope2.feature.chat.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streaming chat orchestrator — Kelivo-style.
 * Handles SSE streaming, reasoning, parallel tool calls, and tool loop.
 */
class StreamingOrchestrator(
  private val baseUrl: String,
  private val apiKey: String,
  private val model: String,
  private val tools: List<ToolDef> = emptyList(),
  private val onToolCall: suspend (String, Map<String, Any?>) -> String = { _, _ -> "" }
) {

  data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

  fun stream(messages: List<Pair<String, String>>): Flow<ChatStreamChunk> = flow {
    var currentMessages = messages.toMutableList()
    var maxRounds = 6

    while (maxRounds-- > 0) {
      val body = buildRequestBody(currentMessages)
      val conn = openConnection(body)

      if (conn.responseCode !in 200..299) {
        val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: "HTTP ${conn.responseCode}"
        emit(ChatStreamChunk(error = "Error ${conn.responseCode}: $err", isDone = true))
        return@flow
      }

      val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
      val toolAcc = mutableMapOf<Int, MutableMap<String, String>>() // index -> {id, name, args}
      var hasToolCalls = false
      var buffer = ""

      try {
        while (true) {
          val line = reader.readLine() ?: break
          if (!line.startsWith("data:")) continue
          val data = line.removePrefix("data:").trim()
          if (data == "[DONE]") break
          if (data.isEmpty()) continue

          try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta") ?: continue
            val finishReason = choice.optString("finish_reason", "")

            // Text content
            val content = delta.optString("content", "")
            // Reasoning (DeepSeek, OpenAI o-series, Qwen)
            val reasoning = delta.optString("reasoning_content", "").ifBlank {
              delta.optString("reasoning", "")
            }

            if (content.isNotEmpty() || reasoning.isNotEmpty()) {
              emit(ChatStreamChunk(content = content, reasoning = reasoning.ifBlank { null }))
            }

            // Tool calls (accumulated across deltas)
            val toolCallsArr = delta.optJSONArray("tool_calls")
            if (toolCallsArr != null) {
              for (i in 0 until toolCallsArr.length()) {
                val tc = toolCallsArr.getJSONObject(i)
                val idx = tc.optInt("index", 0)
                val acc = toolAcc.getOrPut(idx) { mutableMapOf("id" to "", "name" to "", "args" to "") }
                tc.optString("id", "").let { if (it.isNotBlank()) acc["id"] = it }
                tc.optJSONObject("function")?.let { fn ->
                  fn.optString("name", "").let { if (it.isNotBlank()) acc["name"] = it }
                  fn.optString("arguments", "").let { acc["args"] = (acc["args"] ?: "") + it }
                }
              }
            }

            if (finishReason == "tool_calls" || finishReason == "stop" && toolAcc.isNotEmpty()) {
              hasToolCalls = toolAcc.isNotEmpty()
            }
          } catch (_: Exception) { /* skip malformed chunks */ }
        }
      } finally {
        reader.close()
        conn.disconnect()
      }

      // Execute tool calls if any
      if (hasToolCalls && toolAcc.isNotEmpty()) {
        val callInfos = mutableListOf<ToolCallInfo>()
        val toolCallMessages = mutableListOf<Pair<String, String>>() // for appending to history

        // Parse accumulated tool calls
        toolAcc.forEach { (_, acc) ->
          val id = acc["id"] ?: "call_${System.nanoTime()}"
          val name = acc["name"] ?: ""
          val argsStr = acc["args"] ?: "{}"
          val args = try {
            val j = JSONObject(argsStr)
            j.keys().asSequence().associateWith { k -> j.opt(k) }
          } catch (_: Exception) { emptyMap<String, Any?>() }
          callInfos.add(ToolCallInfo(id = id, name = name, arguments = args))
        }

        // Emit tool calls
        emit(ChatStreamChunk(toolCalls = callInfos))

        // Execute all tools (parallel-ready, sequential for now)
        val results = mutableListOf<ToolResultInfo>()
        for (call in callInfos) {
          val result = try { onToolCall(call.name, call.arguments) } catch (e: Exception) { "Error: ${e.message}" }
          results.add(ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result))
        }

        // Emit tool results
        emit(ChatStreamChunk(toolResults = results))

        // Build follow-up messages
        // Add assistant message with tool_calls
        currentMessages.add("assistant" to buildToolCallsContent(callInfos))
        // Add tool results
        for (r in results) {
          currentMessages.add("tool" to "[${r.name}] ${r.result}")
        }

        // Loop back for follow-up
        continue
      }

      // No tool calls — done
      emit(ChatStreamChunk(isDone = true))
      return@flow
    }

    emit(ChatStreamChunk(isDone = true))
  }.flowOn(Dispatchers.IO)

  private fun buildToolCallsContent(calls: List<ToolCallInfo>): String {
    return calls.joinToString("\n") { "🔧 ${it.name}(${JSONObject(it.arguments)})" }
  }

  private fun buildRequestBody(messages: List<Pair<String, String>>): String {
    val body = JSONObject()
    body.put("model", model)
    body.put("stream", true)

    val msgsArr = JSONArray()
    for ((role, content) in messages) {
      msgsArr.put(JSONObject().put("role", role).put("content", content))
    }
    body.put("messages", msgsArr)

    if (tools.isNotEmpty()) {
      val toolsArr = JSONArray()
      for (t in tools) {
        toolsArr.put(JSONObject().apply {
          put("type", "function")
          put("function", JSONObject().apply {
            put("name", t.name)
            put("description", t.description)
            put("parameters", t.parameters)
          })
        })
      }
      body.put("tools", toolsArr)
    }

    return body.toString()
  }

  private fun openConnection(body: String): HttpURLConnection {
    val url = "${baseUrl.trimEnd('/')}/chat/completions"
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Accept", "text/event-stream")
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 30_000
    conn.readTimeout = 120_000
    conn.doOutput = true
    conn.outputStream.write(body.toByteArray())
    return conn
  }
}
