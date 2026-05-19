package com.aiope2.feature.chat.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Manages mic capture → WebSocket and playback from PCM chunks.
 * RealtimeStreaming owns this and sets the WebSocket after connection.
 */
class RealtimeAudioManager(
    private val config: AudioConfig = AudioConfig()
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var webSocket: WebSocket? = null
    private var aec: AcousticEchoCanceler? = null
    private val playbackQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    fun setWebSocket(ws: WebSocket) { webSocket = ws }

    /** Start mic capture loop — sends PCM as base64 JSON to the WebSocket */
    fun startCapture() {
        val bufSize = AudioRecord.getMinBufferSize(
            config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release(); audioRecord = null; return
        }

        // Enable AEC
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
            aec?.enabled = true
        }

        audioRecord?.startRecording()

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(bufSize)
            while (isActive) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read > 0) {
                    val encoded = android.util.Base64.encodeToString(buf.copyOf(read), android.util.Base64.NO_WRAP)
                    webSocket?.send("""{"audio":{"pcm":"$encoded","sampleRate":${config.sampleRate}}}""")
                }
            }
        }
    }

    /** Stop mic capture */
    fun stopCapture() {
        captureJob?.cancel(); captureJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    }

    /** Start playback track (call once, then feed with playAudio) */
    fun startPlayback(outputRate: Int = 24000) {
        val bufSize = AudioTrack.getMinBufferSize(
            outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(outputRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        // Dedicated playback thread to avoid garbling
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val chunk = playbackQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    audioTrack?.write(chunk, 0, chunk.size)
                }
            }
        }
    }

    /** Queue a PCM chunk for playback */
    fun playAudio(pcmData: ByteArray) {
        playbackQueue.offer(pcmData)
    }

    /** Called when model turn completes — flush remaining audio */
    fun onTurnComplete() {
        // Let queue drain naturally
    }

    /** Stop everything */
    fun stop() {
        stopCapture()
        playbackJob?.cancel(); playbackJob = null
        playbackQueue.clear()
        aec?.release(); aec = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        webSocket = null
    }
}
