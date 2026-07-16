package org.xnet.aiope.inference

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device RAG engine using llama.cpp for embeddings and SQLite for vector storage.
 */
class RagEngine(context: Context, private val engine: LlamaEngine) {

    private val db = RagDatabase(context).writableDatabase

    // --- Document Indexing ---

    fun indexDocument(title: String, content: String, source: String = ""): String {
        val docId = java.util.UUID.randomUUID().toString()
        val chunks = chunk(content, chunkSize = 1024, overlap = 128)

        db.insert("documents", null, ContentValues().apply {
            put("id", docId)
            put("title", title)
            put("source", source)
            put("chunk_count", chunks.size)
        })

        chunks.forEachIndexed { index, text ->
            val chunkId = "$docId-$index"
            db.insert("chunks", null, ContentValues().apply {
                put("id", chunkId)
                put("doc_id", docId)
                put("chunk_index", index)
                put("text", text)
            })

            val embedding = engine.embed(text)
            if (embedding != null) {
                db.insert("embeddings", null, ContentValues().apply {
                    put("chunk_id", chunkId)
                    put("embedding", encodeFloats(embedding))
                    put("dims", embedding.size)
                })
            }
        }

        return docId
    }

    fun deleteDocument(docId: String) {
        db.delete("embeddings", "chunk_id IN (SELECT id FROM chunks WHERE doc_id = ?)", arrayOf(docId))
        db.delete("chunks", "doc_id = ?", arrayOf(docId))
        db.delete("documents", "id = ?", arrayOf(docId))
    }

    // --- Search ---

    data class SearchResult(
        val chunkId: String,
        val docId: String,
        val text: String,
        val score: Float,
        val title: String
    )

    fun search(query: String, topK: Int = 5): List<SearchResult> {
        val queryEmbedding = engine.embed(query) ?: return emptyList()

        val results = mutableListOf<SearchResult>()

        val cursor = db.rawQuery("""
            SELECT e.chunk_id, e.embedding, c.text, c.doc_id, d.title
            FROM embeddings e
            JOIN chunks c ON e.chunk_id = c.id
            JOIN documents d ON c.doc_id = d.id
        """, null)

        cursor.use {
            while (it.moveToNext()) {
                val chunkId = it.getString(0)
                val embBlob = it.getBlob(1)
                val text = it.getString(2)
                val docId = it.getString(3)
                val title = it.getString(4)

                val embedding = decodeFloats(embBlob)
                val score = cosineSimilarity(queryEmbedding, embedding)

                results.add(SearchResult(chunkId, docId, text, score, title))
            }
        }

        return results.sortedByDescending { it.score }.take(topK)
    }

    // --- Chunking ---

    private fun chunk(text: String, chunkSize: Int, overlap: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentLen = 0

        for (sentence in sentences) {
            val sentLen = sentence.length / 4  // rough token estimate
            if (currentLen + sentLen > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                // Keep overlap
                val overlapText = current.toString().takeLast(overlap * 4)
                current.clear()
                current.append(overlapText).append(" ")
                currentLen = overlap
            }
            current.append(sentence).append(" ")
            currentLen += sentLen
        }
        if (current.isNotBlank()) {
            chunks.add(current.toString().trim())
        }

        return chunks
    }

    // --- Math ---

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    // --- Encoding ---

    private fun encodeFloats(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun decodeFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    // --- Database ---

    private class RagDatabase(context: Context) :
        SQLiteOpenHelper(context, "aiope_rag.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE documents (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    source TEXT,
                    chunk_count INTEGER,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """)
            db.execSQL("""
                CREATE TABLE chunks (
                    id TEXT PRIMARY KEY,
                    doc_id TEXT NOT NULL REFERENCES documents(id),
                    chunk_index INTEGER NOT NULL,
                    text TEXT NOT NULL
                )
            """)
            db.execSQL("""
                CREATE TABLE embeddings (
                    chunk_id TEXT PRIMARY KEY REFERENCES chunks(id),
                    embedding BLOB NOT NULL,
                    dims INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX idx_chunks_doc ON chunks(doc_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS embeddings")
            db.execSQL("DROP TABLE IF EXISTS chunks")
            db.execSQL("DROP TABLE IF EXISTS documents")
            onCreate(db)
        }
    }
}
