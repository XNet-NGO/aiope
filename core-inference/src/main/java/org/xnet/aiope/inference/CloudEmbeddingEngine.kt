package org.xnet.aiope.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud-based embedding engine using OpenAI-compatible /embeddings endpoint.
 * Works with Gemini Embedding 2 via AIOPE Gateway or any OpenAI-compatible API.
 */
class CloudEmbeddingEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "models/gemini-embedding-2"
) {

    /** Generate embedding for text. Returns float array or null on failure. */
    fun embed(text: String): FloatArray? {
        return try {
            val url = "${baseUrl.trimEnd('/')}/embeddings"
            val body = JSONObject().apply {
                put("model", model)
                put("input", text)
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.outputStream.write(body.toString().toByteArray())

            if (conn.responseCode !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                android.util.Log.e("CloudEmbedding", "HTTP ${conn.responseCode}: $err")
                return null
            }

            val response = JSONObject(conn.inputStream.bufferedReader().readText())
            val data = response.optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            val embeddingArray = data.getJSONObject(0).optJSONArray("embedding") ?: return null
            FloatArray(embeddingArray.length()) { embeddingArray.getDouble(it).toFloat() }
        } catch (e: Exception) {
            android.util.Log.e("CloudEmbedding", "Embed failed: ${e.message}")
            null
        }
    }

    /** Batch embed multiple texts in one API call */
    fun embedBatch(texts: List<String>): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        return try {
            val url = "${baseUrl.trimEnd('/')}/embeddings"
            val body = JSONObject().apply {
                put("model", model)
                put("input", JSONArray(texts))
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.outputStream.write(body.toString().toByteArray())

            if (conn.responseCode !in 200..299) {
                android.util.Log.e("CloudEmbedding", "Batch HTTP ${conn.responseCode}")
                return texts.map { null }
            }

            val response = JSONObject(conn.inputStream.bufferedReader().readText())
            val data = response.optJSONArray("data") ?: return texts.map { null }

            (0 until data.length()).map { i ->
                val embeddingArray = data.getJSONObject(i).optJSONArray("embedding")
                if (embeddingArray != null) {
                    FloatArray(embeddingArray.length()) { embeddingArray.getDouble(it).toFloat() }
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.e("CloudEmbedding", "Batch embed failed: ${e.message}")
            texts.map { null }
        }
    }
}
