package ngo.xnet.aiope.feature.chat.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import ngo.xnet.aiope.core.network.ModelConfig
import ngo.xnet.aiope.core.network.ModelDef
import ngo.xnet.aiope.core.network.ProviderProfile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Bidirectional realtime voice stream.
 * Supports two protocols:
 * 1. AIOPE Gateway (wss://inf.xnet.ngo/ws/voice) — custom protocol
 * 2. Google AI Studio Live API (direct) — native Gemini BidiGenerateContent
 */
class RealtimeStreaming(
  private val okHttp: OkHttpClient,
  private val modelDef: ModelDef,
  private val config: ModelConfig,
  private val provider: ProviderProfile,
  private val audioManager: RealtimeAudioManager,
  private val systemPrompt: String = "",
  private val voiceName: String = "Aoede",
  private val tools: List<StreamingOrchestrator.ToolDef> = emptyList(),
  private val gatewayUrl: String = "wss://inf.xnet.ngo/ws/voice",
) {
  private var webSocket: WebSocket? = null

  private val isGoogleDirect: Boolean
    get() = provider.effectiveApiBase().contains("generativelanguage.googleapis.com")

  /** Extract the actual Google model name from potentially prefixed IDs */
  private fun googleModelId(): String {
    val id = modelDef.id
    // Strip provider prefix like "google-ai-studio/"
    val stripped = if (id.contains("/") && !id.startsWith("models/") && !id.startsWith("@")) {
      id.substringAfter("/")
    } else {
      id
    }
    return if (stripped.startsWith("models/")) stripped else "models/$stripped"
  }

  fun createStream(): Flow<StreamEvent> = callbackFlow {
    val (wsUrl, request) = if (isGoogleDirect) {
      buildGoogleConnection()
    } else {
      buildGatewayConnection()
    }

    webSocket = okHttp.newWebSocket(
      request,
      object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
          android.util.Log.i("VoiceLive", "WebSocket opened: ${response.code} isGoogleDirect=$isGoogleDirect model=${modelDef.id}")
          if (isGoogleDirect) {
            sendGoogleSetup(ws)
            audioManager.googleDirect = true
            // Don't start capture yet — wait for setupComplete
            audioManager.setWebSocket(ws)
          } else {
            sendGatewaySetup(ws)
            audioManager.googleDirect = false
            audioManager.setWebSocket(ws)
            audioManager.startCapture()
            trySend(StreamEvent.Connected)
          }
        }

        override fun onMessage(ws: WebSocket, text: String) {
          try {
            if (isGoogleDirect) {
              val json = JSONObject(text)
              // Start capture only after setup is acknowledged
              if (json.has("setupComplete")) {
                android.util.Log.i("VoiceLive", "setupComplete received, starting capture")
                audioManager.startCapture()
                trySend(StreamEvent.Connected)
                return
              }
              if (text.length < 200) android.util.Log.d("VoiceLive", "msg: $text")
              parseGoogleMessages(text).forEach { trySend(it) }
            } else {
              parseGatewayMessage(text)?.let { trySend(it) }
            }
          } catch (e: Exception) {
            android.util.Log.e("VoiceLive", "Parse error: ${e.message}")
            trySend(StreamEvent.Error("Parse error: ${e.message}"))
          }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
          // Google Live API sends all responses as binary frames
          if (isGoogleDirect) {
            val text = bytes.utf8()
            try {
              val json = JSONObject(text)
              if (json.has("setupComplete")) {
                android.util.Log.i("VoiceLive", "setupComplete received (binary), starting capture")
                audioManager.startCapture()
                trySend(StreamEvent.Connected)
                return
              }
              // Log non-audio messages for debugging transcription
              if (!text.contains("inlineData")) {
                android.util.Log.i("VoiceLive", "recv: ${text.take(400)}")
              }
              parseGoogleMessages(text).forEach { trySend(it) }
            } catch (e: Exception) {
              android.util.Log.e("VoiceLive", "Binary parse error: ${e.message}")
            }
          } else {
            trySend(StreamEvent.AudioChunk(bytes.toByteArray()))
          }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
          trySend(StreamEvent.Error(t.message ?: "Connection failed"))
          close()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
          trySend(StreamEvent.Disconnected)
          close()
        }
      },
    )

    // Heartbeat: ping every 15s to prevent NAT timeout
    val heartbeatTimer = java.util.Timer("ws-heartbeat", true)
    heartbeatTimer.scheduleAtFixedRate(
      object : java.util.TimerTask() {
        override fun run() {
          try {
            webSocket?.send("")
          } catch (_: Exception) {}
        }
      },
      15000L,
      15000L,
    )

    awaitClose {
      heartbeatTimer.cancel()
      stop()
    }
  }.flowOn(Dispatchers.IO)

  // ══════════════════════════════════════════════════════════════
  // CONNECTION BUILDERS
  // ══════════════════════════════════════════════════════════════

  private fun buildGatewayConnection(): Pair<String, Request> {
    val url = "$gatewayUrl?model=${modelDef.id}"
    val request = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer ${provider.apiKey}")
      .build()
    return url to request
  }

  private fun buildGoogleConnection(): Pair<String, Request> {
    // Google Live API: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${provider.apiKey}"
    val request = Request.Builder().url(url).build()
    return url to request
  }

  // ══════════════════════════════════════════════════════════════
  // SETUP MESSAGES
  // ══════════════════════════════════════════════════════════════

  private fun sendGatewaySetup(ws: WebSocket) {
    if (systemPrompt.isNotBlank()) {
      val setup = JSONObject().apply {
        put(
          "setup",
          JSONObject().apply {
            put("systemPrompt", systemPrompt)
            put("voiceName", voiceName)
          },
        )
      }
      ws.send(setup.toString())
    }
  }

  private fun sendGoogleSetup(ws: WebSocket) {
    val setup = JSONObject().apply {
      put(
        "setup",
        JSONObject().apply {
          put("model", googleModelId())
          android.util.Log.i("VoiceLive", "setup model=${googleModelId()}")
          put(
            "generationConfig",
            JSONObject().apply {
              put("responseModalities", JSONArray().put("AUDIO"))
              put(
                "speechConfig",
                JSONObject().apply {
                  put(
                    "voiceConfig",
                    JSONObject().apply {
                      put(
                        "prebuiltVoiceConfig",
                        JSONObject().apply {
                          put("voiceName", voiceName)
                        },
                      )
                    },
                  )
                },
              )
            },
          )
          if (systemPrompt.isNotBlank()) {
            put(
              "systemInstruction",
              JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
              },
            )
          }
          // No outputAudioTranscription/inputAudioTranscription — gateway works without them
          put("outputAudioTranscription", JSONObject())
          put("inputAudioTranscription", JSONObject())
          val toolDecls = buildGoogleToolDeclarations()
          android.util.Log.i("VoiceLive", "tool declarations: ${toolDecls.length()}")
          if (toolDecls.length() > 0) {
            put(
              "tools",
              JSONArray().put(
                JSONObject().apply {
                  put("functionDeclarations", toolDecls)
                },
              ),
            )
          }
        },
      )
    }
    android.util.Log.i("VoiceLive", "sendGoogleSetup: ${setup.toString().take(500)}")
    ws.send(setup.toString())
  }

  private fun buildGoogleToolDeclarations(): JSONArray {
    val decls = JSONArray()
    for (t in tools.take(25)) {
      try {
        val params = JSONObject(t.parameters.toString())
        if (params.optJSONObject("properties")?.length() == 0) {
          params.remove("properties")
          params.remove("required")
        }
        decls.put(
          JSONObject().apply {
            put("name", t.name)
            put("description", t.description)
            put("parameters", params)
          },
        )
      } catch (_: Exception) {}
    }
    return decls
  }

  // ══════════════════════════════════════════════════════════════
  // MESSAGE PARSING
  // ══════════════════════════════════════════════════════════════

  private fun parseGatewayMessage(text: String): StreamEvent? {
    val json = JSONObject(text)

    json.optJSONObject("audio")?.optString("pcm")?.let { b64 ->
      if (b64.isNotBlank()) return StreamEvent.AudioChunk(Base64.getDecoder().decode(b64))
    }
    json.optJSONObject("text")?.optString("delta")?.let {
      if (it.isNotBlank()) return StreamEvent.TextDelta(it)
    }
    if (json.has("turnStart")) return StreamEvent.TurnStart(json.optString("turnStart"))
    if (json.has("turnComplete")) return StreamEvent.TurnComplete
    if (json.has("inputTranscription")) return StreamEvent.InputTranscription(json.getString("inputTranscription"))
    if (json.has("outputTranscription")) return StreamEvent.OutputTranscription(json.getString("outputTranscription"))
    if (json.has("toolCall")) {
      val tc = json.getJSONObject("toolCall")
      val fcs = tc.getJSONArray("functionCalls")
      val calls = (0 until fcs.length()).map { i ->
        val fc = fcs.getJSONObject(i)
        val args = mutableMapOf<String, String>()
        fc.optJSONObject("args")?.let { a -> a.keys().forEach { k -> args[k] = a.optString(k, "") } }
        FunctionCall(fc.getString("name"), fc.getString("id"), args)
      }
      return StreamEvent.ToolCallEvent(calls)
    }
    if (json.has("error")) return StreamEvent.Error(json.getString("error"))
    return null
  }

  private fun parseGoogleMessages(text: String): List<StreamEvent> {
    val events = mutableListOf<StreamEvent>()
    val json = JSONObject(text)

    // Google Live API response format
    val serverContent = json.optJSONObject("serverContent")
    if (serverContent != null) {
      val modelTurn = serverContent.optJSONObject("modelTurn")
      if (modelTurn != null) {
        val parts = modelTurn.optJSONArray("parts")
        if (parts != null) {
          // Gemini 3.1: process ALL parts (audio + transcript can be in same event)
          for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            // Audio data
            part.optJSONObject("inlineData")?.let { inline ->
              val mimeType = inline.optString("mimeType", "")
              if (mimeType.startsWith("audio/")) {
                val b64 = inline.optString("data", "")
                if (b64.isNotBlank()) {
                  events.add(StreamEvent.AudioChunk(Base64.getDecoder().decode(b64)))
                }
              }
            }

            // Text content (transcript inline with audio for 3.1)
            part.optString("text", "").let {
              if (it.isNotBlank()) events.add(StreamEvent.TextDelta(it))
            }

            // Function call
            part.optJSONObject("functionCall")?.let { fc ->
              val name = fc.getString("name")
              val id = fc.optString("id", "call_${System.nanoTime()}")
              val args = mutableMapOf<String, String>()
              fc.optJSONObject("args")?.let { a -> a.keys().forEach { k -> args[k] = a.optString(k, "") } }
              events.add(StreamEvent.ToolCallEvent(listOf(FunctionCall(name, id, args))))
            }
          }
        }
      }

      // Turn complete
      if (serverContent.optBoolean("turnComplete", false)) {
        events.add(StreamEvent.TurnComplete)
      }

      // Input transcription
      serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
        if (it.isNotBlank()) events.add(StreamEvent.InputTranscription(it))
      }

      // Output transcription
      serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
        if (it.isNotBlank()) events.add(StreamEvent.OutputTranscription(it))
      }
    }

    // Check for transcription at top level (some API versions)
    json.optJSONObject("outputTranscription")?.optString("text")?.let {
      if (it.isNotBlank()) events.add(StreamEvent.OutputTranscription(it))
    }
    json.optJSONObject("inputTranscription")?.optString("text")?.let {
      if (it.isNotBlank()) events.add(StreamEvent.InputTranscription(it))
    }

    // Tool call (top-level message type per API spec)
    json.optJSONObject("toolCall")?.let { tc ->
      val fcs = tc.optJSONArray("functionCalls")
      if (fcs != null) {
        val calls = (0 until fcs.length()).map { i ->
          val fc = fcs.getJSONObject(i)
          val name = fc.getString("name")
          val id = fc.optString("id", "call_${System.nanoTime()}")
          val args = mutableMapOf<String, String>()
          fc.optJSONObject("args")?.let { a -> a.keys().forEach { k -> args[k] = a.optString(k, "") } }
          FunctionCall(name, id, args)
        }
        events.add(StreamEvent.ToolCallEvent(calls))
      }
    }

    return events
  }

  // ══════════════════════════════════════════════════════════════
  // PUBLIC API
  // ══════════════════════════════════════════════════════════════

  /** Send text mid-conversation */
  fun sendText(text: String) {
    val json = if (isGoogleDirect) {
      JSONObject().apply {
        put(
          "clientContent",
          JSONObject().apply {
            put(
              "turns",
              JSONArray().put(
                JSONObject().apply {
                  put("role", "user")
                  put("parts", JSONArray().put(JSONObject().put("text", text)))
                },
              ),
            )
            put("turnComplete", true)
          },
        )
      }
    } else {
      JSONObject().apply {
        put("text", JSONObject().apply { put("content", text) })
      }
    }
    webSocket?.send(json.toString())
  }

  /** Send multimodal content (text + images/docs) through the live session */
  fun sendClientContent(parts: List<JSONObject>) {
    val json = if (isGoogleDirect) {
      // For Gemini 3.1, use realtimeInput for text or clientContent for multimodal
      JSONObject().apply {
        put(
          "clientContent",
          JSONObject().apply {
            put(
              "turns",
              JSONArray().put(
                JSONObject().apply {
                  put("role", "user")
                  put("parts", JSONArray().apply { parts.forEach { put(it) } })
                },
              ),
            )
            put("turnComplete", true)
          },
        )
      }
    } else {
      // Gateway: send as text (images not supported via gateway voice)
      val textPart = parts.firstOrNull { it.has("text") }?.optString("text") ?: ""
      JSONObject().apply {
        put("text", JSONObject().apply { put("content", textPart) })
      }
    }
    webSocket?.send(json.toString())
  }

  /** Send audio chunk (called by AudioManager) */
  fun sendAudio(pcmBase64: String) {
    val json = if (isGoogleDirect) {
      JSONObject().apply {
        put(
          "realtimeInput",
          JSONObject().apply {
            put(
              "audio",
              JSONObject().apply {
                put("mimeType", "audio/pcm;rate=16000")
                put("data", pcmBase64)
              },
            )
          },
        )
      }
    } else {
      JSONObject().apply {
        put("audio", JSONObject().apply { put("pcm", pcmBase64) })
      }
    }
    webSocket?.send(json.toString())
  }

  /** Send tool execution results back */
  fun sendToolResponse(responses: List<Pair<String, String>>) {
    val json = if (isGoogleDirect) {
      JSONObject().apply {
        put(
          "toolResponse",
          JSONObject().apply {
            put(
              "functionResponses",
              JSONArray().apply {
                responses.forEach { (id, result) ->
                  put(
                    JSONObject().apply {
                      put("id", id)
                      put("response", JSONObject().apply { put("output", JSONObject().apply { put("result", result) }) })
                    },
                  )
                }
              },
            )
          },
        )
      }
    } else {
      JSONObject().apply {
        put(
          "toolResponse",
          JSONObject().apply {
            put(
              "functionResponses",
              JSONArray().apply {
                responses.forEach { (id, result) ->
                  put(
                    JSONObject().apply {
                      put("id", id)
                      put("response", JSONObject().apply { put("result", result) })
                    },
                  )
                }
              },
            )
          },
        )
      }
    }
    webSocket?.send(json.toString())
  }

  /** Signal end of user turn */
  fun endTurn() {
    if (isGoogleDirect) {
      webSocket?.send("""{"clientContent":{"turnComplete":true}}""")
    } else {
      webSocket?.send("""{"turnEnd":true}""")
    }
  }

  /** Tear down everything */
  fun stop() {
    audioManager.stopCapture()
    webSocket?.close(1000, "Client ended")
    webSocket = null
  }
}
