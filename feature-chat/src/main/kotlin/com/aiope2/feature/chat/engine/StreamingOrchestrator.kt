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
  private val onToolCall: suspend (String, Map<String, Any?>) -> String = { _, _ -> "" },
) {

  data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

  fun stream(messages: List<Pair<String, String>>, imageBase64s: List<String> = emptyList()): Flow<ChatStreamChunk> = flow {
    val rawMessages = mutableListOf<JSONObject>()
    for ((role, content) in messages) {
      rawMessages.add(JSONObject().put("role", role).put("content", content))
    }
    // Attach images to the last user message as multimodal content
    if (imageBase64s.isNotEmpty() && rawMessages.isNotEmpty()) {
      val lastUserIdx = rawMessages.indices.lastOrNull { rawMessages[it].optString("role") == "user" }
      if (lastUserIdx != null) {
        val msg = rawMessages[lastUserIdx]
        val contentArr = JSONArray()
        contentArr.put(JSONObject().put("type", "text").put("text", msg.optString("content", "")))
        for (b64 in imageBase64s) {
          contentArr.put(
            JSONObject().put("type", "image_url").put(
              "image_url",
              JSONObject().put("url", "data:image/jpeg;base64,$b64"),
            ),
          )
        }
        msg.put("content", contentArr)
      }
    }
    // After first request, flatten multimodal content arrays to strings for compatibility
    var firstRequest = true
    var maxRounds = 100

    while (maxRounds-- > 0) {
      if (!firstRequest) {
        // Strip image arrays from messages — follow-up requests don't need images
        for (msg in rawMessages) {
          val c = msg.opt("content")
          if (c is org.json.JSONArray) {
            // Extract text from multimodal array
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
              val obj = c.optJSONObject(i)
              if (obj?.optString("type") == "text") sb.append(obj.optString("text", ""))
            }
            msg.put("content", sb.toString())
          }
        }
      }
      firstRequest = false
      // Trim older tool results to reduce payload — keep last 3 full, truncate rest
      val toolMsgIndices = rawMessages.indices.filter { rawMessages[it].optString("role") == "tool" }
      if (toolMsgIndices.size > 3) {
        for (i in toolMsgIndices.dropLast(3)) {
          val msg = rawMessages[i]
          val content = msg.optString("content", "")
          if (content.length > 500) msg.put("content", content.take(500) + "...(truncated)")
        }
      }
      val body = buildRequestBody(rawMessages)
      val conn = openConnection(body)

      android.util.Log.d("AIOPE2", "SSE conn responseCode=${conn.responseCode} url=${conn.url}")
      if (conn.responseCode !in 200..299) {
        val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: "HTTP ${conn.responseCode}"
        emit(ChatStreamChunk(error = "Error ${conn.responseCode}: $err", isDone = true))
        return@flow
      }

      val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
      val toolAcc = mutableMapOf<Int, MutableMap<String, String>>() // index -> {id, name, args}
      var hasToolCalls = false
      var inThinkTag = false
      var thinkTagName = "think"
      var buffer = ""

      try {
        while (true) {
          if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive) break
          val line = reader.readLine() ?: break
          if (!line.startsWith("data:")) continue
          val data = line.removePrefix("data:").trim()
          if (data == "[DONE]") {
            android.util.Log.d("AIOPE2", "SSE [DONE] received")
            break
          }
          if (data.isEmpty()) continue

          try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta") ?: continue
            val finishReason = choice.optString("finish_reason", "")
            android.util.Log.d("AIOPE2", "SSE chunk: finish=$finishReason hasContent=${delta.has("content")} hasTools=${delta.has("tool_calls")}")

            // Text content
            var content = delta.optString("content", "").let { if (it == "null") "" else it }
            // Reasoning: separate field (DeepSeek, OpenAI o-series) or <think>/<thought> tags in content
            var reasoning = delta.optString("reasoning_content", "").let { if (it == "null") "" else it }.ifBlank {
              delta.optString("reasoning", "").let { if (it == "null") "" else it }
            }

            // Handle <think> and <thought> tags in content stream
            if (!inThinkTag) {
              if (content.contains("<think>")) {
                inThinkTag = true
                thinkTagName = "think"
                content = content.substringAfter("<think>")
              } else if (content.contains("<thought>")) {
                inThinkTag = true
                thinkTagName = "thought"
                content = content.substringAfter("<thought>")
              }
            }
            if (inThinkTag) {
              val closeTag = "</$thinkTagName>"
              if (content.contains(closeTag)) {
                reasoning = content.substringBefore(closeTag)
                content = content.substringAfter(closeTag)
                inThinkTag = false
              } else {
                reasoning = content
                content = ""
              }
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
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            android.util.Log.e("AIOPE2", "SSE parse error: ${e.message} data=${data.take(100)}")
          }
        }
      } finally {
        reader.close()
        conn.disconnect()
      }

      // Execute tool calls if any
      if (hasToolCalls && toolAcc.isNotEmpty()) {
        val callInfos = mutableListOf<ToolCallInfo>()

        toolAcc.forEach { (idx, acc) ->
          val id = acc["id"] ?: "call_${System.nanoTime()}"
          val name = acc["name"] ?: ""
          val argsStr = acc["args"] ?: "{}"
          val args = try {
            val j = JSONObject(argsStr)
            j.keys().asSequence().associateWith { k -> j.opt(k) }
          } catch (_: Exception) {
            emptyMap<String, Any?>()
          }
          callInfos.add(ToolCallInfo(id = id, name = name, arguments = args))
        }

        emit(ChatStreamChunk(toolCalls = callInfos))

        val results = mutableListOf<ToolResultInfo>()
        for (call in callInfos) {
          val result = try {
            onToolCall(call.name, call.arguments)
          } catch (e: Exception) {
            "Error: ${e.message}"
          }
          results.add(ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result))
        }

        emit(ChatStreamChunk(toolResults = results))

        // Rebuild full message list with proper tool_call format for follow-up
        // Add assistant message with tool_calls array
        val assistantToolMsg = JSONObject().apply {
          put("role", "assistant")
          put("content", JSONObject.NULL)
          val tcArr = JSONArray()
          for (c in callInfos) {
            tcArr.put(
              JSONObject().apply {
                put("id", c.id)
                put("type", "function")
                put(
                  "function",
                  JSONObject().apply {
                    put("name", c.name)
                    put("arguments", JSONObject(c.arguments).toString())
                  },
                )
              },
            )
          }
          put("tool_calls", tcArr)
        }
        rawMessages.add(assistantToolMsg)

        // Add tool result messages
        for (r in results) {
          rawMessages.add(
            JSONObject().apply {
              put("role", "tool")
              put("tool_call_id", r.id)
              put("content", r.result.take(16000))
            },
          )
        }

        continue
      }

      // No tool calls — done
      emit(ChatStreamChunk(isDone = true))
      return@flow
    }

    emit(ChatStreamChunk(isDone = true))
  }.flowOn(Dispatchers.IO)

  private fun buildRequestBody(messages: List<JSONObject>): String {
    val body = JSONObject()
    body.put("model", model)
    body.put("stream", true)

    val msgsArr = JSONArray()
    for (msg in messages) msgsArr.put(msg)
    body.put("messages", msgsArr)

    if (tools.isNotEmpty()) {
      val toolsArr = JSONArray()
      for (t in tools) {
        toolsArr.put(
          JSONObject().apply {
            put("type", "function")
            put(
              "function",
              JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.parameters)
              },
            )
          },
        )
      }
      body.put("tools", toolsArr)
    }

    return body.toString()
  }

  private fun openConnection(body: String): HttpURLConnection {
    val url = "${baseUrl.trimEnd('/')}/chat/completions"
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    conn.setRequestProperty("Accept", "text/event-stream")
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    conn.connectTimeout = 15_000
    conn.readTimeout = 300_000
    conn.doOutput = true
    conn.setChunkedStreamingMode(0)
    conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
    return conn
  }
}
