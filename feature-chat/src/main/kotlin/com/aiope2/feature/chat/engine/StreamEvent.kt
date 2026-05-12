package com.aiope2.feature.chat.engine

/**
 * Stream events - unified for both SSE text streaming and WebSocket bidirectional audio
 */
sealed class StreamEvent {
  data class TextDelta(val text: String) : StreamEvent()
  data class AudioChunk(val pcmData: ByteArray) : StreamEvent()
  data class TurnStart(val turnId: String) : StreamEvent()
  data object TurnComplete : StreamEvent()
  data class Error(val message: String) : StreamEvent()
  data object Connected : StreamEvent()
  data object Disconnected : StreamEvent()
}

/**
 * Audio configuration for realtime voice
 */
data class AudioConfig(
  val sampleRate: Int = 16000,
  val audioInputType: String = "LINEAR_PCM",
  val channelMask: Int = 1, // MONO
  val encoding: Int = 2, // PCM_16BIT
)

/**
 * Callback interface for streaming - extended for audio
 */
interface StreamCallback {
  fun onTextDelta(text: String) {}
  fun onAudioChunk(data: ByteArray) {}
  fun onTurnStart(turnId: String) {}
  fun onTurnComplete() {}
  fun onError(msg: String) {}
  fun onConnected() {}
  fun onDisconnected() {}
}
