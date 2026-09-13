package ngo.xnet.aiope.feature.chat.engine

import android.content.Context
import ngo.xnet.aiope.core.terminal.shell.BekkoModelBootstrap
import org.xnet.aiope.inference.LocalEmbeddingEngine

/**
 * Chooses the embedding backend for RAG: on-device (Bekko ONNX) or cloud.
 *
 * The Local/Cloud preference is stored in SharedPreferences ("aiope_rag" / "local_embeddings")
 * so it can be read synchronously from RagEngine construction sites. Local is used only when
 * the toggle is on AND the model + tokenizer have been downloaded via BekkoModelBootstrap;
 * otherwise we transparently fall back to the provided cloud embedder.
 */
object EmbeddingBackend {

    private const val PREFS = "aiope_rag"
    private const val KEY_LOCAL = "local_embeddings"

    fun isLocalEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCAL, false)

    fun setLocalEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCAL, enabled).apply()
    }

    /** True when the local model is both selected and actually installed. */
    fun localReady(ctx: Context): Boolean =
        isLocalEnabled(ctx) && BekkoModelBootstrap.isInstalled(ctx)

    // Single shared engine instance; ONNX session load is expensive, reuse it.
    @Volatile private var localEngine: LocalEmbeddingEngine? = null

    @Synchronized
    private fun localEngine(ctx: Context): LocalEmbeddingEngine {
        val existing = localEngine
        if (existing != null) return existing
        val created = LocalEmbeddingEngine(
            modelFile = BekkoModelBootstrap.modelFile(ctx),
            tokenizerFile = BekkoModelBootstrap.tokenizerFile(ctx),
        )
        localEngine = created
        return created
    }

    /** Drop the cached engine (e.g. after re-downloading the model). */
    @Synchronized
    fun reset() {
        try {
            localEngine?.close()
        } catch (_: Throwable) {}
        localEngine = null
    }

    /**
     * Build an embedFn for RagEngine. Prefers the local model when ready; on any local
     * failure (or when local is off/not installed) delegates to [cloudFallback].
     */
    fun embedFn(ctx: Context, cloudFallback: (String) -> FloatArray?): (String) -> FloatArray? {
        return { text ->
            if (localReady(ctx)) {
                localEngine(ctx).embed(text) ?: cloudFallback(text)
            } else {
                cloudFallback(text)
            }
        }
    }

    /**
     * Build a RagEngine wired to the currently-selected backend (local when ready,
     * otherwise the shared cloud embedder). Centralizes the cloud wiring so callers
     * (e.g. the settings toggle) don't have to duplicate it.
     */
    fun buildRagEngine(ctx: Context): org.xnet.aiope.inference.RagEngine {
        val taskStore = ngo.xnet.aiope.core.network.TaskModelStore(ctx)
        val tc = taskStore.getTaskConfig(ngo.xnet.aiope.core.network.ModelTask.RAG)
        val modelId = tc.modelId ?: "google-ai-studio/models-gemini-embedding-2"
        val cloudEmbed = org.xnet.aiope.inference.CloudEmbeddingEngine(
            baseUrl = "https://inf.xnet.ngo/v1",
            apiKey = ngo.xnet.aiope.feature.chat.BuildConfig.GATEWAY_KEY,
            model = modelId,
        )
        val fn = embedFn(ctx) { text -> cloudEmbed.embed(text) }
        return org.xnet.aiope.inference.RagEngine(ctx, fn)
    }

    /** Number of indexed documents currently in the knowledge base. */
    fun indexedDocumentCount(ctx: Context): Int =
        try {
            buildRagEngine(ctx).listDocuments().size
        } catch (_: Throwable) {
            0
        }

    /**
     * Re-embed all indexed chunks with the currently-selected backend. Blocking — call
     * off the main thread. Returns true on success.
     */
    fun reindexWithCurrentBackend(ctx: Context): Boolean =
        try {
            buildRagEngine(ctx).reindexAll()
            true
        } catch (_: Throwable) {
            false
        }
}
