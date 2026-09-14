package org.xnet.aiope.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * On-device face pipeline for consent-based personalization.
 *
 *  1. DETECT  — YuNet ONNX finds faces (box + 5 landmarks + score).
 *  2. ALIGN   — similarity-transform the 5 landmarks to ArcFace's canonical template,
 *               producing a 112x112 aligned RGB crop.
 *  3. EMBED   — ArcFace ONNX maps the crop to a 512-d, L2-normalized embedding.
 *  4. MATCH   — cosine similarity against enrolled embeddings; best match above a
 *               threshold identifies the user, otherwise "unidentified".
 *
 * Identity is only ever produced for faces the user explicitly enrolled. Everything
 * runs on-device; nothing is transmitted.
 *
 * Construct with the files produced by FaceModelBootstrap (yunet.onnx, arcface.onnx).
 */
class FaceEngine(
    private val detectModel: File,
    private val embedModel: File,
) : AutoCloseable {

    companion object {
        private const val TAG = "FaceEngine"
        const val EMBED_DIM = 512
        private const val DETECT_SIZE = 640       // YuNet 2023mar fixed input is 640x640
        private const val ALIGN_SIZE = 112        // ArcFace input 112x112
        /** Cosine-similarity threshold to even consider an identity match. */
        const val MATCH_THRESHOLD = 0.50f
        /** Above this, accept the top match regardless of runner-up margin. */
        const val CONFIDENT_THRESHOLD = 0.62f
        /** In the marginal band, top must beat the runner-up (different person) by this. */
        const val MATCH_MARGIN = 0.10f

        // ArcFace canonical 5-point landmark template for a 112x112 crop
        // (left eye, right eye, nose, left mouth, right mouth).
        private val REF_LANDMARKS = arrayOf(
            floatArrayOf(38.2946f, 51.6963f),
            floatArrayOf(73.5318f, 51.5014f),
            floatArrayOf(56.0252f, 71.7366f),
            floatArrayOf(41.5493f, 92.3655f),
            floatArrayOf(70.7299f, 92.2041f),
        )
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var detectSession: OrtSession? = null
    @Volatile private var embedSession: OrtSession? = null
    @Volatile private var initFailed = false

    fun isAvailable(): Boolean =
        detectModel.isFile && detectModel.length() > 0 && embedModel.isFile && embedModel.length() > 0

    @Synchronized
    private fun ensureInit(): Boolean {
        if (detectSession != null && embedSession != null) return true
        if (initFailed || !isAvailable()) {
            initFailed = true
            return false
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            detectSession = e.createSession(detectModel.absolutePath, opts)
            embedSession = e.createSession(embedModel.absolutePath, opts)
            env = e
            Log.d(TAG, "Initialized YuNet + ArcFace sessions")
            true
        } catch (ex: Throwable) {
            Log.e(TAG, "init failed", ex)
            initFailed = true
            false
        }
    }

    data class Face(val box: FloatArray, val landmarks: Array<FloatArray>, val score: Float)

    /** Public face detection for enrollment guidance (framing). Returns [] if unavailable. */
    fun detectForGuidance(bitmap: Bitmap): List<Face> {
        if (!ensureInit()) return emptyList()
        return try { detect(bitmap) ?: emptyList() } catch (_: Throwable) { emptyList() }
    }

    /**
     * Detect faces and embed the single most prominent one. Returns its 512-d embedding,
     * or null if no face is found or the pipeline fails. Designed for the enrollment /
     * "who's here" flow where one primary subject is expected.
     */
    fun embedPrimaryFace(bitmap: Bitmap): FloatArray? {
        if (!ensureInit()) return null
        return try {
            val faces = detect(bitmap) ?: return null
            if (faces.isEmpty()) return null
            // Pick the highest-scoring / largest face.
            val face = faces.maxByOrNull { it.score * boxArea(it.box) } ?: return null
            val aligned = alignFace(bitmap, face.landmarks) ?: return null
            embed(aligned)
        } catch (ex: Throwable) {
            Log.e(TAG, "embedPrimaryFace failed", ex)
            null
        }
    }

    /**
     * Match a bitmap against enrolled (label -> embedding) identities. Supports multiple
     * angle samples per label: each label's score is the MAX cosine over its samples.
     *
     * Accept the top label when it clearly meets the threshold. The margin-over-runner-up check
     * is only applied in the AMBIGUOUS zone (top score close to the threshold), so a confident
     * match is never voided just because another enrolled person is somewhat similar. This keeps
     * anti-confusion for weak matches without blanking everyone when 2+ people are enrolled.
     */
    fun identify(bitmap: Bitmap, enrolled: List<Pair<String, FloatArray>>): Pair<String, Float>? {
        if (enrolled.isEmpty()) return null
        val probe = embedPrimaryFace(bitmap) ?: return null

        val perLabelBest = HashMap<String, Float>()
        for ((label, emb) in enrolled) {
            if (emb.size != probe.size) continue
            val s = cosine(probe, emb)
            val cur = perLabelBest[label]
            if (cur == null || s > cur) perLabelBest[label] = s
        }
        if (perLabelBest.isEmpty()) return null

        val ranked = perLabelBest.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val runnerUp = if (ranked.size > 1) ranked[1].value else -1f
        val margin = top.value - runnerUp

        // A "confident" score is comfortably above threshold; accept it regardless of margin.
        val confidentScore = top.value >= CONFIDENT_THRESHOLD
        // In the marginal band [MATCH_THRESHOLD, CONFIDENT_THRESHOLD) require separation.
        val meetsThreshold = top.value >= MATCH_THRESHOLD
        val separated = ranked.size == 1 || margin >= MATCH_MARGIN
        val accept = confidentScore || (meetsThreshold && separated)

        Log.d(
            TAG,
            "identify: top=${top.key} score=${"%.3f".format(top.value)} " +
                "runnerUp=${if (runnerUp < 0) "-" else "%.3f".format(runnerUp)} " +
                "margin=${"%.3f".format(margin)} accept=$accept " +
                "(thr=$MATCH_THRESHOLD confThr=$CONFIDENT_THRESHOLD marginReq=$MATCH_MARGIN)",
        )

        return if (accept) top.key to top.value else null
    }

    /** Diagnostic result for the "Test scan" flow. */
    data class ScanReport(
        val faceFound: Boolean,
        val perLabelScores: List<Pair<String, Float>>, // sorted desc
        val topLabel: String?,
        val topScore: Float,
        val runnerUp: Float,
        val margin: Float,
        val accepted: Boolean,
    )

    /**
     * Like [identify] but returns the full score breakdown so the UI can show WHY a match was
     * (or wasn't) accepted — used by the Security "Test scan" button to validate enrollments.
     */
    fun identifyDetailed(bitmap: Bitmap, enrolled: List<Pair<String, FloatArray>>): ScanReport {
        val probe = embedPrimaryFace(bitmap)
            ?: return ScanReport(false, emptyList(), null, 0f, -1f, 0f, false)
        val perLabelBest = HashMap<String, Float>()
        for ((label, emb) in enrolled) {
            if (emb.size != probe.size) continue
            val s = cosine(probe, emb)
            val cur = perLabelBest[label]
            if (cur == null || s > cur) perLabelBest[label] = s
        }
        val ranked = perLabelBest.entries.sortedByDescending { it.value }.map { it.key to it.value }
        if (ranked.isEmpty()) return ScanReport(true, emptyList(), null, 0f, -1f, 0f, false)
        val top = ranked[0]
        val runnerUp = if (ranked.size > 1) ranked[1].second else -1f
        val margin = top.second - runnerUp
        val confidentScore = top.second >= CONFIDENT_THRESHOLD
        val meetsThreshold = top.second >= MATCH_THRESHOLD
        val separated = ranked.size == 1 || margin >= MATCH_MARGIN
        val accept = confidentScore || (meetsThreshold && separated)
        return ScanReport(true, ranked, top.first, top.second, runnerUp, margin, accept)
    }

    // --- Detection (YuNet) ---

    private fun detect(bitmap: Bitmap): List<Face>? {
        val e = env ?: return null
        val s = detectSession ?: return null
        // Resize to the fixed detector input, tracking scale to map boxes/landmarks back.
        val scaled = Bitmap.createScaledBitmap(bitmap, DETECT_SIZE, DETECT_SIZE, true)
        val sx = bitmap.width.toFloat() / DETECT_SIZE
        val sy = bitmap.height.toFloat() / DETECT_SIZE
        val input = bitmapToBgrNchw(scaled, DETECT_SIZE, DETECT_SIZE)
        val tensor = OnnxTensor.createTensor(
            e, FloatBuffer.wrap(input), longArrayOf(1, 3, DETECT_SIZE.toLong(), DETECT_SIZE.toLong()),
        )
        tensor.use { t ->
            s.run(mapOf(inputName(s) to t)).use { result ->
                return YuNetDecoder.decode(result, DETECT_SIZE, DETECT_SIZE, sx, sy)
            }
        }
    }

    // --- Alignment (5-landmark similarity transform) ---

    private fun alignFace(src: Bitmap, landmarks: Array<FloatArray>): Bitmap? {
        if (landmarks.size < 5) return null
        val srcPts = FloatArray(10)
        val dstPts = FloatArray(10)
        for (i in 0 until 5) {
            srcPts[i * 2] = landmarks[i][0]
            srcPts[i * 2 + 1] = landmarks[i][1]
            dstPts[i * 2] = REF_LANDMARKS[i][0]
            dstPts[i * 2 + 1] = REF_LANDMARKS[i][1]
        }
        val matrix = Matrix()
        // setPolyToPoly with 4 points gives a good similarity/affine fit for alignment.
        val ok = matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        if (!ok) return null
        val out = Bitmap.createBitmap(ALIGN_SIZE, ALIGN_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, null)
        return out
    }

    // --- Embedding (ArcFace) ---

    private fun embed(aligned: Bitmap): FloatArray? {
        val e = env ?: return null
        val s = embedSession ?: return null
        // librefacerec-l (iResNet100 ArcFace) expects NCHW: data[1, 3, 112, 112], RGB, (px-127.5)/128.
        val input = bitmapToRgbNchw(aligned, ALIGN_SIZE, ALIGN_SIZE)
        val tensor = OnnxTensor.createTensor(
            e, FloatBuffer.wrap(input), longArrayOf(1, 3, ALIGN_SIZE.toLong(), ALIGN_SIZE.toLong()),
        )
        tensor.use { t ->
            s.run(mapOf(inputName(s) to t)).use { result ->
                val out = result.get(0) as OnnxTensor
                val arr = when (val v = out.value) {
                    is Array<*> -> (v[0] as FloatArray)
                    else -> return null
                }
                return l2normalize(arr)
            }
        }
    }

    /** ArcFace input: NCHW [3,H,W] RGB, normalized (px - 127.5) / 128. */
    private fun bitmapToRgbNchw(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = ((p shr 16 and 0xFF) - 127.5f) / 128f          // R plane
            out[plane + i] = ((p shr 8 and 0xFF) - 127.5f) / 128f   // G plane
            out[2 * plane + i] = ((p and 0xFF) - 127.5f) / 128f     // B plane
        }
        return out
    }

    // --- Helpers ---

    private fun inputName(s: OrtSession): String = s.inputNames.iterator().next()

    private fun boxArea(box: FloatArray): Float =
        if (box.size >= 4) (box[2] - box[0]).coerceAtLeast(0f) * (box[3] - box[1]).coerceAtLeast(0f) else 0f

    /** RGB NCHW, ArcFace normalization: (px - 127.5) / 128. */
    /** ArcFace input: NHWC [H,W,3] RGB, normalized (px - 127.5) / 128. */
    private fun bitmapToRgbNhwc(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val p = px[i]
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)
            val o = i * 3
            out[o] = (r - 127.5f) / 128f
            out[o + 1] = (g - 127.5f) / 128f
            out[o + 2] = (b - 127.5f) / 128f
        }
        return out
    }

    /** BGR NCHW for YuNet (raw 0..255). */
    private fun bitmapToBgrNchw(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = px[i]
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            out[i] = b
            out[plane + i] = g
            out[2 * plane + i] = r
        }
        return out
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // inputs are L2-normalized, so dot == cosine
    }

    @Synchronized
    override fun close() {
        try { detectSession?.close() } catch (_: Throwable) {}
        try { embedSession?.close() } catch (_: Throwable) {}
        detectSession = null
        embedSession = null
    }
}
