package com.aiope2.feature.chat.engine

import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.Base64

/**
 * Bidirectional streaming for realtime voice - WebSocket based
 */
class RealtimeStreaming(
  private val okHttp: OkHttpClient,
  private val modelDef: ModelDef,
  private val config: ModelConfig,
  private val provider: ProviderProfile,
  private val gatewayUrl: String = "wss://inf.xnet.ngo/ws/voice"
) {
  /**
   * Creates a bidirectional stream for realtime voice
   * Returns Flow<StreamEvent> - emits text deltas and audio chunks from server
   * Server can send audio via WebSocket
   */
  fun createStream(): Flow<StreamEvent> = callbackFlow {
    val callback = object : StreamCallback {
      override fun onTextDelta(text: String) { trySend(StreamEvent.TextDelta(text)) }
      override fun onAudioChunk(data: ByteArray) { trySend(StreamEvent.AudioChunk(data)) }
      override fun onTurnStart(turnId: String) { trySend(StreamEvent.TurnStart(turnId)) }
      override fun onTurnComplete() { trySend(StreamEvent.TurnComplete) }
      override fun onError(msg: String) { trySend(StreamEvent.Error(msg)); close() }
      override fun onConnected() { trySend(StreamEvent.Connected) }
      override fun onDisconnected() { trySend(StreamEvent.Disconnected) }
    }

    val wsUrl = "$gatewayUrl?model=${modelDef.id}"
    val request = Request.Builder()
      .url(wsUrl)
      .addHeader("Authorization", "Bearer ${provider.apiKey}")
      .addHeader("X-Model-Id", modelDef.id)
      .build()

    val webSocket = okHttp.newWebSocket(request, RealtimeWebSocketListener(callback))

    awaitClose {
      webSocket.close(1000, "Client disconnected")
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Send audio data to the server
   */
  fun sendAudio(webSocket: WebSocket, pcmData: ByteArray): Boolean {
    val audioJson = JSONObject().apply {
      put("audio", JSONObject().apply {
        put("pcm", Base64.getEncoder().encodeToString(pcmData))
        put("sampleRate", modelDef.sampleRate)
        put("inputType", modelDef.audioInputType)
      })
    }
    return webSocket.send(audioJson.toString())
  }

  /**
   * Send text prompt to start/interrupt the conversation
   */
  fun sendText(webSocket: WebSocket, text: String): Boolean {
    val textJson = JSONObject().apply {
      put("text", JSONObject().apply {
        put("content", text)
      })
    }
    return webSocket.send(textJson.toString())
  }

  /**
   * Signal end of audio input (turn-taking)
   */
  fun endTurn(webSocket: WebSocket): Boolean {
    val turnEnd = JSONObject().apply {
      put("turnEnd", true)
    }
    return webSocket.send(turnEnd.toString())
  }
}

/**
 * WebSocket listener for realtime voice
 */
class RealtimeWebSocketListener(
  private val callback: StreamCallback
) : WebSocketListener() {

  override fun onOpen(webSocket: WebSocket, response: Response) {
    callback.onConnected()
  }

  override fun onMessage(webSocket: WebSocket, text: String) {
    try {
      val json = JSONObject(text)

      // Text delta from model
      json.optJSONObject("text")?.optString("delta")?.let {
        if (it.isNotBlank()) callback.onTextDelta(it)
      }

      // Audio chunk from model (realtime speech synthesis)
      json.optJSONObject("audio")?.optString("pcm")?.let { base64 ->
        if (base64.isNotBlank()) {
          val pcmData = Base64.getDecoder().decode(base64)
          callback.onAudioChunk(pcmData)
        }
      }

      // Turn events
      if (json.has("turnStart")) {
        callback.onTurnStart(json.optString("turnStart"))
      }
      if (json.has("turnComplete")) {
        callback.onTurnComplete()
      }
      if (json.has("error")) {
        callback.onError(json.optString("error"))
      }
    } catch (e: Exception) {
      callback.onError("Parse error: ${e.message}")
    }
  }

  override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    // Binary audio data
    callback.onAudioChunk(bytes.toByteArray())
  }

  override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    callback.onError(t.message ?: "WebSocket connection failed")
  }

  override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
    callback.onDisconnected()
  }
}
