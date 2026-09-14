package org.xnet.aiope.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Fast on-device object detector backed by YOLOv9-s (LibreYOLO, MIT) ONNX.
 *
 * Contract:
 *   Input:  images [1, 3, 640, 640] float, RGB NCHW, 0..1
 *   Output: [1, 84, 8400] — 4 box rows (cx,cy,w,h in input pixels) + 80 COCO class scores,
 *           channels-first; requires argmax over classes, xywh->xyxy, and NMS.
 *
 * Boxes are returned in ORIGINAL-image pixel coordinates (scaled back from 640).
 * Intended for the real-time / live path (much lighter than the RT-DETR transformer).
 */
class YoloDetectionEngine(private val modelFile: File) : AutoCloseable {

    companion object {
        private const val TAG = "YoloDetectionEngine"
        private const val IN = 640
        private const val NUM_CLASSES = 80
        private const val SCORE_THRESHOLD = 0.35f
        private const val NMS_IOU = 0.45f
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
            Log.d(TAG, "Initialized YOLOv9-s session")
            true
        } catch (ex: Throwable) {
            Log.e(TAG, "init failed", ex)
            initFailed = true
            false
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (!ensureInit()) return emptyList()
        val e = env ?: return emptyList()
        val s = session ?: return emptyList()
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, IN, IN, true)
            val sx = bitmap.width.toFloat() / IN
            val sy = bitmap.height.toFloat() / IN
            val input = toRgbNchw01(scaled)
            val t = OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, 3, IN.toLong(), IN.toLong()))
            t.use { tensor ->
                s.run(mapOf(s.inputNames.iterator().next() to tensor)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val arr = out.value as Array<Array<FloatArray>> // [1, 84, 8400]
                    decode(arr[0], sx, sy)
                }
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "detect failed", ex)
            emptyList()
        }
    }

    /** data: [84][8400] — rows 0..3 box (cx,cy,w,h), 4..83 class scores. */
    private fun decode(data: Array<FloatArray>, sx: Float, sy: Float): List<Detection> {
        val n = data[0].size
        val dets = ArrayList<Detection>()
        for (i in 0 until n) {
            var bestCls = -1
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val sc = data[4 + c][i]
                if (sc > bestScore) { bestScore = sc; bestCls = c }
            }
            if (bestScore < SCORE_THRESHOLD || bestCls < 0) continue
            // VERIFIED on desktop against yolov9s.onnx with a 640x640 square input: this export
            // emits boxes as [x1, y1, x2, y2] CORNER coordinates directly (NOT xywh). Sample raw
            // outputs like [19,20,639,638] are valid corners; treating rows 2,3 as w,h produced
            // oversized/too-tall boxes that spilled outside the frame.
            val rx1 = data[0][i]; val ry1 = data[1][i]
            val rx2 = data[2][i]; val ry2 = data[3][i]
            // Clamp to the model input frame before scaling back.
            val left = minOf(rx1, rx2).coerceIn(0f, IN.toFloat())
            val top = minOf(ry1, ry2).coerceIn(0f, IN.toFloat())
            val right = maxOf(rx1, rx2).coerceIn(0f, IN.toFloat())
            val bottom = maxOf(ry1, ry2).coerceIn(0f, IN.toFloat())
            if (right - left < 1f || bottom - top < 1f) continue
            dets.add(
                Detection(
                    label = CocoLabels.NAMES.getOrElse(bestCls) { "obj$bestCls" },
                    score = bestScore,
                    x1 = left * sx,
                    y1 = top * sy,
                    x2 = right * sx,
                    y2 = bottom * sy,
                ),
            )
        }
        return nms(dets, NMS_IOU)
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

    private fun nms(dets: List<Detection>, iouT: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { it.label == best.label && iou(best, it) > iouT }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x1, b.x1); val y1 = maxOf(a.y1, b.y1)
        val x2 = min(a.x2, b.x2); val y2 = min(a.y2, b.y2)
        val inter = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
        val areaA = (a.x2 - a.x1).coerceAtLeast(0f) * (a.y2 - a.y1).coerceAtLeast(0f)
        val areaB = (b.x2 - b.x1).coerceAtLeast(0f) * (b.y2 - b.y1).coerceAtLeast(0f)
        val u = areaA + areaB - inter
        return if (u <= 0f) 0f else inter / u
    }

    @Synchronized
    override fun close() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }
}
