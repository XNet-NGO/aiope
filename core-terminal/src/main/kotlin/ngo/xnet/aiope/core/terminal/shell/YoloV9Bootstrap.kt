package ngo.xnet.aiope.core.terminal.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the YOLOv9-s (LibreYOLO, MIT) object-detection ONNX at runtime for the fast/live
 * detection path. Input images[1,3,640,640]; output [1,84,8400] (80 COCO classes).
 * Mirrors ObjectDetectionBootstrap conventions.
 */
object YoloV9Bootstrap {

    private const val TAG = "YoloV9Bootstrap"
    private const val VERSION = "yolov9s_v1"

    const val MODEL_URL =
        "https://github.com/XNet-NGO/deps/releases/download/yolov9s/yolov9s.onnx"

    private const val MIN_BYTES = 15L * 1024 * 1024 // real ~29MB

    fun modelsDir(ctx: Context) = File(File(ctx.filesDir, "models"), "yolov9s")
    fun modelFile(ctx: Context) = File(modelsDir(ctx), "yolov9s.onnx")
    private fun marker(ctx: Context) = File(modelsDir(ctx), ".$VERSION")

    fun isConfigured(): Boolean = MODEL_URL.isNotBlank()

    fun isInstalled(ctx: Context): Boolean {
        val f = modelFile(ctx)
        return marker(ctx).exists() && f.isFile && f.length() >= MIN_BYTES
    }

    fun installedBytes(ctx: Context): Long = modelFile(ctx).let { if (it.isFile) it.length() else 0L }

    fun remove(ctx: Context) {
        modelFile(ctx).delete()
        marker(ctx).delete()
    }

    fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
        val l: (String) -> Unit = { Log.d(TAG, it); logCb(it) }
        if (MODEL_URL.isBlank()) { l("ERROR: MODEL_URL not configured"); return false }
        return try {
            val dir = modelsDir(ctx); dir.mkdirs()
            if (isInstalled(ctx)) { l("YOLOv9-s already installed"); return true }
            val tmp = File(dir, "yolov9s.onnx.part"); if (tmp.exists()) tmp.delete()
            l("Downloading YOLOv9-s (~29MB)...")
            if (!download(MODEL_URL, tmp, l)) { tmp.delete(); return false }
            if (tmp.length() < MIN_BYTES) { l("ERROR: too small (${tmp.length()/1024}KB)"); tmp.delete(); return false }
            val dest = modelFile(ctx)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }; tmp.delete() }
            marker(ctx).writeText(VERSION)
            l("YOLOv9-s ready (${dest.length()/1024/1024}MB)")
            true
        } catch (e: Exception) {
            l("ERROR: ${e.message}"); Log.e(TAG, "setup failed", e); false
        }
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit): Boolean {
        var url = URL(urlStr); var redirects = 0; var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc); conn.disconnect()
                if (++redirects > 6) { log("ERROR: too many redirects"); return false }
                continue
            }
            if (code != 200) { log("ERROR: HTTP $code"); conn.disconnect(); return false }
            break
        }
        val total = conn.contentLength.toLong(); var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536); var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n); downloaded += n
                    if (total > 0 && downloaded % (2*1024*1024) < 65536) log("  ${downloaded/1024/1024}MB / ${total/1024/1024}MB")
                }
            }
        }
        conn.disconnect(); log("  Download complete (${dest.length()/1024/1024}MB)"); return true
    }
}
