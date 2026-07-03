package com.aiope2.core.terminal.shell

import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Basic Android sh shell executor. */
object ShellExecutor {

  fun exec(command: String, timeoutMs: Long = 300_000): String = try {
    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
    val output = readAll(process.inputStream, timeoutMs)
    val stderr = readAll(process.errorStream, 1_000)
    process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    val out = (output + stderr).trim()
    if (out.length >
      8000
    ) {
      out.take(8000) + "\n[truncated]"
    } else {
      out.ifEmpty { "(no output, exit ${process.exitValue()})" }
    }
  } catch (e: Exception) {
    "Error: ${e.message}"
  }

  /**
   * Read all output from a stream. Uses a background thread so we can
   * enforce a timeout without the polling/sleep loop that was causing
   * proot responses to take 15+ seconds.
   */
  internal fun readAll(stream: InputStream, timeoutMs: Long): String {
    val sb = StringBuilder()
    val thread = Thread {
      try {
        stream.bufferedReader().use { reader ->
          val buf = CharArray(4096)
          var n: Int
          while (reader.read(buf).also { n = it } != -1) {
            sb.append(buf, 0, n)
            if (sb.length > 8000) break
          }
        }
      } catch (_: Exception) {}
    }
    thread.start()
    thread.join(timeoutMs)
    if (thread.isAlive) {
      thread.interrupt()
      sb.append("\n[timeout after ${timeoutMs / 1000}s]")
    }
    return sb.toString()
  }
}
