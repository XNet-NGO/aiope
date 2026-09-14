package ngo.xnet.aiope.feature.chat.face

import android.content.Context
import android.graphics.Bitmap
import ngo.xnet.aiope.core.terminal.shell.FaceModelBootstrap
import ngo.xnet.aiope.feature.chat.db.ChatDao
import org.xnet.aiope.inference.FaceEngine

/**
 * Coordinates on-device face identity for personalization.
 *
 *  - Enrollment: [enroll] detects+embeds the primary face in a captured bitmap and stores
 *    an encrypted identity (opt-in, user-initiated).
 *  - "Who's here": [identifyAndCache] runs on app foreground / a dedicated moment and caches
 *    the result until [invalidate] is called or a new identification is run. Unknown faces
 *    stay unknown — never guessed.
 *  - [currentUserLabel] is what gets injected into the agent system prompt.
 *
 * Everything is on-device. If models aren't installed, all calls degrade to "no identity".
 */
class FaceIdentityManager(
    private val app: Context,
    private val dao: ChatDao,
) {
    private val store = FaceEnrollmentStore(dao)

    companion object {
        /** settings_kv key holding the current prompt-injection line (empty = unidentified). */
        const val KEY_CURRENT_IDENTITY = "face_current_identity"

        /** In-memory mirror of the current injection line, for synchronous (non-suspend) reads. */
        @Volatile private var currentLine: String? = null
        @Volatile private var currentLineAt: Long = 0L

        /** How long an identity stays valid for injection after its scan; beyond this it is
         *  treated as stale and not injected (the user may have walked away). */
        const val IDENTITY_TTL_MS = 5 * 60_000L

        /** Synchronous read of the current identity injection line (null if none or stale). */
        fun currentInjectionLine(): String? {
            val line = currentLine ?: return null
            if (System.currentTimeMillis() - currentLineAt > IDENTITY_TTL_MS) return null
            return line
        }

        /**
         * Read the current identity prompt line for injection. Returns null when face identity
         * is unavailable, no identification has run, or the last identification is stale
         * (older than [IDENTITY_TTL_MS]). Persisted format is "<epochMs>|<line>".
         */
        /** Optional hook invoked when injection is requested but the identity is missing or
         *  stale, so a fresh scan can be kicked off. Set by PresenceIdentifier. Debounced there. */
        @Volatile var onStaleRefreshRequested: (() -> Unit)? = null

        /**
         * Read the current identity prompt line for injection. Returns null when face identity
         * is unavailable, no identification has run, or the last identification is stale
         * (older than [IDENTITY_TTL_MS]). On a stale/missing read it requests a fresh scan via
         * [onStaleRefreshRequested] so the identity refreshes for subsequent turns.
         * Persisted format is "<epochMs>|<line>".
         */
        suspend fun promptInjectionFor(dao: ChatDao): String? {
            val raw = dao.getSetting(KEY_CURRENT_IDENTITY)?.takeIf { it.isNotBlank() }
            if (raw == null) {
                onStaleRefreshRequested?.invoke()
                return null
            }
            val sep = raw.indexOf('|')
            if (sep <= 0) return null
            val ts = raw.substring(0, sep).toLongOrNull() ?: return null
            if (System.currentTimeMillis() - ts > IDENTITY_TTL_MS) {
                currentLine = null
                onStaleRefreshRequested?.invoke() // expired — proactively re-scan
                return null // stale — user likely no longer present
            }
            val line = raw.substring(sep + 1).takeIf { it.isNotBlank() } ?: return null
            currentLine = line
            currentLineAt = ts
            return line
        }
    }

    @Volatile private var engine: FaceEngine? = null

    // Cached "who's here" result. null label = unidentified/unknown.
    @Volatile private var cachedLabel: String? = null
    @Volatile private var cachedConfidence: Float = 0f
    @Volatile private var hasIdentified: Boolean = false

    fun modelsInstalled(): Boolean = FaceModelBootstrap.isInstalled(app)

    @Synchronized
    private fun engine(): FaceEngine? {
        if (!modelsInstalled()) return null
        engine?.let { return it }
        val e = FaceEngine(
            detectModel = FaceModelBootstrap.detectFile(app),
            embedModel = FaceModelBootstrap.embedFile(app),
        )
        engine = e
        return e
    }

    /** Enroll one angle sample of the primary face in [bitmap] under [label]. Appends to any
     *  existing samples for that label (multi-angle enrollment). Returns true on success. */
    suspend fun enroll(label: String, bitmap: Bitmap): Boolean {
        if (label.isBlank()) return false
        val emb = engine()?.embedPrimaryFace(bitmap) ?: return false
        store.enroll(label, emb)
        // The person who just enrolled is present now — set them as the current user so the
        // agent has context immediately, without waiting for a separate identify pass.
        setCache(label.trim(), 1.0f)
        persist()
        return true
    }

    suspend fun enrolled(): List<FaceEnrollmentStore.Identity> = store.all()
    /**
     * Diagnostic scan for the Security "Test scan" button: runs a detailed identify against the
     * enrolled set and returns the full score breakdown WITHOUT mutating the cached identity.
     */
    suspend fun testScan(bitmap: Bitmap): org.xnet.aiope.inference.FaceEngine.ScanReport? {
        val e = engine() ?: return null
        return e.identifyDetailed(bitmap, store.labeledEmbeddings())
    }

    /** Encrypt/decrypt + dimension integrity of the stored embeddings. */
    suspend fun integrityReport(): FaceEnrollmentStore.Integrity =
        store.integrity(org.xnet.aiope.inference.FaceEngine.EMBED_DIM)

    /**
     * Extended, robust scan used by the tool + auto-scan. Captures several front-camera frames
     * over a longer window, runs a detailed identify on each, and keeps the best (highest top
     * score that is ACCEPTED; else the best face-found result). Stops early on a confident accept.
     * Caches + persists the final decision. Returns the identified label or null.
     */
    suspend fun identifyBestOverFrames(
        capture: SilentFaceCapture,
        maxFrames: Int = 5,
    ): String? {
        val e = engine() ?: run { setCache(null, 0f); persist(); return null }
        val enrolled = store.labeledEmbeddings()
        if (enrolled.isEmpty()) { setCache(null, 0f); persist(); return null }

        var best: org.xnet.aiope.inference.FaceEngine.ScanReport? = null
        capture.captureFrames(maxFrames = maxFrames) { bmp ->
            val r = runCatching { e.identifyDetailed(bmp, enrolled) }.getOrNull()
            if (r != null && r.faceFound) {
                if (best == null || r.topScore > (best?.topScore ?: -1f)) best = r
            }
            // Stop early once we have a clearly accepted, confident match.
            r?.accepted == true && (r.topScore >= org.xnet.aiope.inference.FaceEngine.CONFIDENT_THRESHOLD)
        }

        val b = best
        val label = if (b?.accepted == true) b.topLabel else null
        setCache(label, b?.topScore ?: 0f)
        persist()
        return label
    }
    /** Distinct enrolled people with their angle-sample counts. */
    suspend fun labelCounts(): Map<String, Int> = store.labelCounts()
    suspend fun deleteIdentity(id: String) { store.delete(id); invalidate(); persist() }
    /** Delete all angle samples for a person. */
    suspend fun deleteByLabel(label: String) { store.deleteByLabel(label); invalidate(); persist() }
    suspend fun clearIdentities() { store.clear(); invalidate(); persist() }

    /**
     * Identify the primary face in [bitmap] against enrolled identities and cache the result.
     * Returns the matched label or null (unidentified). Call at the "who's here" moment.
     */
    suspend fun identifyAndCache(bitmap: Bitmap): String? {
        val e = engine() ?: run { setCache(null, 0f); persist(); return null }
        val enrolled = store.labeledEmbeddings()
        val match = e.identify(bitmap, enrolled)
        setCache(match?.first, match?.second ?: 0f)
        persist()
        return match?.first
    }

    /** Persist the current injection line (timestamped) to settings_kv for chat + worker. */
    private suspend fun persist() {
        val line = promptInjection() ?: ""
        val now = System.currentTimeMillis()
        currentLine = line.ifBlank { null }
        currentLineAt = now
        // Format: "<epochMs>|<line>". Empty line still stamps the time so a stale positive is
        // actively cleared when a later scan is unidentified.
        dao.upsertSetting(
            ngo.xnet.aiope.feature.chat.db.SettingsKvEntity(KEY_CURRENT_IDENTITY, "$now|$line"),
        )
    }

    /** Manually set the current user (e.g., no camera available). */
    fun invalidate() {
        cachedLabel = null
        cachedConfidence = 0f
        hasIdentified = false
    }

    /** Clear the current identity (e.g. a scan couldn't get a frame) so nothing stale is
     *  injected. Marks as "identified with no match" and persists the cleared state. */
    suspend fun clearIdentity() {
        setCache(null, 0f)
        persist()
    }

    @Synchronized
    private fun setCache(label: String?, confidence: Float) {
        cachedLabel = label
        cachedConfidence = confidence
        hasIdentified = true
    }

    /** The current cached user label, or null if none/unidentified/not yet run. */
    fun currentUserLabel(): String? = cachedLabel

    /**
     * A system-prompt line describing the current user, or null if identity is unavailable
     * (models not installed, no enrolled users, or no identification has run). Only enrolled
     * labels are ever emitted; unknown faces produce "unidentified".
     */
    fun promptInjection(): String? {
        if (!modelsInstalled()) return null
        if (!hasIdentified) return null
        val label = cachedLabel
        return if (label != null) {
            "Current user: $label (identified on-device, confidence ${"%.2f".format(cachedConfidence)})."
        } else {
            "Current user: unidentified."
        }
    }
}
