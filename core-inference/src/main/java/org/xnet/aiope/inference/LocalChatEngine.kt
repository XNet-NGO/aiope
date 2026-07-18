package org.xnet.aiope.inference

import android.content.Context
import java.io.File

/**
 * On-device text generation engine using Falcon-H1-Tiny-Multilingual-100M.
 * Used for: title generation, translation, and simple RAG extraction.
 * NOT for general chat (too small for coherent multi-turn conversation).
 */
class LocalChatEngine(private val context: Context) {

    private var engine: LlamaEngine? = null
    private val modelName = "Falcon-H1-Tiny-Multilingual-100M-Instruct.Q4_K_M.gguf"

    val isLoaded: Boolean get() = engine != null

    @Synchronized
    fun ensureLoaded() {
        if (engine != null) return
        val modelFile = File(context.filesDir, "models/$modelName")
        if (!modelFile.exists()) {
            modelFile.parentFile?.mkdirs()
            context.assets.open("models/$modelName").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val e = LlamaEngine()
        e.loadModel(modelFile.absolutePath, contextSize = 2048, nThreads = 4, embedding = false)
        engine = e
    }

    fun unload() {
        engine?.close()
        engine = null
    }

    /**
     * Generate text with the local model.
     * @param prompt Full formatted prompt (with system + user message)
     * @param maxTokens Maximum tokens to generate
     * @return Generated text or null on failure
     */
    fun generate(prompt: String, maxTokens: Int = 128, temperature: Float = 0.0f): String? {
        ensureLoaded()
        val e = engine ?: return null
        val sb = StringBuilder()
        val success = e.generate(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 1.0f,
            repeatPenalty = 1.0f,
            callback = object : LlamaEngine.StreamCallback {
                override fun onToken(token: String): Boolean {
                    sb.append(token)
                    return true
                }
                override fun onComplete(tokensPerSec: Float, tokenCount: Int) {}
            }
        )
        return if (success) sb.toString().trim() else null
    }

    /**
     * Generate a conversation title from the first user message.
     */
    fun generateTitle(userMessage: String, language: String = "en"): String? {
        val prompt = buildString {
            append("<|im_start|>system\nYou generate short conversation titles. Output ONLY a title of 3-6 words. No explanation, no quotes.<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userMessage.take(500))
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        return generate(prompt, maxTokens = 20, temperature = 0.0f)?.let { raw ->
            raw.replace(Regex("<\\|.*?\\|>"), "")
                .replace(Regex("^[\"']|[\"']$"), "")
                .lines().first()
                .trim()
                .take(60)
                .ifBlank { null }
        }
    }

    /**
     * Translate text to the target language.
     * Supports 18 languages: English, Czech, German, Spanish, French, Hindi, Italian,
     * Japanese, Korean, Dutch, Polish, Portuguese, Romanian, Russian, Swedish, Urdu, Chinese.
     */
    fun translate(text: String, targetLanguage: String): String? {
        val supportedLanguages = setOf(
            "english", "czech", "german", "spanish", "french", "hindi", "italian",
            "japanese", "korean", "dutch", "polish", "portuguese", "romanian",
            "russian", "swedish", "urdu", "chinese"
        )
        if (targetLanguage.lowercase() !in supportedLanguages) return null
        val safeText = text.take(800)

        ensureLoaded()
        val e = engine ?: return null

        // Use target language name in the target language itself as a stronger cue
        val langCue = when (targetLanguage.lowercase()) {
            "spanish" -> "Traduce al español"
            "french" -> "Traduisez en français"
            "german" -> "Übersetze ins Deutsche"
            "italian" -> "Traduci in italiano"
            "portuguese" -> "Traduza para português"
            "dutch" -> "Vertaal naar het Nederlands"
            "polish" -> "Przetłumacz na polski"
            "czech" -> "Přelož do češtiny"
            "romanian" -> "Traduce în română"
            "swedish" -> "Översätt till svenska"
            "russian" -> "Переведи на русский"
            "chinese" -> "翻译成中文"
            "japanese" -> "日本語に翻訳してください"
            "korean" -> "한국어로 번역해주세요"
            "hindi" -> "हिंदी में अनुवाद करें"
            "urdu" -> "اردو میں ترجمہ کریں"
            else -> "Translate to $targetLanguage"
        }

        val prompt = buildString {
            append("<|im_start|>system\n$langCue. Output ONLY the translation.<|im_end|>\n")
            append("<|im_start|>user\n$safeText<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        return generate(prompt, maxTokens = 256, temperature = 0.0f)?.let { raw ->
            raw.replace(Regex("<\\|.*?\\|>"), "").trim().ifBlank { null }
        }
    }

    /**
     * Extract/summarize a RAG answer from retrieved context chunks.
     */
    fun ragExtract(query: String, contextChunks: List<String>): String? {
        val ctx = contextChunks.joinToString("\n---\n") { it.take(500) }
        val prompt = buildString {
            append("<|im_start|>system\nAnswer using ONLY the provided context. Be concise. If the answer is not in the context, say \"Not found in documents.\"<|im_end|>\n")
            append("<|im_start|>user\nContext:\n$ctx\n\nQuestion: $query<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        return generate(prompt, maxTokens = 256, temperature = 0.0f)?.let { raw ->
            raw.replace(Regex("<\\|.*?\\|>"), "").trim().ifBlank { null }
        }
    }
}
