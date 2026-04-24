package com.aiope2.feature.chat.engine

import android.util.Log
import java.io.File

object ImageProcessor {

  fun saveImagesToDisk(filesDir: File, contentResolver: android.content.ContentResolver, msgId: String, uris: List<String>): String {
    if (uris.isEmpty()) return ""
    val dir = File(filesDir, "chat_images").also { it.mkdirs() }
    return uris.mapIndexedNotNull { i, uriStr ->
      try {
        val input = contentResolver.openInputStream(android.net.Uri.parse(uriStr)) ?: return@mapIndexedNotNull null
        val bytes = input.readBytes()
        input.close()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapIndexedNotNull null
        val file = File(dir, "${msgId}_$i.jpg")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        "chat_images/${msgId}_$i.jpg"
      } catch (e: Exception) {
        Log.w("ImageProcessor", "image save failed: ${e.message}")
        null
      }
    }.joinToString(",")
  }

  fun encodeImages(filesDir: File, savedPaths: String): List<String> =
    savedPaths.split(",").filter { it.isNotBlank() }.mapNotNull { relPath ->
      try {
        val file = File(filesDir, relPath)
        if (!file.exists()) return@mapNotNull null
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
        val padded = padToSquare(bmp)
        val scaled = android.graphics.Bitmap.createScaledBitmap(padded, 448, 448, true)
        if (padded != bmp) padded.recycle()
        bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
      } catch (e: Exception) {
        Log.w("ImageProcessor", "image encode failed: ${e.message}")
        null
      }
    }

  fun padToSquare(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = bmp.width
    val h = bmp.height
    if (w == h) return bmp
    val size = maxOf(w, h)
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.drawBitmap(bmp, ((size - w) / 2f), ((size - h) / 2f), null)
    return out
  }
}
