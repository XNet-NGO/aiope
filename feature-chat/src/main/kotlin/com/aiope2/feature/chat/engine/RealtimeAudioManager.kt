package com.aiope2.feature.chat.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket

/**
 * Manages Android audio capture and playback for realtime voice
 */
class RealtimeAudioManager(private val context: Context) {

  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var captureJob: Job? = null
  private var playbackJob: Job? = null

  var isRecording = false
    private set
  var isPlaying = false
    private set

  private var webSocket: WebSocket? = null
  private var config: AudioConfig = AudioConfig()

  /**
   * Start audio capture and optional playback
   */
  fun start(
    audioConfig: AudioConfig = AudioConfig(),
    ws: WebSocket? = null,
    enablePlayback: Boolean = false
  ) {
    config = audioConfig
    webSocket = ws
    isRecording = true

    // Start AudioRecord for capture
    startCapture()

    // Optionally start AudioTrack for playback
    if (enablePlayback) {
      startPlayback()
    }
  }

  /**
   * Start PCM capture from microphone
   */
  private fun startCapture() {
    val bufferSize = AudioRecord.getMinBufferSize(
      config.sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )

    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      return
    }

    audioRecord = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      config.sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize * 2
    )

    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
      audioRecord?.release()
      audioRecord = null
      return
    }

    audioRecord?.startRecording()
    isRecording = true

    // Capture loop - reads PCM and sends to WebSocket
    captureJob = CoroutineScope(Dispatchers.IO).launch {
      val buffer = ByteArray(bufferSize)
      while (isRecording && isActive) {
        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
        if (read > 0) {
          val pcmChunk = buffer.copyOf(read)
          // Send to WebSocket if available
          webSocket?.let { ws ->
            sendToWebSocket(ws, pcmChunk)
          }
        }
      }
    }
  }

  /**
   * Start PCM playback to speaker
   */
  fun startPlayback(sampleRate: Int = config.sampleRate) {
    val bufferSize = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )

    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(bufferSize * 2)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()

    if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
      audioTrack?.release()
      audioTrack = null
      return
    }

    audioTrack?.play()
    isPlaying = true
  }

  /**
   * Play a PCM audio chunk
   */
  fun playAudio(pcmData: ByteArray) {
    if (isPlaying && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
      CoroutineScope(Dispatchers.IO).launch {
        audioTrack?.write(pcmData, 0, pcmData.size)
      }
    }
  }

  /**
   * Stop both capture and playback
   */
  fun stop() {
    isRecording = false
    isPlaying = false

    captureJob?.cancel()
    captureJob = null

    audioRecord?.stop()
    audioRecord?.release()
    audioRecord = null

    audioTrack?.stop()
    audioTrack?.release()
    audioTrack = null
  }

  /**
   * Set WebSocket for sending audio (can be changed mid-session)
   */
  fun setWebSocket(ws: WebSocket?) {
    webSocket = ws
  }

  /**
   * Get current audio amplitude for visualization (0.0 - 1.0)
   */
  fun getAmplitude(): Float {
    if (!isRecording || audioRecord == null) return 0f

    val buffer = ByteArray(1024)
    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
    if (read <= 0) return 0f

    // Calculate RMS amplitude
    var sum = 0L
    for (i in 0 until read step 2) {
      val sample = (buffer[i].toInt() and 0xFF) or (buffer.getOrNull(i + 1)?.toInt()?.shl(8) ?: 0)
      val signed = if (sample > 32767) sample - 65536 else sample
      sum += (signed * signed).toLong()
    }
    val rms = kotlin.math.sqrt((sum / (read / 2)).toDouble())
    return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
  }

  private suspend fun sendToWebSocket(ws: WebSocket, pcmData: ByteArray) {
    withContext(Dispatchers.IO) {
      // Format: {"audio": {"pcm": "base64...", "sampleRate": 16000}}
      val encoded = android.util.Base64.encodeToString(
        pcmData,
        android.util.Base64.NO_WRAP
      )
      val json = """{"audio": {"pcm": "$encoded", "sampleRate": ${config.sampleRate}}}"""
      ws.send(json)
    }
  }
}
