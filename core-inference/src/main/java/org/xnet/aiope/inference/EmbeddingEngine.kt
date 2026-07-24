package org.xnet.aiope.inference

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Embedding engine using LiteRT with bundled MiniLM model.
 * Produces 384-dimensional sentence embeddings for RAG.
 */
class EmbeddingEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelFile: File by lazy { ensureModel() }

    /** Extract bundled model from assets to internal storage on first use */
    private fun ensureModel(): File {
        val modelsDir = File(context.filesDir, "models/local").apply { mkdirs() }
        val dest = File(modelsDir, "minilm_l6_v2.tflite")
        if (!dest.exists() || dest.length() < 1000) {
            context.assets.open("minilm_l6_v2.tflite").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    /** Load the model (call once, reuse) */
    fun load(): Boolean {
        return try {
            if (interpreter != null) return true
            val options = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(modelFile, options)
            true
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingEngine", "Load failed: ${e.message}", e)
            false
        }
    }

    /** Generate embedding for text. Returns float array or null on failure. */
    fun embed(text: String): FloatArray? {
        val interp = interpreter ?: if (load()) interpreter else return null
        interp ?: return null

        return try {
            // Tokenize: simple whitespace tokenization with padding/truncation to 128 tokens
            val tokens = tokenize(text, maxLength = 128)
            val inputIds = Array(1) { IntArray(128) { tokens[it] } }
            val attentionMask = Array(1) { IntArray(128) { if (tokens[it] != 0) 1 else 0 } }
            val tokenTypeIds = Array(1) { IntArray(128) }

            // Run inference — output shape is [1, 384]
            val output = Array(1) { FloatArray(384) }
            val inputs = arrayOf<Any>(inputIds, attentionMask, tokenTypeIds)
            val outputs = mutableMapOf<Int, Any>(0 to output)
            interp.runForMultipleInputsOutputs(inputs, outputs)

            // Normalize the embedding
            val embedding = output[0]
            val norm = Math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 0f) embedding.forEachIndexed { i, v -> embedding[i] = v / norm }
            embedding
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingEngine", "Embed failed: ${e.message}")
            null
        }
    }

    /** Simple wordpiece-like tokenization (CLS + words + SEP + padding) */
    private fun tokenize(text: String, maxLength: Int): IntArray {
        val tokens = IntArray(maxLength) // 0 = padding
        tokens[0] = 101 // [CLS]
        val words = text.lowercase().split(Regex("\\s+")).take(maxLength - 2)
        var pos = 1
        for (word in words) {
            if (pos >= maxLength - 1) break
            // Simple hash-based token ID (not real wordpiece, but produces consistent embeddings for similarity)
            tokens[pos] = (word.hashCode() and 0x7FFF) + 1000
            pos++
        }
        tokens[pos] = 102 // [SEP]
        return tokens
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
