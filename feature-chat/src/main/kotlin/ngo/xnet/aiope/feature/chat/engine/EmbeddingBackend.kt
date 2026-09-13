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
            // The introspect manual store shares the same embedding backend, so it must
            // be re-embedded too when the backend changes (its vectors live in a
            // SEPARATE db and would otherwise mismatch dimensions).
            buildManualRagEngine(ctx).reindexAll()
            true
        } catch (_: Throwable) {
            false
        }

    // ── Introspect manual store (separate DB) ────────────────────────────────
    // The AIOPE manual is indexed into its OWN SQLite db (aiope_manual.db), fully
    // isolated from the user's knowledge base (aiope_rag.db). It uses the SAME
    // embedding backend as RAG (local Bekko when ready, else cloud) so a backend
    // toggle re-indexes both stores together.

    const val MANUAL_DB = "aiope_manual.db"

    /** A RagEngine bound to the manual DB, wired to the current embedding backend. */
    fun buildManualRagEngine(ctx: Context): org.xnet.aiope.inference.RagEngine {
        val taskStore = ngo.xnet.aiope.core.network.TaskModelStore(ctx)
        val tc = taskStore.getTaskConfig(ngo.xnet.aiope.core.network.ModelTask.RAG)
        val modelId = tc.modelId ?: "google-ai-studio/models-gemini-embedding-2"
        val cloudEmbed = org.xnet.aiope.inference.CloudEmbeddingEngine(
            baseUrl = "https://inf.xnet.ngo/v1",
            apiKey = ngo.xnet.aiope.feature.chat.BuildConfig.GATEWAY_KEY,
            model = modelId,
        )
        val fn = embedFn(ctx) { text -> cloudEmbed.embed(text) }
        return org.xnet.aiope.inference.RagEngine(ctx, fn, dbName = MANUAL_DB)
    }

    private const val MANUAL_PREFS = "aiope_manual"
    private const val KEY_INDEXED_VERSION = "indexed_version"
    private const val MANUAL_ASSET_DIR = "manual"

    /**
     * Index the bundled manual (assets/manual markdown files) into the manual DB.
     * Runs once per app version: if the recorded indexed_version matches the
     * current versionCode AND documents exist, it's a no-op. Called at startup
     * (off the main thread). Re-indexes automatically when the app version changes.
     */
    fun ensureManualIndexed(ctx: Context) {
        val prefs = ctx.getSharedPreferences(MANUAL_PREFS, Context.MODE_PRIVATE)
        val currentVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            }
        } catch (_: Throwable) {
            -1L
        }
        val indexedVersion = prefs.getLong(KEY_INDEXED_VERSION, -2L)

        val rag = buildManualRagEngine(ctx)
        val alreadyIndexed = runCatching { rag.listDocuments().isNotEmpty() }.getOrDefault(false)
        if (indexedVersion == currentVersion && alreadyIndexed) return // up to date

        // Rebuild from scratch so removed/renamed manual pages don't linger.
        runCatching {
            rag.listDocuments().forEach { rag.deleteDocument(it.id) }
        }
        val am = ctx.assets
        val files = runCatching { am.list(MANUAL_ASSET_DIR)?.filter { it.endsWith(".md") } ?: emptyList() }
            .getOrDefault(emptyList())
        var indexed = 0
        for (name in files) {
            val content = runCatching {
                am.open("$MANUAL_ASSET_DIR/$name").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            if (content.isBlank()) continue
            val title = name.removeSuffix(".md")
            runCatching { rag.indexDocument(title = title, content = content, source = "manual/$name") }
                .onSuccess { indexed++ }
        }
        if (indexed > 0) {
            prefs.edit().putLong(KEY_INDEXED_VERSION, currentVersion).apply()
        }
    }

    /** Search the manual store. Returns markdown snippets from matching manual pages. */
    fun searchManual(ctx: Context, query: String, topK: Int = 5) =
        buildManualRagEngine(ctx).search(query, topK)
}
