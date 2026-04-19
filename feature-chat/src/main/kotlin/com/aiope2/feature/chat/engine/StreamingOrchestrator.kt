package com.aiope2.feature.chat.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class StreamingOrchestrator(
  private val baseUrl: String,
  private val apiKey: String,
  private val model: String,
  private val tools: List<ToolDef> = emptyList(),
  private val onToolCall: suspend (String, Map<String, Any?>) -> String = { _, _ -> "" },
) {
  data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

  companion object {
    private val client = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(0, TimeUnit.SECONDS) // no overall call timeout — SSE streams are unbounded
      .pingInterval(15, TimeUnit.SECONDS) // keep HTTP/2 connection alive between chunks
      .retryOnConnectionFailure(true)
      .protocols(listOf(okhttp3.Protocol.HTTP_1_1)) // avoid HTTP/2 stream reset issues with SSE
      .build()
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val PARALLEL_SAFE = setOf(
      "read_file", "list_directory", "query_data", "search_web", "search_images",
      "fetch_url", "memory_recall", "get_location", "browser_content", "browser_elements",
      "search_location", "read_calendar", "read_contacts", "read_sms", "clipboard_read",
      "device_info", "analyze_image", "image_generate", "task",
    )
  }

  fun stream(
    messages: List<Pair<String, String>>,
    imageBase64s: List<String> = emptyList(),
  ): Flow<ChatStreamChunk> = callbackFlow {
    val rawMessages = messages.map { (role, content) ->
      JSONObject().put("role", role).put("content", content)
    }.toMutableList()

    // Attach images to last user message
    if (imageBase64s.isNotEmpty()) {
      val idx = rawMessages.indices.lastOrNull { rawMessages[it].optString("role") == "user" }
      if (idx != null) {
        val arr = JSONArray()
        arr.put(JSONObject().put("type", "text").put("text", rawMessages[idx].optString("content", "")))
        for (b64 in imageBase64s) {
          arr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        }
        rawMessages[idx].put("content", arr)
      }
    }

    var firstRequest = true
    var maxRounds = 100

    while (maxRounds-- > 0) {
      if (!firstRequest) {
        // Flatten multimodal arrays to text for follow-up requests
        for (msg in rawMessages) {
          val c = msg.opt("content")
          if (c is JSONArray) {
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

      // Trim older tool results
      val toolIdxs = rawMessages.indices.filter { rawMessages[it].optString("role") == "tool" }
      if (toolIdxs.size > 3) {
        for (i in toolIdxs.dropLast(3)) {
          val content = rawMessages[i].optString("content", "")
          if (content.length > 500) rawMessages[i].put("content", content.take(500) + "...(truncated)")
        }
      }

      val body = buildRequestBody(rawMessages)
      val request = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/chat/completions")
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Accept", "text/event-stream")
        .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
        .post(body.toRequestBody(JSON_MT))
        .build()

      val toolAcc = mutableMapOf<Int, MutableMap<String, String>>()
      var hasToolCalls = false
      var inThinkTag = false
      var thinkTagName = "think"
      var sseError: String? = null
      var sseDone = false

      val factory = EventSources.createFactory(client)
      val latch = java.util.concurrent.CountDownLatch(1)

      val eventSource = factory.newEventSource(
        request,
        object : EventSourceListener() {
          override fun onOpen(eventSource: EventSource, response: Response) {
            android.util.Log.d("AIOPE2", "SSE opened: ${response.code}")
            if (response.code !in 200..299) {
              sseError = "HTTP ${response.code}: ${response.body?.string()?.take(300)}"
              latch.countDown()
            }
          }

          override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (data == "[DONE]") {
              sseDone = true
              latch.countDown()
              return
            }
            try {
              val json = JSONObject(data)
              val choices = json.optJSONArray("choices") ?: return
              if (choices.length() == 0) return
              val choice = choices.getJSONObject(0)
              val delta = choice.optJSONObject("delta") ?: return
              val finishReason = choice.optString("finish_reason", "")

              // Text content
              var content = delta.optString("content", "").let { if (it == "null") "" else it }
              // Reasoning
              var reasoning = delta.optString("reasoning_content", "").let { if (it == "null") "" else it }.ifBlank {
                delta.optString("reasoning", "").let { if (it == "null") "" else it }
              }

              // Handle <think>/<thought> tags
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
                trySend(ChatStreamChunk(content = content, reasoning = reasoning.ifBlank { null }))
              }

              // Tool calls
              val tcArr = delta.optJSONArray("tool_calls")
              if (tcArr != null) {
                for (i in 0 until tcArr.length()) {
                  val tc = tcArr.getJSONObject(i)
                  val idx = tc.optInt("index", 0)
                  val acc = toolAcc.getOrPut(idx) { mutableMapOf("id" to "", "name" to "", "args" to "") }
                  tc.optString("id", "").let { if (it.isNotBlank()) acc["id"] = it }
                  tc.optJSONObject("function")?.let { fn ->
                    fn.optString("name", "").let { if (it.isNotBlank()) acc["name"] = it }
                    fn.optString("arguments", "").let { acc["args"] = (acc["args"] ?: "") + it }
                  }
                }
              }

              if (finishReason == "tool_calls" || (finishReason == "stop" && toolAcc.isNotEmpty())) {
                hasToolCalls = toolAcc.isNotEmpty()
                latch.countDown()
              }
            } catch (e: Exception) {
              android.util.Log.e("AIOPE2", "SSE parse: ${e.message} data=${data.take(100)}")
            }
          }

          override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val msg = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "Connection failed"
            // HTTP/2 stream resets during idle periods are not fatal — ignore if we already got data
            if (msg.contains("CANCEL") || msg.contains("stream was reset")) {
              android.util.Log.w("AIOPE2", "SSE stream reset (non-fatal): $msg")
            } else {
              sseError = msg
              android.util.Log.e("AIOPE2", "SSE failure: $sseError", t)
            }
            latch.countDown()
          }

          override fun onClosed(eventSource: EventSource) {
            latch.countDown()
          }
        },
      )

      latch.await()
      eventSource.cancel()

      if (sseError != null) {
        send(ChatStreamChunk(error = sseError, isDone = true))
        close()
        return@callbackFlow
      }

      // Execute tool calls
      if (hasToolCalls && toolAcc.isNotEmpty()) {
        val callInfos = toolAcc.map { (_, acc) ->
          val argsStr = acc["args"] ?: "{}"
          val args = try {
            val j = JSONObject(argsStr)
            j.keys().asSequence().associateWith { k -> j.opt(k) }
          } catch (_: Exception) {
            emptyMap()
          }
          ToolCallInfo(id = acc["id"] ?: "call_${System.nanoTime()}", name = acc["name"] ?: "", arguments = args)
        }
        send(ChatStreamChunk(toolCalls = callInfos))

        val results = if (callInfos.size > 1 && callInfos.all { it.name in PARALLEL_SAFE }) {
          val deferred = callInfos.map { call ->
            kotlinx.coroutines.CompletableDeferred<ToolResultInfo>().also { d ->
              kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val result = try {
                  onToolCall(call.name, call.arguments)
                } catch (e: Exception) {
                  "Error: ${e.message}"
                }
                d.complete(ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result))
              }
            }
          }
          deferred.map { it.await() }
        } else {
          callInfos.map { call ->
            val result = try {
              onToolCall(call.name, call.arguments)
            } catch (e: Exception) {
              "Error: ${e.message}"
            }
            ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result)
          }
        }
        send(ChatStreamChunk(toolResults = results))

        // Append assistant tool_calls + tool results for next round
        rawMessages.add(
          JSONObject().apply {
            put("role", "assistant")
            put("content", JSONObject.NULL)
            put(
              "tool_calls",
              JSONArray().apply {
                for (c in callInfos) put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", JSONObject(c.arguments).toString())))
              },
            )
          },
        )
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

      // Done
      send(ChatStreamChunk(isDone = true))
      close()
      return@callbackFlow
    }

    send(ChatStreamChunk(isDone = true))
    close()

    awaitClose { }
  }.flowOn(Dispatchers.IO)

  private fun buildRequestBody(messages: List<JSONObject>): String {
    val body = JSONObject()
    body.put("model", model)
    body.put("stream", true)
    body.put("messages", JSONArray().apply { for (m in messages) put(m) })
    if (tools.isNotEmpty()) {
      body.put(
        "tools",
        JSONArray().apply {
          for (t in tools) put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)))
        },
      )
    }
    return body.toString()
  }
}
