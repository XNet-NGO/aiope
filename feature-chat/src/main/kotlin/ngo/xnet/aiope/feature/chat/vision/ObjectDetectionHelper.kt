package ngo.xnet.aiope.feature.chat.vision

import android.content.Context
import android.graphics.Bitmap
import ngo.xnet.aiope.core.terminal.shell.ObjectDetectionBootstrap
import org.xnet.aiope.inference.Detection
import org.xnet.aiope.inference.ObjectDetectionEngine
import org.xnet.aiope.inference.YoloDetectionEngine

/**
 * App-side facade over the on-device RT-DETR [ObjectDetectionEngine]. Owns a cached engine
 * instance (session load is expensive) and provides bitmap detection plus a human/agent-friendly
 * summary. Shared by the snapshot ("Detect & Send") and live-preview flows.
 */
object ObjectDetectionHelper {

    @Volatile private var engine: ObjectDetectionEngine? = null
    @Volatile private var fastEngine: YoloDetectionEngine? = null

    fun isReady(ctx: Context): Boolean =
        ObjectDetectionBootstrap.isConfigured() && ObjectDetectionBootstrap.isInstalled(ctx)

    /** Whether the fast (YOLOv9-s) live-path detector is downloaded. */
    fun isFastReady(ctx: Context): Boolean =
        ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.isInstalled(ctx)

    @Synchronized
    fun engine(ctx: Context): ObjectDetectionEngine? {
        if (!isReady(ctx)) return null
        engine?.let { return it }
        val e = ObjectDetectionEngine(ObjectDetectionBootstrap.modelFile(ctx))
        engine = e
        return e
    }

    @Synchronized
    fun fastEngine(ctx: Context): YoloDetectionEngine? {
        if (!isFastReady(ctx)) return null
        fastEngine?.let { return it }
        val e = YoloDetectionEngine(ngo.xnet.aiope.core.terminal.shell.YoloV9Bootstrap.modelFile(ctx))
        fastEngine = e
        return e
    }

    fun detect(ctx: Context, bitmap: Bitmap): List<Detection> =
        engine(ctx)?.detect(bitmap) ?: emptyList()

    /**
     * Fast detection for the live viewfinder: prefer the lightweight YOLOv9-s when installed,
     * otherwise fall back to RT-DETR if available.
     */
    fun detectFast(ctx: Context, bitmap: Bitmap): List<Detection> =
        fastEngine(ctx)?.detect(bitmap) ?: detect(ctx, bitmap)

    /** True if EITHER detector is usable (fast or accurate). */
    fun anyReady(ctx: Context): Boolean = isFastReady(ctx) || isReady(ctx)

    /** One-line-per-object summary suitable for prefilling a chat message to the agent. */
    fun summarize(detections: List<Detection>): String {
        if (detections.isEmpty()) return "Object detection: no objects found."
        val counts = detections.groupingBy { it.label }.eachCount()
        val tally = counts.entries.sortedByDescending { it.value }
            .joinToString(", ") { (label, n) -> if (n > 1) "$n $label" else label }
        val lines = detections.sortedByDescending { it.score }.joinToString("\n") {
            "- ${it.label} (${"%.2f".format(it.score)}) at [${it.x1.toInt()},${it.y1.toInt()},${it.x2.toInt()},${it.y2.toInt()}]"
        }
        return "Object detection found: $tally.\n$lines"
    }

    @Synchronized
    fun reset() {
        try { engine?.close() } catch (_: Throwable) {}
        try { fastEngine?.close() } catch (_: Throwable) {}
        engine = null
        fastEngine = null
    }
}
