package ngo.xnet.aiope.feature.chat.fileserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class FileServerService : Service() {

  companion object {
    const val CHANNEL_ID = "aiope_fileserver"
    const val NOTIFICATION_ID = 42
    const val EXTRA_ROOT_PATH = "root_path"
    const val EXTRA_PORT = "port"
    const val EXTRA_USE_HTTPS = "use_https"
    const val EXTRA_PIN = "pin"
    const val DEFAULT_PORT = 8080

    private var instance: FileServerService? = null
    fun isRunning(): Boolean = instance != null
    fun currentUrl(): String? = instance?.serverUrl

    fun start(context: Context, rootPath: String, port: Int = DEFAULT_PORT, useHttps: Boolean = false, pin: String? = null) {
      val intent = Intent(context, FileServerService::class.java).apply {
        putExtra(EXTRA_ROOT_PATH, rootPath)
        putExtra(EXTRA_PORT, port)
        putExtra(EXTRA_USE_HTTPS, useHttps)
        putExtra(EXTRA_PIN, pin)
      }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, FileServerService::class.java))
    }
  }

  private var serverSocket: ServerSocket? = null
  private var serverThread: Thread? = null
  private var rootDir: File = File("/")
  private var port: Int = DEFAULT_PORT
  private var useHttps: Boolean = false
  private var pin: String? = null
  private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
  var serverUrl: String? = null
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this
    createChannel()
    // Acquire WiFi lock to prevent WiFi from sleeping when screen is off
    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "aiope:fileserver").apply { acquire() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val rootPath = intent?.getStringExtra(EXTRA_ROOT_PATH) ?: return START_NOT_STICKY
    port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
    useHttps = intent.getBooleanExtra(EXTRA_USE_HTTPS, false)
    pin = intent.getStringExtra(EXTRA_PIN)?.ifBlank { null }
    rootDir = File(rootPath)

    if (!rootDir.exists() || !rootDir.isDirectory) {
      stopSelf()
      return START_NOT_STICKY
    }

    val ip = getWifiIp()
    val scheme = if (useHttps) "https" else "http"
    serverUrl = "$scheme://$ip:$port"
    startForeground(NOTIFICATION_ID, buildNotification(serverUrl!!))
    startServer()
    return START_STICKY
  }

  override fun onDestroy() {
    instance = null
    serverUrl = null
    serverSocket?.close()
    serverThread?.interrupt()
    wifiLock?.let { if (it.isHeld) it.release() }
    wifiLock = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startServer() {
    serverThread = thread(name = "FileServer") {
      try {
        serverSocket = if (useHttps) {
          createSslServerSocket(port)
        } else {
          ServerSocket(port)
        }
        while (!Thread.interrupted()) {
          val socket = serverSocket?.accept() ?: break
          thread { handleClient(socket) }
        }
      } catch (_: Exception) {
        // Server closed
      }
    }
  }

  private fun handleClient(socket: Socket) {
    try {
      socket.keepAlive = true
      socket.tcpNoDelay = true
      socket.use { s ->
        val input = s.getInputStream()

        // Read request line and headers byte-by-byte to avoid buffered reader consuming body
        val headerBytes = StringBuilder()
        var prev = 0
        var curr: Int
        var headersDone = false
        while (input.read().also { curr = it } != -1) {
          headerBytes.append(curr.toChar())
          if (prev == '\r'.code && curr == '\n'.code) {
            // Check for empty line (end of headers)
            val s2 = headerBytes.toString()
            if (s2.endsWith("\r\n\r\n")) {
              headersDone = true
              break
            }
          }
          prev = curr
        }
        if (!headersDone) return

        val headerStr = headerBytes.toString()
        val lines = headerStr.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return

        val method = parts[0]
        val rawPath = URLDecoder.decode(parts[1], "UTF-8")
        val safePath = rawPath.replace("..", "").removePrefix("/")

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
          val colonIdx = lines[i].indexOf(':')
          if (colonIdx > 0) headers[lines[i].substring(0, colonIdx).trim().lowercase()] = lines[i].substring(colonIdx + 1).trim()
        }

        val out = BufferedOutputStream(s.getOutputStream())

        // PIN authentication check
        if (pin != null) {
          val authHeader = headers["authorization"]
          val queryPin = rawPath.substringAfter("pin=", "").substringBefore("&").ifEmpty { null }
          val cookiePin = headers["cookie"]?.let { Regex("pin=([^;]+)").find(it)?.groupValues?.get(1) }
          val authenticated = authHeader == "Bearer $pin" || queryPin == pin || cookiePin == pin
          if (!authenticated) {
            // Check if this is a PIN submission via POST form
            val formPin = headers["x-pin"]
            if (formPin == pin) {
              // Set cookie and redirect
              out.write("HTTP/1.1 302 Found\r\nSet-Cookie: pin=$pin; Path=/; HttpOnly\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
              out.flush()
              return
            }
            // Show login page
            sendPinPrompt(out)
            out.flush()
            return
          }
        }

        if (method == "POST" && headers["content-type"]?.contains("multipart/form-data") == true) {
          handleUpload(input, headers, safePath, out)
        } else {
          val file = File(rootDir, safePath)
          if (!file.exists()) {
            sendError(out, 404, "Not Found")
          } else if (file.isDirectory) {
            sendDirectoryListing(out, file, rawPath)
          } else {
            sendFile(out, file)
          }
        }
        out.flush()
      }
    } catch (_: Exception) {
      // Client disconnected
    }
  }

  private fun handleUpload(input: java.io.InputStream, headers: Map<String, String>, path: String, out: BufferedOutputStream) {
    try {
      val contentType = headers["content-type"] ?: ""
      val boundary = "--" + contentType.substringAfter("boundary=").trim()
      val endBoundary = "$boundary--"
      val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
      val body = ByteArray(contentLength)
      var read = 0
      while (read < contentLength) {
        val n = input.read(body, read, contentLength - read)
        if (n < 0) break
        read += n
      }

      val uploadDir = File(rootDir, path)
      if (!uploadDir.exists()) uploadDir.mkdirs()

      val boundaryBytes = boundary.toByteArray()
      var savedCount = 0
      // Find each part between boundaries
      var pos = indexOf(body, boundaryBytes, 0)
      while (pos >= 0) {
        val nextPos = indexOf(body, boundaryBytes, pos + boundaryBytes.size)
        if (nextPos < 0) break
        // Parse part headers
        val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), pos)
        if (headerEnd < 0 || headerEnd > nextPos) {
          pos = nextPos
          continue
        }
        val partHeaders = String(body, pos + boundaryBytes.size + 2, headerEnd - pos - boundaryBytes.size - 2)
        val filenameMatch = Regex("filename=\"([^\"]+)\"").find(partHeaders)
        if (filenameMatch == null) {
          pos = nextPos
          continue
        }
        val filename = filenameMatch.groupValues[1]
        if (filename.isBlank()) {
          pos = nextPos
          continue
        }
        // File content is between header end+4 and next boundary-2 (strip trailing \r\n)
        val contentStart = headerEnd + 4
        val contentEnd = nextPos - 2
        if (contentEnd > contentStart) {
          File(uploadDir, filename).outputStream().use { it.write(body, contentStart, contentEnd - contentStart) }
          savedCount++
        }
        pos = nextPos
      }

      val redirectPath = if (path.isBlank()) "/" else "/$path"
      val msg = "$savedCount file(s) uploaded"
      out.write("HTTP/1.1 303 See Other\r\nLocation: $redirectPath\r\nContent-Length: ${msg.length}\r\nConnection: close\r\n\r\n$msg".toByteArray())
    } catch (e: Exception) {
      sendError(out, 500, "Upload failed: ${e.message}")
    }
  }

  private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
    outer@ for (i in from..data.size - pattern.size) {
      for (j in pattern.indices) {
        if (data[i + j] != pattern[j]) continue@outer
      }
      return i
    }
    return -1
  }

  private fun sendDirectoryListing(out: BufferedOutputStream, dir: File, path: String) {
    val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    val html = buildString {
      append("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
      append("<title>AIOPE Files - ${dir.name}</title>")
      append("<style>body{font-family:system-ui;margin:20px;background:#1a1a1a;color:#eee}a{color:#6cf;text-decoration:none}a:hover{text-decoration:underline}")
      append(".entry{padding:8px 12px;border-bottom:1px solid #333;display:flex;justify-content:space-between}")
      append(".size{color:#888;font-size:0.9em}</style></head><body>")
      append("<h2>📁 ${dir.name.ifEmpty { "/" }}</h2>")
      if (path != "/") {
        val parent = path.removeSuffix("/").substringBeforeLast("/").ifEmpty { "/" }
        append("<div class='entry'><a href='$parent'>⬆️ ..</a></div>")
      }
      for (f in files) {
        val name = f.name
        val href = if (path.endsWith("/")) "$path$name" else "$path/$name"
        val icon = if (f.isDirectory) "📁" else "📄"
        val size = if (f.isFile) formatSize(f.length()) else ""
        append("<div class='entry'><a href='${URLEncoder.encode(href, "UTF-8").replace("+", "%20")}'>$icon $name</a><span class='size'>$size</span></div>")
      }
      append("<hr><form method='POST' enctype='multipart/form-data' style='margin:12px 0'>")
      append("<input type='file' name='file' multiple style='color:#eee;margin-right:8px'>")
      append("<button type='submit' style='padding:6px 16px;background:#6cf;color:#000;border:none;border-radius:4px;cursor:pointer'>Upload</button>")
      append("</form>")
      append("<p style='font-size:0.8em;color:#666'>AIOPE File Server • ${files.size} items</p>")
      append("</body></html>")
    }
    val bytes = html.toByteArray()
    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
    out.write(bytes)
  }

  private fun sendFile(out: BufferedOutputStream, file: File) {
    val mime = guessMime(file.name)
    val bytes = file.length()
    out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: $bytes\r\nContent-Disposition: inline; filename=\"${file.name}\"\r\nConnection: close\r\n\r\n".toByteArray())
    out.flush()
    file.inputStream().use { it.copyTo(out, 65536) }
  }

  private fun sendPinPrompt(out: BufferedOutputStream) {
    val html = """<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>AIOPE Files - Login</title>
<style>body{font-family:system-ui;margin:0;background:#1a1a1a;color:#eee;display:flex;align-items:center;justify-content:center;height:100vh}
.box{background:#222;padding:32px;border-radius:12px;text-align:center}
input{padding:12px;font-size:18px;border:1px solid #444;border-radius:6px;background:#333;color:#eee;text-align:center;letter-spacing:4px;width:150px}
button{margin-top:16px;padding:10px 24px;background:#6cf;color:#000;border:none;border-radius:6px;cursor:pointer;font-size:16px}</style></head>
<body><div class='box'><h2>🔒 PIN Required</h2>
<form onsubmit="fetch('/',{method:'POST',headers:{'X-Pin':document.getElementById('p').value}}).then(r=>{if(r.redirected)location.href=r.url;else alert('Wrong PIN')});return false;">
<input id='p' type='password' maxlength='8' placeholder='PIN' autofocus><br><button type='submit'>Enter</button></form></div></body></html>
    """.trimIndent()
    val bytes = html.toByteArray()
    out.write("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
    out.write(bytes)
  }

  private fun createSslServerSocket(port: Int): ServerSocket {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()

    // Generate self-signed X.509 cert using BouncyCastle
    val now = java.util.Date()
    val until = java.util.Date(now.time + 365L * 24 * 60 * 60 * 1000)
    val issuer = org.bouncycastle.asn1.x500.X500Name("CN=AIOPE File Server")
    val serial = java.math.BigInteger.valueOf(System.currentTimeMillis())
    val subjectPublicKeyInfo = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(kp.public.encoded)
    val certBuilder = org.bouncycastle.cert.X509v3CertificateBuilder(issuer, serial, now, until, issuer, subjectPublicKeyInfo)
    val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
    val certHolder = certBuilder.build(signer)
    val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(certHolder)

    val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setKeyEntry("aiope", kp.private, charArrayOf(), arrayOf(cert))
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, charArrayOf())
    val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
    sslCtx.init(kmf.keyManagers, null, null)
    return sslCtx.serverSocketFactory.createServerSocket(port)
  }

  private fun sendError(out: BufferedOutputStream, code: Int, msg: String) {
    val body = "<html><body><h1>$code $msg</h1></body></html>".toByteArray()
    out.write("HTTP/1.1 $code $msg\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
    out.write(body)
  }

  private fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "application/javascript"
    "json" -> "application/json"
    "txt", "log", "md" -> "text/plain"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    "tar", "gz", "tgz" -> "application/gzip"
    else -> "application/octet-stream"
  }

  private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / 1048576.0)} MB"
    else -> "${"%.1f".format(bytes / 1073741824.0)} GB"
  }

  private fun getWifiIp(): String {
    // Prefer WiFi/LAN interface IP over VPN/global
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        // Skip VPN/loopback interfaces
        val name = intf.name.lowercase()
        if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("lo")) continue
        for (addr in intf.inetAddresses) {
          if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
          val hostAddr = addr.hostAddress ?: continue
          // Prefer private LAN ranges
          if (hostAddr.startsWith("192.168.") || hostAddr.startsWith("10.") || hostAddr.startsWith("172.")) {
            return hostAddr
          }
        }
      }
    } catch (_: Exception) {}
    // Fallback to WifiManager
    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ip = wm.connectionInfo.ipAddress
    return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
  }

  private fun createChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "File Server",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "AIOPE local file server"
      setShowBadge(false)
      setSound(null, null)
    }
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
  }

  private fun buildNotification(url: String): Notification {
    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("File Server Running")
      .setContentText(url)
      .setSmallIcon(android.R.drawable.ic_menu_share)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(pending)
      .build()
  }
}
