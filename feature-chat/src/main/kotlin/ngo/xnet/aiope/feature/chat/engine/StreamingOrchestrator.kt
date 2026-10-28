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
  private val reasoningEffort: String? = null,
) {
  data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

  companion object {
    private val client = SafeOkHttp.builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(3, TimeUnit.MINUTES) // shorter than 5m — trigger retry on dead connections sooner
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(0, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
      .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS)) // no pooling — fresh connection every request (cellular NAT kills idle)
      .socketFactory(KeepAliveSocketFactory)
      .eventListenerFactory(LlmEventListener.Factory)
      .build()
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private const val MAX_RETRIES = 3
    private val PARALLEL_SAFE = setOf(
      "read_file", "list_directory", "search_web", "search_images",
      "fetch_url", "memory_recall", "get_location", "browser_content", "browser_elements",
      "search_location", "read_calendar", "read_contacts", "read_sms", "clipboard_read",
      "device_info", "analyze_image", "image_generate", "ssh_exec",
      "search_files", "http_request", "datetime_now", "todo_read", "list_schedules", "rag_search",
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

    /**
     * Retryable HTTP status codes: rate limiting (429) and transient server errors
     * (500, 502, 503, 504). These are temporary and safe to retry with backoff.
     */
    private fun isRetryableHttp(msg: String): Boolean {
      val m = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
      return m == 429 || m == 500 || m == 502 || m == 503 || m == 504
    }

    /** Exponential backoff with jitter: ~1s, 2s, 4s (+/- up to 500ms), capped. */
    private fun backoffMs(attempt: Int): Long {
      val base = (1000L shl (attempt - 1)).coerceAtMost(8000L)
      return base + (0..500).random()
    }

    /** Per-stream state for the inline <think> tag drainer. */
    class ThinkTagState(var inThinkTag: Boolean = false, var thinkTagName: String = "think")

    /** True for Google-family models (Gemini + Gemma), which have special tool-call history rules. */
    fun isGoogleModelName(model: String): Boolean =
      model.contains("gemini", ignoreCase = true) ||
        model.contains("gemma", ignoreCase = true) ||
        model.startsWith("google", ignoreCase = true)

    /** True when a tool call carries a Google thought_signature in its extra_content. */
    private fun hasThoughtSignature(extraContent: String?): Boolean =
      extraContent?.let {
        runCatching { JSONObject(it).optJSONObject("google")?.has("thought_signature") == true }.getOrDefault(false)
      } == true

    /**
     * Decide how to record a completed tool round in history for the NEXT request.
     *
     * Returns true to use the native OpenAI tool_calls/tool protocol; false to fall back to a plain
     * assistant+user text turn. Non-Google models always use the native protocol. Google models use
     * it ONLY when every tool call carries a thought_signature — otherwise Gemini rejects the
     * resent turn, and Gemma never produces signatures at all, so we must use the plain-text form.
     * Deleting the exchange instead (the old behavior) lost the tool result and caused infinite
     * re-call loops with context loss.
     */
    fun useNativeToolProtocol(model: String, extraContents: List<String?>): Boolean {
      if (!isGoogleModelName(model)) return true
      return extraContents.isNotEmpty() && extraContents.all { hasThoughtSignature(it) }
    }

    private val OPEN_TAGS = listOf("<thinking>", "<think>", "<thought>")
    private val CLOSE_TAGS = listOf("</thinking>", "</think>", "</thought>")

    /**
     * Splits a content delta into (visibleContent, reasoning) for models that inline their
     * chain-of-thought in `<think>`/`<thinking>`/`<thought>` tags.
     *
     * Fully DRAINS [pendingTagBuf] each call: a single delta may open and close several tags
     * (e.g. "...</think> answer <think>more"), so it loops until only a possible partial trailing
     * tag remains buffered. [state] carries open/closed status across deltas; [pendingTagBuf] holds
     * any unresolved partial tag for the next delta. Orphan close tags (a stray `</think>` with no
     * opener) are dropped rather than rendered. Returns the content and reasoning extracted this call.
     */
    fun drainThinkTags(
      content: String,
      state: ThinkTagState,
      pendingTagBuf: StringBuilder,
    ): Pair<String, String> {
      pendingTagBuf.append(content)
      // Longest recognized tag is "</thinking>" (11 chars); hold back a trailing '<' only when it
      // could still grow into a real open/close tag.
      val maxTagLen = 12
      val contentOut = StringBuilder()
      val reasoningOut = StringBuilder()
      var draining = true
      while (draining) {
        val buf = pendingTagBuf.toString()
        if (buf.isEmpty()) break
        if (!state.inThinkTag) {
          // Find the EARLIEST open tag in the buffer (not just the first match in list order).
          val match = OPEN_TAGS
            .mapNotNull { tag -> buf.indexOf(tag).takeIf { it >= 0 }?.let { it to tag } }
            .minByOrNull { it.first }
          if (match != null) {
            val (idx, openTag) = match
            contentOut.append(buf.substring(0, idx))
            state.inThinkTag = true
            state.thinkTagName = openTag.removePrefix("<").removeSuffix(">")
            pendingTagBuf.clear()
            pendingTagBuf.append(buf.substring(idx + openTag.length))
          } else {
            // No open tag. Drop any ORPHAN close tag so it doesn't render as literal text,
            // then flush the rest as content, holding back a trailing partial '<...'.
            val orphan = CLOSE_TAGS
              .mapNotNull { tag -> buf.indexOf(tag).takeIf { it >= 0 }?.let { it to tag } }
              .minByOrNull { it.first }
            if (orphan != null) {
              val (oidx, otag) = orphan
              contentOut.append(buf.substring(0, oidx))
              pendingTagBuf.clear()
              pendingTagBuf.append(buf.substring(oidx + otag.length))
            } else {
              val lastLt = buf.lastIndexOf('<')
              if (lastLt >= 0 && lastLt > buf.length - maxTagLen) {
                contentOut.append(buf.substring(0, lastLt))
                pendingTagBuf.clear()
                pendingTagBuf.append(buf.substring(lastLt))
              } else {
                contentOut.append(buf)
                pendingTagBuf.clear()
              }
              draining = false
            }
          }
        } else {
          val closeTag = "</${state.thinkTagName}>"
          val idx = buf.indexOf(closeTag)
          if (idx >= 0) {
            reasoningOut.append(buf.substring(0, idx))
            state.inThinkTag = false
            pendingTagBuf.clear()
            pendingTagBuf.append(buf.substring(idx + closeTag.length))
          } else {
            // Still inside the tag. Emit reasoning, holding back a trailing partial '</...'.
            val lastLt = buf.lastIndexOf('<')
            if (lastLt >= 0 && lastLt > buf.length - (maxTagLen + 1)) {
              reasoningOut.append(buf.substring(0, lastLt))
              pendingTagBuf.clear()
              pendingTagBuf.append(buf.substring(lastLt))
            } else {
              reasoningOut.append(buf)
              pendingTagBuf.clear()
            }
            draining = false
          }
        }
      }
      return contentOut.toString() to reasoningOut.toString()
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
    var maxRounds = 40
    var lastUsage: UsageInfo? = null

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

      // Trim older tool results — but NEVER trim introspect manual pages (they are
      // whole reference docs; trimming them to 500 chars made the agent think it got
      // a truncated result and call introspect over and over).
      val toolIdxs = rawMessages.indices.filter { rawMessages[it].optString("role") == "tool" }
      if (toolIdxs.size > 3) {
        for (i in toolIdxs.dropLast(3)) {
          val content = rawMessages[i].optString("content", "")
          val isManualPage = content.startsWith("AIOPE manual")
          if (content.length > 500 && !isManualPage) rawMessages[i].put("content", content.take(500) + "...(truncated)")
        }
      }

      // NOTE: We deliberately do NOT strip assistant tool_calls that lack a Google
      // thought_signature. Doing so wiped the call AND its result from history, so the model
      // never saw the tool output and re-called the same tool forever (context loss + loop).
      // Gemini rejects native tool_calls sent back without their thought_signature, and Gemma
      // never produces signatures at all — so for Google models WITHOUT a signature we instead
      // record the exchange as a plain assistant+user text turn (see the tool-result append
      // below), which every model accepts and nothing strips.

      val body = buildRequestBody(rawMessages)

      var toolAcc = mutableMapOf<Int, MutableMap<String, String>>()
      var hasToolCalls = false
      val tagState = ThinkTagState()
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
          .header("X-Request-Id", lastRequestNonce) // matching id for request/response correlation
          .header("Cache-Control", "no-store") // defeat server-side response caching for this request
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
              val serverId = response.header("X-Request-Id") ?: "-"
              android.util.Log.d("AIOPE2", "SSE opened: ${response.code} reqId=$lastRequestNonce serverReqId=$serverId (attempt ${retries + 1})")
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
                // Extract usage (often in the final chunk)
                json.optJSONObject("usage")?.let { u ->
                  lastUsage = UsageInfo(
                    inputTokens = u.optInt("prompt_tokens", 0),
                    outputTokens = u.optInt("completion_tokens", 0),
                  )
                }
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
                // ALWAYS strip inline <think>/<thinking>/<thought> tags from content, even for models
                // that also emit structured reasoning. Some models (e.g. minimax) send a structured
                // reasoning delta first, then switch to inline <think> tags in the content field — if
                // we skip tag parsing after seeing structured reasoning, those later tags leak into the
                // visible answer verbatim (the reported "duped thoughts in the body" bug). The drainer
                // is a no-op passthrough on tag-free content, so running it unconditionally is safe.
                // See drainThinkTags + ThinkTagParserTest.
                if (content.isNotEmpty()) {
                  val out = drainThinkTags(content, tagState, pendingTagBuf)
                  content = out.first
                  // Merge any tag-extracted reasoning with structured reasoning from this same chunk.
                  reasoning = if (reasoning.isNotEmpty() && out.second.isNotEmpty()) {
                    reasoning + out.second
                  } else {
                    reasoning.ifEmpty { out.second }
                  }
                }

                if (content.isNotEmpty()) {
                  contentSoFar.append(content)
                  // Cap at 2MB to prevent OOM on long agent runs
                  if (contentSoFar.length > 2_000_000) {
                    contentSoFar.delete(0, contentSoFar.length - 1_500_000)
                  }
                  // Suppress display of minimax tool call XML (will be parsed after stream)
                  val soFar = contentSoFar.toString()
                  if (soFar.contains("<minimax:tool_call>") && !soFar.contains("</minimax:tool_call>")) {
                    content = "" // Hold back — still accumulating tool call block
                  } else if (soFar.contains("<minimax:tool_call>") && soFar.contains("</minimax:tool_call>")) {
                    content = "" // Entire block received — will be handled by fallback parser
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
                    val tcId = tc.optString("id", "")
                    // Use explicit index if provided, otherwise find by id or use next slot
                    val idx = if (tc.has("index")) {
                      tc.getInt("index")
                    } else if (tcId.isNotBlank()) {
                      // Find existing slot with this id, or assign next available
                      toolAcc.entries.firstOrNull { it.value["id"] == tcId }?.key
                        ?: toolAcc.size
                    } else {
                      0
                    }
                    val acc = toolAcc.getOrPut(idx) { mutableMapOf("id" to "", "name" to "", "args" to "") }
                    if (tcId.isNotBlank()) acc["id"] = tcId
                    tc.optJSONObject("function")?.let { fn ->
                      fn.optString("name", "").let { if (it.isNotBlank()) acc["name"] = it }
                      fn.optString("arguments", "").let { acc["args"] = (acc["args"] ?: "") + it }
                    }
                    // Capture extra_content (Google thought_signature)
                    tc.optJSONObject("extra_content")?.let { acc["extra_content"] = it.toString() }
                  }
                }

                if (finishReason == "tool_calls" || finishReason == "tool_use" || (finishReason == "stop" && toolAcc.isNotEmpty())) {
                  hasToolCalls = toolAcc.isNotEmpty()
                }
              } catch (e: Exception) {
                android.util.Log.e("AIOPE2", "SSE parse: ${e.message} data=${data.take(100)}")
              }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
              val msg = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "Connection failed"
              when {
                // Stream already signaled done — any trailing reset is harmless.
                isTransientReset(msg) && sseDoneRef.get() -> {
                  android.util.Log.w("AIOPE2", "SSE reset after done (non-fatal): $msg")
                }
                // Some gateways (e.g. freeinference) END the stream by closing the socket
                // WITHOUT sending `data: [DONE]`. If we already received content this attempt,
                // treat the close as a normal end-of-stream, not a failure.
                gotDataThisAttempt && (isTransientReset(msg) || msg.lowercase().contains("cancel")) -> {
                  android.util.Log.w("AIOPE2", "SSE closed after delivering data (treated as done): $msg")
                  sseDoneRef.set(true)
                }
                sseErrorRef.get() == null -> {
                  sseErrorRef.set(msg)
                  android.util.Log.e("AIOPE2", "SSE failure (attempt ${retries + 1}): $msg", t)
                }
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

        // Retry transient network resets AND retryable HTTP statuses (429 rate-limit, 5xx).
        // Everything else (4xx auth/validation, etc.) is non-retryable.
        val retryable = isTransientReset(sseError) || isRetryableHttp(sseError)
        if (!retryable) break

        // Retry with exponential backoff + jitter
        if (retries < MAX_RETRIES) {
          retries++
          val delay = backoffMs(retries)
          android.util.Log.i("AIOPE2", "Retrying SSE in ${delay}ms (attempt ${retries + 1}, had ${contentSoFar.length} chars): $sseError")
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

      // Flush any pending think-tag buffer remnants. If the round ended while still INSIDE a
      // <think> tag (e.g. the model emitted a tool call mid-thought), the remainder is REASONING,
      // not content — emit it as reasoning and drop it, so raw <think> text never bleeds into the
      // visible answer or the stored assistant history. Otherwise it's a held-back partial that
      // never resolved into a tag — treat it as content.
      if (pendingTagBuf.isNotEmpty()) {
        val leftover = pendingTagBuf.toString()
        pendingTagBuf.clear()
        if (tagState.inThinkTag) {
          if (leftover.isNotBlank()) trySend(ChatStreamChunk(reasoning = leftover))
          tagState.inThinkTag = false
        } else {
          contentSoFar.append(leftover)
        }
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
          ToolCallInfo(id = acc["id"] ?: "call_${System.nanoTime()}", name = acc["name"] ?: "", arguments = args, extraContent = acc["extra_content"])
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

        // Append the completed tool round to history for the next request.
        //
        // Preferred form is the native tool_calls/tool protocol. BUT for Google models that did
        // not return a thought_signature (always true for Gemma; sometimes true for Gemini via an
        // OpenAI-compat gateway), Gemini rejects a tool_calls turn sent back without the signature.
        // Rather than DELETE the exchange (which loses the result and loops forever), we record it
        // as a plain assistant turn + a user message carrying the results — understood by every
        // model and never rejected.
        val preToolText = contentSoFar.toString().trim()
        val useNativeProtocol = useNativeToolProtocol(model, callInfos.map { it.extraContent })

        if (useNativeProtocol) {
          rawMessages.add(
            JSONObject().apply {
              put("role", "assistant")
              put("content", preToolText.ifEmpty { "" })
              put(
                "tool_calls",
                JSONArray().apply {
                  for (c in callInfos) {
                    val tcObj = JSONObject()
                      .put("id", c.id)
                      .put("type", "function")
                      .put("function", JSONObject().put("name", c.name).put("arguments", JSONObject(c.arguments).toString()))
                    // Include extra_content (Google thought_signature) if present
                    c.extraContent?.let { tcObj.put("extra_content", JSONObject(it)) }
                    put(tcObj)
                  }
                },
              )
            },
          )
          for (r in results) {
            rawMessages.add(
              JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", r.id)
                // introspect returns whole manual pages (up to ~19KB) — give it a
                // larger cap so pages aren't truncated mid-content.
                put("content", r.result.take(if (r.name == "introspect") 24000 else 16000))
              },
            )
          }
        } else {
          // Plain-text representation for signatureless Google models.
          val assistantText = buildString {
            if (preToolText.isNotEmpty()) append(preToolText).append("\n")
            append("(called ")
            append(callInfos.joinToString(", ") { "${it.name}(${JSONObject(it.arguments)})" })
            append(")")
          }
          rawMessages.add(
            JSONObject().apply {
              put("role", "assistant")
              put("content", assistantText)
            },
          )
          val resultText = buildString {
            append("Tool results:\n")
            for (r in results) {
              append("\n[").append(r.name).append("]\n")
              append(r.result.take(if (r.name == "introspect") 24000 else 16000))
              append("\n")
            }
            append("\nUse these results to answer. Do not call the same tool again for the same request.")
          }
          rawMessages.add(
            JSONObject().apply {
              put("role", "user")
              put("content", resultText)
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
          // Feed text-parsed tool calls back as PLAIN messages, NOT via the
          // tool_calls/tool-role protocol. Reason: the Gemini sanitizer above
          // strips assistant tool_calls that lack a Google thought_signature (which
          // text-parsed calls never have) along with their tool results — so the
          // model never saw the output and re-called the tool endlessly. A plain
          // assistant turn + a user message carrying the result is understood by
          // every model and is never stripped.
          rawMessages.add(
            JSONObject().apply {
              put("role", "assistant")
              put("content", cleanContent.ifBlank { "(calling tools)" })
            },
          )
          val resultText = buildString {
            append("Tool results:\n")
            for (r in results) {
              append("\n[").append(r.name).append("]\n")
              append(r.result.take(if (r.name == "introspect") 24000 else 16000))
              append("\n")
            }
            append("\nUse these results to answer. Do not call the same tool again for the same request.")
          }
          rawMessages.add(
            JSONObject().apply {
              put("role", "user")
              put("content", resultText)
            },
          )
          contentSoFar.clear()
          continue
        }
      }

      // Detect DSML hallucination loop: model outputs tool call tokens but no valid calls
      if (tools.isNotEmpty()) {
        val text = contentSoFar.toString()
        if (text.contains("DSML") && text.contains("function_calls")) {
          val cleanContent = stripToolMarkup(text)
          if (cleanContent.isNotBlank()) {
            send(ChatStreamChunk(contentReplace = cleanContent))
          }
          // Don't loop — the model is hallucinating tool calls
          send(ChatStreamChunk(isDone = true, usage = lastUsage))
          close()
          return@callbackFlow
        }
      }

      // Done
      send(ChatStreamChunk(isDone = true, usage = lastUsage))
      close()
      return@callbackFlow
    }

    send(ChatStreamChunk(isDone = true, usage = lastUsage))
    close()

    awaitClose { }
  }.flowOn(Dispatchers.IO)

  /** Per-request cache-bust nonce. Set by buildRequestBody, read for headers/logging so the
   *  request and its response share a matching id. */
  @Volatile private var lastRequestNonce: String = ""

  private fun newNonce(): String {
    val bytes = ByteArray(12)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun buildRequestBody(messages: List<JSONObject>): String {
    val nonce = newNonce()
    lastRequestNonce = nonce
    val body = JSONObject()
    body.put("model", model)
    body.put("stream", true)
    body.put("temperature", temperature.toDouble())
    if (reasoningEffort != null && reasoningEffort != "auto") {
      body.put("reasoning_effort", reasoningEffort)
    }
    // Cache-bust: append a unique nonce to the TAIL of the last message so the prompt tail
    // differs every request, defeating server-side prompt/KV prefix caching that was serving
    // stale identity/time. The nonce is echoed as a header + logged so request/response match.
    val msgList = messages.toMutableList()
    if (msgList.isNotEmpty()) {
      val last = msgList.last()
      val content = last.optString("content", "")
      val busted = JSONObject(last.toString()).put("content", content + "\n\n<!-- req:$nonce -->")
      msgList[msgList.size - 1] = busted
    }
    body.put("messages", JSONArray().apply { for (m in msgList) put(m) })
    // Also expose the nonce as a request-scoped id and disable response caching.
    body.put("user", "req-$nonce")
    android.util.Log.e("AIOPE2", "Request: model=$model tools=${tools.size} msgs=${messages.size} reqId=$nonce")
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
              args[key] = try {
                JSONObject("{\"v\":$value}").opt("v")
              } catch (_: Exception) {
                value
              }
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

    // Pattern 7: DSML format (DeepSeek) — <｜DSML｜function_calls followed by JSON array
    if (text.contains("DSML") && text.contains("function_calls")) {
      // Extract JSON array after the DSML token
      val dsmlRegex = Regex("""(?:DSML[｜|]function_calls|DSML.{0,5}function_calls)\s*\[?\s*(\{.*?\})\s*\]?""", RegexOption.DOT_MATCHES_ALL)
      for (m in dsmlRegex.findAll(text)) {
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
    }

    // Pattern 8: <|tool_call>call:func_name{key:<|"|>value<|"|>}<tool_call|> (Gemma 4 native)
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
    "read_file", "write_file", "edit_file" -> "path"
    "list_directory", "search_files" -> "path"
    "search_web", "search_images" -> "query"
    "fetch_url", "http_request" -> "url"
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
    "schedule_task" -> "prompt"
    "cancel_schedule" -> "task_id"
    "rag_search" -> "query"
    "create_event" -> "title"
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
    // Strip DSML markers (DeepSeek)
    cleaned = Regex("""<[｜|]DSML[｜|]function_calls.*""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    cleaned = Regex("""(?:DSML[｜|]function_calls).*""", RegexOption.DOT_MATCHES_ALL).replace(cleaned, "")
    // Collapse multiple blank lines
    cleaned = Regex("""\n{3,}""").replace(cleaned, "\n\n")
    return cleaned.trim()
  }
}
