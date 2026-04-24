package com.aiope2.feature.chat.browser

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Minimal localhost HTTP server exposing browser control endpoints.
 * Runs on port 8735. All responses are JSON.
 *
 * Endpoints:
 *   GET /navigate?url=...
 *   GET /content
 *   GET /elements
 *   GET /click?selector=...
 *   GET /fill?selector=...&value=...
 *   GET /eval?script=...
 *   GET /back
 *   GET /status
 */
object BrowserServer {
  private const val TAG = "BrowserServer"
  private const val PORT = 8735
  private var serverSocket: ServerSocket? = null
  private var job: Job? = null
  private var browserRef: WeakReference<() -> WebBrowser>? = null

  fun start(getBrowser: () -> WebBrowser) {
    if (job != null) return
    browserRef = WeakReference(getBrowser)
    job = CoroutineScope(Dispatchers.IO).launch {
      try {
        val ss = ServerSocket(PORT)
        serverSocket = ss
        Log.i(TAG, "Listening on port $PORT")
        while (isActive) {
          val socket = ss.accept()
          launch {
            try {
              val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
              val requestLine = reader.readLine() ?: return@launch
              // Read headers (discard)
              while (reader.readLine().let { it != null && it.isNotEmpty() }) {}

              val parts = requestLine.split(" ")
              val path = if (parts.size >= 2) parts[1] else "/"
              val response = handleRequest(path)

              val writer = PrintWriter(socket.getOutputStream(), true)
              writer.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
              writer.print(response)
              writer.flush()
            } catch (e: Exception) {
              Log.e(TAG, "Request error", e)
            } finally {
              socket.close()
            }
          }
        }
      } catch (e: Exception) {
        if (isActive) Log.e(TAG, "Server error", e)
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    serverSocket?.close()
    serverSocket = null
  }

  private suspend fun handleRequest(fullPath: String): String {
    val qIdx = fullPath.indexOf('?')
    val path = if (qIdx >= 0) fullPath.substring(0, qIdx) else fullPath
    val params = if (qIdx >= 0) parseQuery(fullPath.substring(qIdx + 1)) else emptyMap()
    val browser = try {
      browserRef?.get()?.invoke()
    } catch (_: Exception) {
      null
    }
      ?: return json("error", "Browser not ready. Open the browser panel in the app first.")

    return try {
      when (path) {
        "/navigate" -> {
          val url = params["url"] ?: return json("error", "Missing url param")
          val result = browser.navigate(url)
          json("ok", result)
        }

        "/content" -> {
          val content = browser.getPageContent()
          json("ok", content)
        }

        "/elements" -> {
          val elements = browser.getElements()
          json("ok", elements)
        }

        "/click" -> {
          val selector = params["selector"] ?: return json("error", "Missing selector param")
          val result = browser.click(selector)
          json("ok", result)
        }

        "/fill" -> {
          val selector = params["selector"] ?: return json("error", "Missing selector param")
          val value = params["value"] ?: ""
          val result = browser.fill(selector, value)
          json("ok", result)
        }

        "/eval" -> {
          val script = params["script"] ?: return json("error", "Missing script param")
          val result = browser.evaluateJs(script)
          json("ok", result)
        }

        "/back" -> {
          val ok = browser.goBack()
          json("ok", if (ok) "Navigated back" else "No history")
        }

        "/scroll" -> {
          val direction = params["direction"] ?: "down"
          val amount = params["amount"]?.toIntOrNull() ?: 500
          val result = browser.scroll(direction, amount)
          json("ok", result)
        }

        "/status" -> {
          json("ok", "url=${browser.currentUrl()} title=${browser.title()}")
        }

        else -> json("error", "Unknown endpoint: $path. Available: /navigate, /content, /elements, /click, /fill, /eval, /back, /status")
      }
    } catch (e: Exception) {
      json("error", e.message ?: "Unknown error")
    }
  }

  private fun parseQuery(query: String): Map<String, String> = query.split("&").mapNotNull {
    val kv = it.split("=", limit = 2)
    if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
  }.toMap()

  private fun json(status: String, message: String): String {
    val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    return """{"status":"$status","result":"$escaped"}"""
  }
}
