package com.aiope2.feature.chat.engine

/**
 * Stream events for realtime voice sessions.
 */
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class AudioChunk(val pcmData: ByteArray) : StreamEvent()
    data class TurnStart(val turnId: String) : StreamEvent()
    data object TurnComplete : StreamEvent()
    data class InputTranscription(val text: String) : StreamEvent()
    data class OutputTranscription(val text: String) : StreamEvent()
    data class ToolCallEvent(val functionCalls: List<FunctionCall>) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    data object Connected : StreamEvent()
    data object Disconnected : StreamEvent()
}

data class FunctionCall(val name: String, val id: String, val args: Map<String, String>)

data class AudioConfig(
    val sampleRate: Int = 16000,
    val channelMask: Int = 1,
    val encoding: Int = 2,
)
