package ngo.xnet.aiope.feature.chat.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
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
  private val temperature: Float = 0.7f,
) {
  data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

  companion object {
    private val client = SafeOkHttp.builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(5, TimeUnit.MINUTES) // reduced from 10m — detect dead connections faster
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(0, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
      .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS)) // no pooling — fresh connection every request (cellular NAT kills idle)
      .build()
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private const val MAX_RETRIES = 3
    private val PARALLEL_SAFE = setOf(
      "read_file", "list_directory", "query_data", "search_web", "search_images",
      "fetch_url", "memory_recall", "get_location", "browser_content", "browser_elements",
      "search_location", "read_calendar", "read_contacts", "read_sms", "clipboard_read",
      "device_info", "analyze_image", "image_generate", "ssh_exec",
    )

    private fun isTransientReset(msg: String): Boolean {
      val lower = msg.lowercase()
      return lower.contains("connection reset") ||
        lower.contains("stream was reset") ||
        lower.contains("unexpected end of stream") ||
        lower.contains("broken pipe") ||
        lower.contains("socket closed") ||
        lower.contains("connection abort") ||
        lower.contains("connection shutdown") ||
        lower.contains("failed to connect") ||
        lower.contains("timeout") ||
        lower.contains("enetunreach") ||
        lower.contains("enetdown") ||
        lower.contains("network is unreachable") ||
        lower.contains("software caused connection abort") ||
        lower.contains("recvfrom failed")
    }
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
    var maxRounds = 140

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

      var toolAcc = mutableMapOf<Int, MutableMap<String, String>>()
      var hasToolCalls = false
      var inThinkTag = false
      var thinkTagName = "think"
      var hasStructuredReasoning = false
      val pendingTagBuf = StringBuilder()
      val sseErrorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
      val sseDoneRef = java.util.concurrent.atomic.AtomicBoolean(false)

      // Accumulated content across retries (for mid-stream recovery)
      val contentSoFar = StringBuilder()
      var retries = 0

      while (true) {
        val request = Request.Builder()
          .url("${baseUrl.trimEnd('/')}/chat/completions")
          .header("Content-Type", "application/json; charset=utf-8")
          .header("Accept", "text/event-stream")
          .header("Connection", "close") // force fresh TCP — avoids cellular NAT killing reused sockets
          .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
          .post(body.toRequestBody(JSON_MT))
          .build()

        toolAcc = mutableMapOf()
        hasToolCalls = false
        sseErrorRef.set(null)
        sseDoneRef.set(false)
        // Track how much content we had before this attempt, to skip duplicates on retry
        val contentBeforeAttempt = contentSoFar.length
        var gotDataThisAttempt = false

        val factory = EventSources.createFactory(client)
        val latch = java.util.concurrent.CountDownLatch(1)

        val eventSource = factory.newEventSource(
          request,
          object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
              android.util.Log.d("AIOPE2", "SSE opened: ${response.code} (attempt ${retries + 1})")
              if (response.code !in 200..299) {
                sseErrorRef.set("HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                latch.countDown()
              }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
              if (data == "[DONE]") {
                sseDoneRef.set(true)
                latch.countDown()
                return
              }
              gotDataThisAttempt = true
              try {
                val json = JSONObject(data)
                val choices = json.optJSONArray("choices") ?: return
                if (choices.length() == 0) return
                val choice = choices.getJSONObject(0)
                val delta = choice.optJSONObject("delta") ?: return
                val finishReason = choice.optString("finish_reason", "")

                // Text content
                var content = delta.optString("content", "").let { if (it == "null") "" else it }
                // Reasoning from API field (models that support structured reasoning)
                var reasoning = delta.optString("reasoning_content", "").let { if (it == "null") "" else it }.ifBlank {
                  delta.optString("reasoning", "").let { if (it == "null") "" else it }
                }
                // Track if this model uses structured reasoning (skip tag parsing if so)
                if (reasoning.isNotEmpty()) hasStructuredReasoning = true

                // Handle <think>/<thought>/<thinking> tags (only for models that don't provide reasoning_content)
                if (content.isNotEmpty() && !hasStructuredReasoning) {
                  pendingTagBuf.append(content)
                  content = ""
                  // Process buffer
                  val buf = pendingTagBuf.toString()
                  if (!inThinkTag) {
                    val openTag = listOf("<thinking>", "<think>", "<thought>").firstOrNull { buf.contains(it) }
                    if (openTag != null) {
                      inThinkTag = true
                      thinkTagName = openTag.removePrefix("<").removeSuffix(">")
                      content = buf.substringBefore(openTag)
                      val afterOpen = buf.substringAfter(openTag)
                      pendingTagBuf.clear()
                      pendingTagBuf.append(afterOpen)
                      // Check if close tag is also in this buffer
                      val closeTag = "</$thinkTagName>"
                      val buf2 = pendingTagBuf.toString()
                      if (buf2.contains(closeTag)) {
                        reasoning = buf2.substringBefore(closeTag)
                        val afterClose = buf2.substringAfter(closeTag)
                        pendingTagBuf.clear()
                        content += afterClose
                        inThinkTag = false
                      } else {
                        // Emit reasoning up to any trailing partial tag
                        val lastLt = buf2.lastIndexOf('<')
                        if (lastLt >= 0 && lastLt > buf2.length - 13) {
                          reasoning = buf2.substring(0, lastLt)
                          pendingTagBuf.clear()
                          pendingTagBuf.append(buf2.substring(lastLt))
                        } else {
                          reasoning = buf2
                          pendingTagBuf.clear()
                        }
                      }
                    } else if (buf.endsWith("<") || (buf.length < 12 && buf.contains("<"))) {
                      // Might be partial open tag, keep buffering
                    } else {
                      // No tag, flush as content
                      val lastLt = buf.lastIndexOf('<')
                      if (lastLt >= 0 && lastLt > buf.length - 12) {
                        content = buf.substring(0, lastLt)
                        pendingTagBuf.clear()
                        pendingTagBuf.append(buf.substring(lastLt))
                      } else {
                        content = buf
                        pendingTagBuf.clear()
                      }
                    }
                  } else {
                    // In think tag — look for close tag
                    val closeTag = "</$thinkTagName>"
                    if (buf.contains(closeTag)) {
                      reasoning = buf.substringBefore(closeTag)
                      content = buf.substringAfter(closeTag)
                      pendingTagBuf.clear()
                      inThinkTag = false
                    } else {
                      // Emit reasoning but hold back potential partial close tag
                      val lastLt = buf.lastIndexOf('<')
                      if (lastLt >= 0 && lastLt > buf.length - 13) {
                        reasoning = buf.substring(0, lastLt)
                        pendingTagBuf.clear()
                        pendingTagBuf.append(buf.substring(lastLt))
                      } else {
                        reasoning = buf
                        pendingTagBuf.clear()
                      }
                    }
                  }
                }

                if (content.isNotEmpty()) {
                  contentSoFar.append(content)
                  // Cap at 2MB to prevent OOM on long agent runs
                  if (contentSoFar.length > 2_000_000) {
                    contentSoFar.delete(0, contentSoFar.length - 1_500_000)
                  }
                }

                // On retry, skip emitting content we already sent to the UI
                val shouldEmit = contentSoFar.length > contentBeforeAttempt || reasoning.isNotEmpty()
                if (shouldEmit && (content.isNotEmpty() || reasoning.isNotEmpty())) {
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
                }
              } catch (e: Exception) {
                android.util.Log.e("AIOPE2", "SSE parse: ${e.message} data=${data.take(100)}")
              }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
              val msg = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "Connection failed"
              if (isTransientReset(msg) && sseDoneRef.get()) {
                android.util.Log.w("AIOPE2", "SSE reset after done (non-fatal): $msg")
              } else {
                sseErrorRef.set(msg)
                android.util.Log.e("AIOPE2", "SSE failure (attempt ${retries + 1}): $msg", t)
              }
              latch.countDown()
            }

            override fun onClosed(eventSource: EventSource) {
              latch.countDown()
            }
          },
        )

        val latchOk = latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
        eventSource.cancel()

        // Latch timed out — treat as transient failure
        if (!latchOk && !sseDoneRef.get()) {
          sseErrorRef.set("Stream timeout (180s)")
          android.util.Log.e("AIOPE2", "SSE latch timeout (attempt ${retries + 1})")
        }

        // Success — no error or already done
        val sseError = sseErrorRef.get()
        val sseDone = sseDoneRef.get()
        if (sseError == null || sseDone) break

        // Non-retryable errors (HTTP 4xx, auth failures, etc.)
        if (!isTransientReset(sseError) || sseError.startsWith("HTTP 4")) break

        // Retry
        if (retries < MAX_RETRIES) {
          retries++
          val delay = 1000L * retries // 1s, 2s, 3s
          android.util.Log.i("AIOPE2", "Retrying SSE (attempt ${retries + 1}, had ${contentSoFar.length} chars): $sseError")
          Thread.sleep(delay)
          continue
        }
        break // exhausted retries
      }

      val sseError = sseErrorRef.get()
      val sseDone = sseDoneRef.get()
      if (sseError != null && !sseDone) {
        val retryNote = if (retries > 0) " (after ${retries + 1} attempts)" else ""
        send(ChatStreamChunk(error = "$sseError$retryNote", isDone = true))
        close()
        return@callbackFlow
      }

      // Flush any pending think-tag buffer remnants into contentSoFar
      if (pendingTagBuf.isNotEmpty()) {
        contentSoFar.append(pendingTagBuf)
        pendingTagBuf.clear()
      }

      // Execute tool calls
      if (toolAcc.isNotEmpty()) {
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
          coroutineScope {
            callInfos.map { call ->
              async(Dispatchers.IO) {
                val result = try {
                  onToolCall(call.name, call.arguments)
                } catch (e: Exception) {
                  "Error: ${e.message}"
                }
                ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result)
              }
            }.map { it.await() }
          }
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

      // Fallback: parse text-based tool calls (for models that don't use structured tool_calls)
      if (tools.isNotEmpty()) {
        val text = contentSoFar.toString()
        val parsed = parseTextToolCalls(text)
        if (parsed.isNotEmpty()) {
          // Strip tool call markup from displayed content
          val cleanContent = stripToolMarkup(text)
          send(ChatStreamChunk(contentReplace = cleanContent))

          val callInfos = parsed.map { (name, args) ->
            ToolCallInfo(id = "call_${System.nanoTime()}", name = name, arguments = args)
          }
          send(ChatStreamChunk(toolCalls = callInfos))
          val results = if (callInfos.size > 1 && callInfos.all { it.name in PARALLEL_SAFE }) {
            coroutineScope {
              callInfos.map { call ->
                async(Dispatchers.IO) {
                  val result = try { onToolCall(call.name, call.arguments) } catch (e: Exception) { "Error: ${e.message}" }
                  ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result)
                }
              }.map { it.await() }
            }
          } else {
            callInfos.map { call ->
              val result = try { onToolCall(call.name, call.arguments) } catch (e: Exception) { "Error: ${e.message}" }
              ToolResultInfo(id = call.id, name = call.name, arguments = call.arguments, result = result)
            }
          }
          send(ChatStreamChunk(toolResults = results))
          rawMessages.add(JSONObject().apply {
            put("role", "assistant")
            put("content", text)
          })
          for (r in results) {
            rawMessages.add(JSONObject().apply {
              put("role", "tool")
              put("tool_call_id", r.id)
              put("content", r.result.take(16000))
            })
          }
          contentSoFar.clear()
          continue
        }
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
    body.put("temperature", temperature.toDouble())
    body.put("messages", JSONArray().apply { for (m in messages) put(m) })
    android.util.Log.e("AIOPE2", "Request: model=$model tools=${tools.size} msgs=${messages.size}")
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

  /** Parse text-based tool calls from models that don't use structured tool_calls.
   *  Supports:
   *  - <tool_call>{"name":"x","arguments":{...}}</tool_call> (Qwen/Hermes format)
   *  - <minimax:tool_call><invoke name="x"><parameter name="k">v</parameter></invoke></minimax:tool_call> (MiniMax format)
   *  - ```json blocks with name+arguments
   *  - [tool_name]({"key":"value"}) inline format
   */
  private fun parseTextToolCalls(text: String): List<Pair<String, Map<String, Any?>>> {
    val results = mutableListOf<Pair<String, Map<String, Any?>>>()
    val toolNames = tools.map { it.name }.toSet()

    // Pattern 1: <minimax:tool_call> XML format
    if (text.contains("<minimax:tool_call>")) {
      val blockRegex = Regex("""<minimax:tool_call>(.*?)</minimax:tool_call>""", RegexOption.DOT_MATCHES_ALL)
      val invokeRegex = Regex("""<invoke name=["']?([^"'>]+)["']?>(.*?)</invoke>""", RegexOption.DOT_MATCHES_ALL)
      val paramRegex = Regex("""<parameter name=["']?([^"'>]+)["']?>(.*?)</parameter>""", RegexOption.DOT_MATCHES_ALL)
      for (block in blockRegex.findAll(text)) {
        for (invoke in invokeRegex.findAll(block.groupValues[1])) {
          val name = invoke.groupValues[1].trim()
          if (name in toolNames) {
            val args = mutableMapOf<String, Any?>()
            for (param in paramRegex.findAll(invoke.groupValues[2])) {
              val key = param.groupValues[1].trim()
              val value = param.groupValues[2].trim()
              // Try to parse as JSON (arrays, objects, numbers, booleans)
              args[key] = try { JSONObject("{\"v\":$value}").opt("v") } catch (_: Exception) { value }
            }
            results.add(name to args)
          }
        }
      }
      if (results.isNotEmpty()) return results
    }

    // Pattern 2: <tool_call>{"name":"x","arguments":{...}}</tool_call> (Qwen/Hermes)
    val tagRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
    for (m in tagRegex.findAll(text)) {
      try {
        val j = JSONObject(m.groupValues[1])
        val name = j.optString("name", "")
        if (name in toolNames) {
          val argsObj = j.optJSONObject("arguments") ?: j.optJSONObject("parameters") ?: JSONObject()
          val args = argsObj.keys().asSequence().associateWith { k -> argsObj.opt(k) }
          results.add(name to args)
        }
      } catch (_: Exception) {}
    }
    if (results.isNotEmpty()) return results

    // Pattern 3: ```json blocks with name+arguments
    val codeRegex = Regex("""```(?:json)?\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
    for (m in codeRegex.findAll(text)) {
      try {
        val j = JSONObject(m.groupValues[1])
        val name = j.optString("name", "")
        if (name in toolNames) {
          val argsObj = j.optJSONObject("arguments") ?: j.optJSONObject("parameters") ?: JSONObject()
          val args = argsObj.keys().asSequence().associateWith { k -> argsObj.opt(k) }
          results.add(name to args)
        }
      } catch (_: Exception) {}
    }
    if (results.isNotEmpty()) return results

    // Pattern 4: Standalone JSON tool call objects (no wrapping)
    val jsonCallRegex = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"(?:arguments|parameters)"\s*:\s*(\{.*?\})\s*\}""", RegexOption.DOT_MATCHES_ALL)
    for (m in jsonCallRegex.findAll(text)) {
      try {
        val name = m.groupValues[1]
        if (name in toolNames) {
          val argsObj = JSONObject(m.groupValues[2])
          val args = argsObj.keys().asSequence().associateWith { k -> argsObj.opt(k) }
          results.add(name to args)
        }
      } catch (_: Exception) {}
    }
    if (results.isNotEmpty()) return results

    // Pattern 5: [Tools: tool_name → argument_value] bracket format (minimax inline)
    val bracketRegex = Regex("""\[Tools?:\s*([a-z_]+)\s*→\s*(.+?)\]""")
    for (m in bracketRegex.findAll(text)) {
      val name = m.groupValues[1].trim()
      if (name in toolNames) {
        val argValue = m.groupValues[2].trim()
        // Infer the argument key from the tool name
        val argKey = inferArgKey(name)
        results.add(name to mapOf(argKey to argValue))
      }
    }
    if (results.isNotEmpty()) return results

    // Pattern 6: <|tool_calls_section_begin|> <|tool_call_begin|> functions.name:N <|tool_call_argument_begin|> {json} <|tool_call_end|> <|tool_calls_section_end|> (Kimi-K2 format)
    if (text.contains("<|tool_call_begin|>")) {
      val callRegex = Regex("""<\|tool_call_begin\|>\s*functions\.([^:]+):\d+\s*<\|tool_call_argument_begin\|>\s*(\{.*?\})\s*<\|tool_call_end\|>""", RegexOption.DOT_MATCHES_ALL)
      for (m in callRegex.findAll(text)) {
        val name = m.groupValues[1].trim()
        if (name in toolNames) {
          try {
            val argsObj = JSONObject(m.groupValues[2].trim())
            val args = argsObj.keys().asSequence().associateWith { k -> argsObj.opt(k) }
            results.add(name to args)
          } catch (_: Exception) {}
        }
      }
    }
    if (results.isNotEmpty()) return results

    // Pattern 7: <|tool_call>call:func_name{key:<|"|>value<|"|>}<tool_call|> (Gemma 4 native)
    if (text.contains("<|tool_call>")) {
      val gemmaRegex = Regex("""<\|tool_call>call:(\w+)\{(.*?)\}<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)
      for (m in gemmaRegex.findAll(text)) {
        val name = m.groupValues[1].trim()
        if (name in toolNames) {
          val rawArgs = m.groupValues[2]
          val args = mutableMapOf<String, Any?>()
          // Parse key:<|"|>value<|"|> pairs
          val argPattern = Regex("""(\w+):<\|"\|>(.*?)<\|"\|>""")
          for (am in argPattern.findAll(rawArgs)) {
            args[am.groupValues[1]] = am.groupValues[2]
          }
          // Parse key:number pairs
          val numPattern = Regex("""(\w+):(\d+(?:\.\d+)?)""")
          for (nm in numPattern.findAll(rawArgs)) {
            if (nm.groupValues[1] !in args) {
              val v = nm.groupValues[2]
              args[nm.groupValues[1]] = if (v.contains(".")) v.toDouble() else v.toInt()
            }
          }
          if (args.isNotEmpty()) results.add(name to args)
        }
      }
    }
    return results
  }

  /** Infer the primary argument key for a tool based on its name */
  private fun inferArgKey(toolName: String): String = when (toolName) {
    "run_sh", "run_proot" -> "command"
    "read_file", "write_file" -> "path"
    "list_directory" -> "path"
    "search_web", "search_images" -> "query"
    "fetch_url" -> "url"
    "memory_store", "memory_recall", "memory_forget" -> "key"
    "send_sms" -> "message"
    "send_notification" -> "text"
    "open_intent" -> "uri"
    "ssh_exec" -> "command"
    "browser_navigate" -> "url"
    "browser_click", "browser_fill" -> "selector"
    "browser_eval" -> "script"
    "image_generate" -> "prompt"
    "analyze_image" -> "prompt"
    "search_location" -> "query"
    "query_data" -> "source"
    else -> "input"
  }

  /** Strip tool call markup from text for display purposes */
  private fun stripToolMarkup(text: String): String {
    var cleaned = text
    // Strip <minimax:tool_call>...</minimax:tool_call> blocks
    cleaned = Regex("""<minimax:tool_call>.*?</minimax:tool_call>""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip <tool_call>...</tool_call> blocks
    cleaned = Regex("""<tool_call>.*?</tool_call>""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip <|tool_calls_section_begin|>...<|tool_calls_section_end|> blocks (Kimi-K2)
    cleaned = Regex("""<\|tool_calls_section_begin\|>.*?<\|tool_calls_section_end\|>""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip standalone <|tool_call_begin|>...<|tool_call_end|> (if no section wrapper)
    cleaned = Regex("""<\|tool_call_begin\|>.*?<\|tool_call_end\|>""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip <|tool_call>call:...<tool_call|> (Gemma 4 native)
    cleaned = Regex("""<\|tool_call>.*?<tool_call\|>""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip ```json tool call blocks (only those with name+arguments pattern)
    cleaned = Regex("""```(?:json)?\s*\{[^`]*?"name"\s*:.*?"(?:arguments|parameters)"\s*:.*?\}\s*```""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip standalone JSON tool call objects
    cleaned = Regex("""\{\s*"name"\s*:\s*"[^"]+"\s*,\s*"(?:arguments|parameters)"\s*:\s*\{.*?\}\s*\}""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Strip [Tools: tool_name → args] bracket format
    cleaned = Regex("""\[Tools?:\s*[a-z_]+\s*→\s*.+?\]""").replace(cleaned, "")
    // Collapse multiple blank lines
    cleaned = Regex("""\n{3,}""").replace(cleaned, "\n\n")
    return cleaned.trim()
  }
}
