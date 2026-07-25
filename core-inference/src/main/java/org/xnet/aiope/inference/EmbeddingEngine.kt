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
            val interp = interpreter!!
            val inputCount = interp.inputTensorCount
            val outputCount = interp.outputTensorCount
            android.util.Log.i("EmbeddingEngine", "Model loaded: inputs=$inputCount outputs=$outputCount")
            for (i in 0 until inputCount) {
                val t = interp.getInputTensor(i)
                android.util.Log.i("EmbeddingEngine", "  input[$i]: ${t.name()} shape=${t.shape().contentToString()} type=${t.dataType()}")
            }
            for (i in 0 until outputCount) {
                val t = interp.getOutputTensor(i)
                android.util.Log.i("EmbeddingEngine", "  output[$i]: ${t.name()} shape=${t.shape().contentToString()} type=${t.dataType()}")
            }
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
            val maxLength = 128
            val tokens = tokenize(text, maxLength)
            val seqLen = tokens.indexOfFirst { it == 0 }.let { if (it == -1) maxLength else it }

            // Resize inputs to actual sequence length
            interp.resizeInput(0, intArrayOf(1, seqLen))
            interp.resizeInput(1, intArrayOf(1, seqLen))
            interp.allocateTensors()

            // input[0] = input_ids, input[1] = attention_mask
            val inputIds = Array(1) { IntArray(seqLen) { tokens[it] } }
            val attentionMask = Array(1) { IntArray(seqLen) { 1 } }

            // Run inference — output shape is [1, 384]
            val output = Array(1) { FloatArray(384) }
            val inputs = arrayOf<Any>(inputIds, attentionMask)
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

    /** Simple hash-based tokenization (CLS + words + SEP + padding) */
    private fun tokenize(text: String, maxLength: Int): IntArray {
        val vocabSize = 30522 // BERT/MiniLM vocab size
        val tokens = IntArray(maxLength) // 0 = padding
        tokens[0] = 101 // [CLS]
        val words = text.lowercase().split(Regex("\\s+")).take(maxLength - 2)
        var pos = 1
        for (word in words) {
            if (pos >= maxLength - 1) break
            // Hash to valid vocab range (avoid special tokens 0-999)
            tokens[pos] = (word.hashCode().and(0x7FFFFFFF) % (vocabSize - 1000)) + 1000
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
