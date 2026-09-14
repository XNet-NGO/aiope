package org.xnet.aiope.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decodes YuNet (face_detection_yunet_2023mar) ONNX outputs into faces.
 *
 * This export produces 9 outputs across strides 8/16/32:
 *   cls_{s}  : [1, N_s, 1]   classification score
 *   obj_{s}  : [1, N_s, 1]   objectness score
 *   bbox_{s} : [1, N_s, 4]   box regression (cx,cy,w,h offsets in stride units)
 *   kps_{s}  : [1, N_s, 10]  5 landmarks (x,y) in stride units
 * where N_s = (inputH/s) * (inputW/s), anchors are single-cell (priors of size = stride).
 *
 * Final score = sqrt(cls * obj). Coordinates are mapped back to the ORIGINAL image via
 * (sx, sy) = originalDim / detectorInputDim.
 */
object YuNetDecoder {

    private const val SCORE_THRESHOLD = 0.6f
    private const val NMS_IOU = 0.3f
    private val STRIDES = intArrayOf(8, 16, 32)

    fun decode(
        result: OrtSession.Result,
        inW: Int,
        inH: Int,
        sx: Float,
        sy: Float,
    ): List<FaceEngine.Face> {
        val faces = mutableListOf<FaceEngine.Face>()

        for (stride in STRIDES) {
            val cls = tensor(result, "cls_$stride") ?: continue
            val obj = tensor(result, "obj_$stride") ?: continue
            val box = tensor(result, "bbox_$stride") ?: continue
            val kps = tensor(result, "kps_$stride") ?: continue

            val cols = inW / stride
            val rows = inH / stride
            val n = rows * cols

            var idx = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (idx >= n) break
                    val clsScore = cls[idx]
                    val objScore = obj[idx]
                    val score = sqrt(clsScore.coerceIn(0f, 1f) * objScore.coerceIn(0f, 1f))
                    if (score >= SCORE_THRESHOLD) {
                        val bo = idx * 4
                        // YuNet box decode: center offset + size, in stride units, cell-anchored.
                        val cx = (c + box[bo]) * stride
                        val cy = (r + box[bo + 1]) * stride
                        val w = kotlin.math.exp(box[bo + 2]) * stride
                        val h = kotlin.math.exp(box[bo + 3]) * stride
                        val x1 = (cx - w / 2f) * sx
                        val y1 = (cy - h / 2f) * sy
                        val x2 = (cx + w / 2f) * sx
                        val y2 = (cy + h / 2f) * sy

                        val ko = idx * 10
                        val lm = Array(5) { k ->
                            floatArrayOf(
                                (c + kps[ko + k * 2]) * stride * sx,
                                (r + kps[ko + k * 2 + 1]) * stride * sy,
                            )
                        }
                        faces.add(FaceEngine.Face(floatArrayOf(x1, y1, x2, y2), lm, score))
                    }
                    idx++
                }
            }
        }
        return nms(faces, NMS_IOU)
    }

    private fun tensor(result: OrtSession.Result, name: String): FloatArray? {
        val opt = result.get(name)
        val t = if (opt.isPresent) opt.get() as? OnnxTensor else return null
        // Flatten [1, N, C] to a contiguous FloatArray.
        return when (val v = t?.value) {
            is Array<*> -> {
                val batch = v[0] as? Array<*> ?: return null
                val out = ArrayList<Float>(batch.size * 4)
                for (row in batch) {
                    when (row) {
                        is FloatArray -> row.forEach { out.add(it) }
                        else -> return null
                    }
                }
                out.toFloatArray()
            }
            else -> null
        }
    }

    private fun nms(faces: List<FaceEngine.Face>, iouThresh: Float): List<FaceEngine.Face> {
        val sorted = faces.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<FaceEngine.Face>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { iou(best.box, it.box) > iouThresh }
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = maxOf(a[0], b[0]); val y1 = maxOf(a[1], b[1])
        val x2 = min(a[2], b[2]); val y2 = min(a[3], b[3])
        val inter = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
        val areaA = (a[2] - a[0]).coerceAtLeast(0f) * (a[3] - a[1]).coerceAtLeast(0f)
        val areaB = (b[2] - b[0]).coerceAtLeast(0f) * (b[3] - b[1]).coerceAtLeast(0f)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}
