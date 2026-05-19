package com.aiope2.feature.chat.engine

import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.Base64

/**
 * Bidirectional realtime voice stream.
 * Owns the WebSocket and the mic capture loop — emits StreamEvents to the collector.
 */
class RealtimeStreaming(
    private val okHttp: OkHttpClient,
    private val modelDef: ModelDef,
    private val config: ModelConfig,
    private val provider: ProviderProfile,
    private val audioManager: RealtimeAudioManager,
    private val systemPrompt: String = "",
    private val gatewayUrl: String = "wss://inf.xnet.ngo/ws/voice"
) {
    private var webSocket: WebSocket? = null

    fun createStream(): Flow<StreamEvent> = callbackFlow {
        val encodedPrompt = java.net.URLEncoder.encode(systemPrompt, "UTF-8")
        val wsUrl = "$gatewayUrl?model=${modelDef.id}&system=$encodedPrompt"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .build()

        webSocket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                trySend(StreamEvent.Connected)
                // Start mic capture → sends audio over this WS
                audioManager.setWebSocket(ws)
                audioManager.startCapture()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)

                    json.optJSONObject("audio")?.optString("pcm")?.let { b64 ->
                        if (b64.isNotBlank()) {
                            trySend(StreamEvent.AudioChunk(Base64.getDecoder().decode(b64)))
                        }
                    }

                    json.optJSONObject("text")?.optString("delta")?.let {
                        if (it.isNotBlank()) trySend(StreamEvent.TextDelta(it))
                    }

                    if (json.has("turnStart")) trySend(StreamEvent.TurnStart(json.optString("turnStart")))
                    if (json.has("turnComplete")) trySend(StreamEvent.TurnComplete)
                    if (json.has("inputTranscription")) trySend(StreamEvent.InputTranscription(json.getString("inputTranscription")))
                    if (json.has("outputTranscription")) trySend(StreamEvent.OutputTranscription(json.getString("outputTranscription")))
                    if (json.has("toolCall")) {
                        val tc = json.getJSONObject("toolCall")
                        val fcs = tc.getJSONArray("functionCalls")
                        val calls = (0 until fcs.length()).map { i ->
                            val fc = fcs.getJSONObject(i)
                            val args = mutableMapOf<String, String>()
                            fc.optJSONObject("args")?.let { a -> a.keys().forEach { k -> args[k] = a.optString(k, "") } }
                            FunctionCall(fc.getString("name"), fc.getString("id"), args)
                        }
                        trySend(StreamEvent.ToolCallEvent(calls))
                    }
                    if (json.has("error")) trySend(StreamEvent.Error(json.getString("error")))
                } catch (e: Exception) {
                    trySend(StreamEvent.Error("Parse error: ${e.message}"))
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                trySend(StreamEvent.AudioChunk(bytes.toByteArray()))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                trySend(StreamEvent.Error(t.message ?: "Connection failed"))
                close()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                trySend(StreamEvent.Disconnected)
                close()
            }
        })

        awaitClose { stop() }
    }.flowOn(Dispatchers.IO)

    /** Send text mid-conversation (e.g. barge-in or typed input) */
    fun sendText(text: String) {
        val json = JSONObject().apply {
            put("text", JSONObject().apply { put("content", text) })
        }
        webSocket?.send(json.toString())
    }

    /** Send tool execution results back */
    fun sendToolResponse(responses: List<Pair<String, String>>) {
        val json = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", org.json.JSONArray().apply {
                    responses.forEach { (id, result) ->
                        put(JSONObject().apply {
                            put("id", id)
                            put("response", JSONObject().apply { put("result", result) })
                        })
                    }
                })
            })
        }
        webSocket?.send(json.toString())
    }

    /** Signal end of user turn */
    fun endTurn() {
        webSocket?.send("""{"turnEnd":true}""")
    }

    /** Tear down everything */
    fun stop() {
        audioManager.stopCapture()
        webSocket?.close(1000, "Client ended")
        webSocket = null
    }
}
