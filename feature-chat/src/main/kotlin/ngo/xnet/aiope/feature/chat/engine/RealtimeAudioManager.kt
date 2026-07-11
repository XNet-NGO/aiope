package ngo.xnet.aiope.feature.chat.engine

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
    private var sharedSessionId: Int = 0

    fun setWebSocket(ws: WebSocket) { webSocket = ws }

    /** Start mic capture loop — sends PCM as base64 JSON to the WebSocket */
    fun startCapture() {
        // Guard against re-entry: release existing capture resources first
        if (audioRecord != null) {
            captureJob?.cancel(); captureJob = null
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        }

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

        // Share session ID with AudioTrack for proper AEC pairing
        sharedSessionId = audioRecord!!.audioSessionId

        // Enable AEC
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sharedSessionId)
            aec?.enabled = true
        }

        // Rebuild AudioTrack with shared session ID for proper AEC pairing
        if (audioTrack != null && audioTrack?.audioSessionId != sharedSessionId) {
            playbackJob?.cancel()
            playbackJob = null
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            playbackQueue.clear()
            startPlayback()
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

    /** Start playback track. If called before startCapture, will be re-initialized with shared session when capture starts. */
    fun startPlayback(outputRate: Int = 24000) {
        val bufSize = AudioTrack.getMinBufferSize(
            outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(outputRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (sharedSessionId != 0) builder.setSessionId(sharedSessionId)
        audioTrack = builder.build()
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
