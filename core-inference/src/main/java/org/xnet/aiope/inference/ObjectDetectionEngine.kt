package org.xnet.aiope.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** A single detected object, in original-image pixel coordinates. */
data class Detection(
    val label: String,
    val score: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
)

/**
 * On-device object detector backed by RT-DETR (Apache-2.0) ONNX.
 *
 * Contract (official RT-DETR export):
 *   Inputs:
 *     images            : [1, 3, 640, 640] float, RGB NCHW, 0..1
 *     orig_target_sizes : [1, 2] int64, [[origWidth, origHeight]]
 *   Outputs (already post-processed by the model; boxes in ORIGINAL-image pixels, no NMS):
 *     labels : [1, 300] int64  (COCO class index per query)
 *     boxes  : [1, 300, 4] float ([x1,y1,x2,y2])
 *     scores : [1, 300] float
 *
 * Construct with the file downloaded by ObjectDetectionBootstrap.
 */
class ObjectDetectionEngine(private val modelFile: File) : AutoCloseable {

    companion object {
        private const val TAG = "ObjectDetectionEngine"
        private const val IN = 640
        private const val SCORE_THRESHOLD = 0.5f
        private const val IN_IMAGES = "images"
        private const val IN_SIZES = "orig_target_sizes"
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var initFailed = false

    fun isAvailable(): Boolean = modelFile.isFile && modelFile.length() > 0

    @Synchronized
    private fun ensureInit(): Boolean {
        if (session != null) return true
        if (initFailed || !isAvailable()) { initFailed = true; return false }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = e.createSession(modelFile.absolutePath, opts)
            env = e
            Log.d(TAG, "Initialized RT-DETR session")
            true
        } catch (ex: Throwable) {
            Log.e(TAG, "init failed", ex)
            initFailed = true
            false
        }
    }

    /** Detect objects in [bitmap]. Returns [] on failure or if nothing is above threshold. */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!ensureInit()) return emptyList()
        val e = env ?: return emptyList()
        val s = session ?: return emptyList()
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, IN, IN, true)
            val pixels = toRgbNchw01(scaled)
            val imgTensor = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(pixels), longArrayOf(1, 3, IN.toLong(), IN.toLong()),
            )
            // RT-DETR scales boxes back to these dims internally.
            val sizes = longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())
            val sizeTensor = OnnxTensor.createTensor(e, LongBuffer.wrap(sizes), longArrayOf(1, 2))

            imgTensor.use { img ->
                sizeTensor.use { sz ->
                    s.run(mapOf(IN_IMAGES to img, IN_SIZES to sz)).use { result ->
                        decode(result)
                    }
                }
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "detect failed", ex)
            emptyList()
        }
    }

    private fun decode(result: OrtSession.Result): List<Detection> {
        val labels = (result.get("labels").orElseGet { result.get(0) } as OnnxTensor).value
        val boxes = (result.get("boxes").orElseGet { result.get(1) } as OnnxTensor).value
        val scores = (result.get("scores").orElseGet { result.get(2) } as OnnxTensor).value

        // labels: [1, 300] long ; boxes: [1, 300, 4] float ; scores: [1, 300] float
        val labRow = (labels as Array<*>)[0]
        val scrRow = (scores as Array<*>)[0] as FloatArray
        val boxRow = (boxes as Array<*>)[0] as Array<*>

        val out = ArrayList<Detection>()
        for (i in scrRow.indices) {
            val score = scrRow[i]
            if (score < SCORE_THRESHOLD) continue
            val cls = when (labRow) {
                is LongArray -> labRow[i].toInt()
                is IntArray -> labRow[i]
                is Array<*> -> (labRow[i] as Number).toInt()
                else -> continue
            }
            val b = boxRow[i] as FloatArray
            out.add(
                Detection(
                    label = CocoLabels.NAMES.getOrElse(cls) { "obj$cls" },
                    score = score,
                    x1 = b[0], y1 = b[1], x2 = b[2], y2 = b[3],
                ),
            )
        }
        return out.sortedByDescending { it.score }
    }

    private fun toRgbNchw01(bmp: Bitmap): FloatArray {
        val px = IntArray(IN * IN)
        bmp.getPixels(px, 0, IN, 0, 0, IN, IN)
        val out = FloatArray(3 * IN * IN)
        val plane = IN * IN
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = (p shr 16 and 0xFF) / 255f
            out[plane + i] = (p shr 8 and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }

    @Synchronized
    override fun close() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }
}
