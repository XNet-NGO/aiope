package ngo.xnet.aiope.core.terminal.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the RT-DETR (Apache-2.0) object-detection ONNX model at runtime.
 *
 * Expected model I/O contract (official RT-DETR ONNX export, the one ObjectDetectionEngine
 * targets):
 *   Inputs:  images [1,3,640,640] float RGB NCHW 0..1 ; orig_target_sizes [1,2] int64 [[w,h]]
 *   Outputs: labels [1,300] int64 ; boxes [1,300,4] float (pixel coords) ; scores [1,300] float
 *
 * Set [MODEL_URL] to an RT-DETRv4-S ONNX export matching that contract. Left blank by default
 * so the distributor supplies the exact export they validated. Weights are downloaded (not
 * bundled), keeping the APK small.
 */
object ObjectDetectionBootstrap {

    private const val TAG = "ObjDetBootstrap"
    private const val VERSION = "rtdetr_v1"

    /** RT-DETRv4-S ONNX (labels/boxes/scores contract). Hosted on the XNet model releases. */
    const val MODEL_URL =
        "https://github.com/xnet-admin-1/box/releases/download/rtdetrv4-s/rtdetrv4_s.onnx"

    private const val MIN_BYTES = 30L * 1024 * 1024 // real export ~41.5MB; floor guards junk

    fun modelsDir(ctx: Context) = File(File(ctx.filesDir, "models"), "rtdetr")
    fun modelFile(ctx: Context) = File(modelsDir(ctx), "rtdetr.onnx")
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
        if (MODEL_URL.isBlank()) {
            l("ERROR: ObjectDetectionBootstrap.MODEL_URL is not configured")
            return false
        }
        return try {
            val dir = modelsDir(ctx)
            dir.mkdirs()
            if (isInstalled(ctx)) { l("Object-detection model already installed"); return true }
            val tmp = File(dir, "rtdetr.onnx.part")
            if (tmp.exists()) tmp.delete()
            l("Downloading RT-DETR model...")
            if (!download(MODEL_URL, tmp, l)) { tmp.delete(); return false }
            if (tmp.length() < MIN_BYTES) {
                l("ERROR: model too small (${tmp.length() / 1024}KB)"); tmp.delete(); return false
            }
            val dest = modelFile(ctx)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                tmp.delete()
            }
            marker(ctx).writeText(VERSION)
            l("Object-detection model ready (${dest.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            l("ERROR: ${e.message}")
            Log.e(TAG, "setup failed", e)
            false
        }
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit): Boolean {
        var url = URL(urlStr)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
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
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0 && downloaded % (2 * 1024 * 1024) < 65536) {
                        log("  ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                    }
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024 / 1024}MB)")
        return true
    }
}
