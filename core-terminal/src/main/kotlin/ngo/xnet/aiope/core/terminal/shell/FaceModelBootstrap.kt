package ngo.xnet.aiope.core.terminal.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads on-device face models at runtime for consent-based, on-device face features:
 *  - YuNet (MIT): lightweight face DETECTION (boxes + 5 landmarks). ~227 KB.
 *  - ArcFace (InsightFace, MIT code): face IDENTITY embedding, 112x112 RGB -> 512-d. ~130 MB.
 *
 * Identity use is opt-in only: an embedding is produced solely for a user who explicitly
 * enrolls themselves. Models and embeddings never leave the device. Mirrors
 * [BekkoModelBootstrap] conventions (app-private storage, redirect-following download,
 * size floors, version markers).
 */
object FaceModelBootstrap {

  private const val TAG = "FaceModelBootstrap"
  private const val VERSION = "face_v2"

  // YuNet fixed-input detection model (opencv_zoo, MIT). Served via Git LFS -> follow redirects.
  private const val DETECT_URL =
    "https://github.com/XNet-NGO/deps/releases/download/yunet/yunet.onnx"
  private const val MIN_DETECT_BYTES = 100L * 1024          // ~227 KB real

  // iResNet100 ArcFace identity embedder (LibreYOLO/librefacerec-l, Apache-2.0).
  // input data[N,3,112,112] RGB NCHW (px-127.5)/128 -> 512-d. Stronger discrimination than
  // the old ResNet50 ArcFace (which collapsed to ~0.98 between different people).
  private const val EMBED_URL =
    "https://github.com/XNet-NGO/deps/releases/download/librefacerec-l/librefacerec-l.onnx"
  private const val MIN_EMBED_BYTES = 200L * 1024 * 1024    // ~260 MB real

  fun modelsDir(ctx: Context) = File(File(ctx.filesDir, "models"), "face")
  fun detectFile(ctx: Context) = File(modelsDir(ctx), "yunet.onnx")
  fun embedFile(ctx: Context) = File(modelsDir(ctx), "arcface.onnx")
  private fun marker(ctx: Context) = File(modelsDir(ctx), ".$VERSION")

  fun isInstalled(ctx: Context): Boolean {
    val d = detectFile(ctx)
    val e = embedFile(ctx)
    return marker(ctx).exists() &&
      d.isFile && d.length() >= MIN_DETECT_BYTES &&
      e.isFile && e.length() >= MIN_EMBED_BYTES
  }

  fun installedBytes(ctx: Context): Long {
    val d = if (detectFile(ctx).isFile) detectFile(ctx).length() else 0L
    val e = if (embedFile(ctx).isFile) embedFile(ctx).length() else 0L
    return d + e
  }

  /** Human-readable status for the settings UI — never stale, always derived from disk. */
  fun statusText(ctx: Context): String {
    if (isInstalled(ctx)) {
      val mb = installedBytes(ctx) / (1024 * 1024)
      return "Installed ($VERSION, ~$mb MB)"
    }
    // Files present but wrong version/size => needs (re)download.
    val stale = marker(ctx).let { !it.exists() } && embedFile(ctx).isFile
    return if (stale) "Update required — tap Download" else "Not installed"
  }

  fun remove(ctx: Context) {
    detectFile(ctx).delete()
    embedFile(ctx).delete()
    marker(ctx).delete()
  }

  /** Download both face models. Call on a background thread. @return true on success. */
  fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
    val l: (String) -> Unit = { msg ->
      Log.d(TAG, msg)
      logCb(msg)
    }
    return try {
      val dir = modelsDir(ctx)
      dir.mkdirs()
      if (isInstalled(ctx)) {
        l("Face models already installed")
        return true
      }

      // Version changed (or partial/old files present): purge stale model files + old markers so
      // the new model fully replaces the old one and no stale bytes linger.
      dir.listFiles()?.forEach { f ->
        if (f.name.startsWith(".face_") && f.name != ".$VERSION") f.delete()
      }
      detectFile(ctx).delete()
      embedFile(ctx).delete()

      // Detection model (small)
      l("Downloading face detection model (YuNet)...")
      if (!downloadTo(DETECT_URL, detectFile(ctx), MIN_DETECT_BYTES, dir, "yunet", l)) return false

      // Identity embedder (large)
      l("Downloading face identity model (~260MB)...")
      if (!downloadTo(EMBED_URL, embedFile(ctx), MIN_EMBED_BYTES, dir, "arcface", l)) return false

      marker(ctx).writeText(VERSION)
      l("Face models ready (${installedBytes(ctx) / 1024 / 1024}MB)")
      true
    } catch (e: Exception) {
      l("ERROR: ${e.message}")
      Log.e(TAG, "setup failed", e)
      false
    }
  }

  private fun downloadTo(
    url: String,
    dest: File,
    minBytes: Long,
    dir: File,
    tmpName: String,
    log: (String) -> Unit,
  ): Boolean {
    val tmp = File(dir, "$tmpName.part")
    if (tmp.exists()) tmp.delete()
    if (!download(url, tmp, log)) {
      tmp.delete()
      log("ERROR: download failed for $tmpName")
      return false
    }
    if (tmp.length() < minBytes) {
      log("ERROR: $tmpName too small (${tmp.length() / 1024}KB)")
      tmp.delete()
      return false
    }
    if (dest.exists()) dest.delete()
    if (!tmp.renameTo(dest)) {
      tmp.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
      tmp.delete()
    }
    return true
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
        url = URL(url, loc)
        conn.disconnect()
        if (++redirects > 6) {
          log("ERROR: too many redirects")
          return false
        }
        continue
      }
      if (code != 200) {
        log("ERROR: HTTP $code")
        conn.disconnect()
        return false
      }
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
            val pct = downloaded * 100 / total
            log("  ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB ($pct%)")
          }
        }
      }
    }
    conn.disconnect()
    log("  Download complete (${dest.length() / 1024}KB)")
    return true
  }
}
