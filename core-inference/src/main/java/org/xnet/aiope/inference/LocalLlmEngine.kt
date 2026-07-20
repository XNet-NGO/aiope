package org.xnet.aiope.inference

import android.content.Context
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM engine using LiteRT-LM.
 * Handles text generation with GPU/NPU acceleration on Tensor G5.
 *
 * Based on proven patterns from aiope-inf which works with Pixel 10 Pro XL TPU.
 */
class LocalLlmEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isLoaded: Boolean get() = engine != null

    /**
     * Load a .litertlm model with automatic backend fallback: NPU → GPU → CPU.
     */
    suspend fun load(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            close()
            val file = File(modelPath)
            // NPU for G5-compiled models, GPU otherwise
            val backend = if (file.name.contains("Google_Tensor_G5", ignoreCase = true)) {
                Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
            } else {
                Backend.GPU()
            }

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 4096,
                cacheDir = context.cacheDir.resolve("litert_cache").apply { mkdirs() }.absolutePath
            )
            engine = Engine(config)
            engine!!.initialize()
            // Create default conversation
            createConversation()
            android.util.Log.i("LocalLlm", "Loaded: ${file.name} backend=$backend")
            true
        } catch (e: Exception) {
            android.util.Log.e("LocalLlm", "Load failed, trying CPU fallback", e)
            // Fallback to CPU
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxNumTokens = 4096,
                    cacheDir = context.cacheDir.resolve("litert_cache").apply { mkdirs() }.absolutePath
                )
                engine = Engine(config)
                engine!!.initialize()
                createConversation()
                android.util.Log.i("LocalLlm", "Loaded with CPU fallback")
                true
            } catch (e2: Exception) {
                android.util.Log.e("LocalLlm", "CPU fallback also failed", e2)
                engine = null
                false
            }
        }
    }

    /**
     * Create or reset conversation with given parameters.
     */
    fun createConversation(
        systemPrompt: String? = null,
        temperature: Double = 0.7,
        topK: Int = 40,
        topP: Double = 0.9
    ) {
        conversation?.close()
        conversation = engine?.createConversation(ConversationConfig(
            systemInstruction = if (systemPrompt != null) Contents.of(systemPrompt) else null,
            samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature)
        ))
    }

    /**
     * Stream tokens as a Kotlin Flow.
     * Uses MessageCallback with Content.Text extraction (proven working pattern).
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val conv = conversation ?: run { close(); return@callbackFlow }
        conv.sendMessageAsync(
            Message.user(prompt),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    message.contents?.contents?.filterIsInstance<Content.Text>()?.forEach {
                        trySend(it.text)
                    }
                }
                override fun onDone() { close() }
                override fun onError(e: Throwable) { close(Exception(e)) }
            }
        )
        awaitClose { conv.cancelProcess() }
    }

    /**
     * Synchronous generation (blocks until complete).
     */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext ""
        val response = conv.sendMessage(Message.user(prompt))
        response.contents?.contents?.filterIsInstance<Content.Text>()?.joinToString("") { it.text } ?: ""
    }

    /**
     * Generate a conversation title.
     */
    suspend fun generateTitle(userMessage: String): String? {
        val e = engine ?: return null
        val config = ConversationConfig(
            systemInstruction = Contents.of("You generate short conversation titles. Output ONLY a title of 3-6 words. No explanation, no quotes."),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
        )
        return e.createConversation(config).use { conv ->
            try {
                val response = conv.sendMessage(Message.user(userMessage.take(500)))
                response.contents?.contents?.filterIsInstance<Content.Text>()
                    ?.joinToString("") { it.text }?.trim()?.take(60)?.ifBlank { null }
            } catch (_: Exception) { null }
        }
    }

    /**
     * Translate text.
     */
    suspend fun translate(text: String, targetLanguage: String): String? {
        val e = engine ?: return null
        val langCue = when (targetLanguage.lowercase()) {
            "spanish" -> "Traduce al español. Output ONLY the translation."
            "french" -> "Traduisez en français. Output ONLY the translation."
            "german" -> "Übersetze ins Deutsche. Output ONLY the translation."
            "italian" -> "Traduci in italiano. Output ONLY the translation."
            "portuguese" -> "Traduza para português. Output ONLY the translation."
            "russian" -> "Переведи на русский. Output ONLY the translation."
            "chinese" -> "翻译成中文。只输出翻译结果。"
            "japanese" -> "日本語に翻訳してください。翻訳のみ出力してください。"
            "korean" -> "한국어로 번역해주세요. 번역만 출력하세요."
            else -> "Translate to $targetLanguage. Output ONLY the translation."
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(langCue),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
        )
        return e.createConversation(config).use { conv ->
            try {
                val response = conv.sendMessage(Message.user(text.take(2000)))
                response.contents?.contents?.filterIsInstance<Content.Text>()
                    ?.joinToString("") { it.text }?.trim()?.ifBlank { null }
            } catch (_: Exception) { null }
        }
    }

    fun stop() { conversation?.cancelProcess() }

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
