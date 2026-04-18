package com.aiope2.core.terminal.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes commands inside a proot Alpine Linux environment.
 * Tracks running process for external cancellation.
 */
object ProotExecutor {

  private const val TAG = "ProotExecutor"

  @Volatile private var currentProcess: Process? = null

  fun exec(context: Context, command: String, timeoutMs: Long = 30_000): String {
    val filesDir = context.filesDir.absolutePath
    val rootfs = File(filesDir, "env/alpine")
    val nativeLibDir = context.applicationInfo.nativeLibraryDir

    val prootBin = ProotBootstrap.findProotXed(context)
      ?: return "Error: proot binary not found in $nativeLibDir"
    if (!rootfs.isDirectory) return "Error: Alpine rootfs not installed. Run setup first."

    val talloc = File(filesDir, "libtalloc.so.2")
    if (!talloc.exists()) {
      val src = File(nativeLibDir, "libtalloc.so")
      if (src.exists()) {
        src.inputStream().use { i ->
          talloc.outputStream().use { o -> i.copyTo(o) }
        }
      }
    }

    File(filesDir, "tmp").mkdirs()
    val rootDir = File(rootfs, "root")
    rootDir.mkdirs()
    if (!rootDir.isDirectory) Log.w(TAG, "/root dir creation failed at ${rootDir.absolutePath}")
    // Ensure /etc/group has our GID to suppress "cannot find name for group ID" warning
    val groupFile = File(rootfs, "etc/group")
    if (groupFile.exists()) {
      val content = groupFile.readText()
      if (!content.contains(":1077:")) groupFile.appendText("android:x:1077:\n")
    }

    val args = buildProotArgs(rootfs, filesDir, command)
    val env = buildProotEnv(filesDir, nativeLibDir)

    Log.d(TAG, "exec: ${prootBin.name} ${args.take(5).joinToString(" ")}...")

    return try {
      val pb = ProcessBuilder(listOf(prootBin.absolutePath) + args)
      pb.environment().clear()
      pb.environment().putAll(env)
      pb.redirectErrorStream(true)
      val process = pb.start()
      currentProcess = process

      val output = ShellExecutor.readAll(process.inputStream, timeoutMs)
      process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      currentProcess = null
      val out = output.lines()
        .filter { !it.startsWith("proot warning:") && !it.startsWith("proot info:") }
        .joinToString("\n").trim()
      if (out.length > 8000) {
        out.take(8000) + "\n[truncated]"
      } else {
        out.ifEmpty { "(no output, exit ${process.exitValue()})" }
      }
    } catch (e: Exception) {
      currentProcess = null
      Log.e(TAG, "exec failed", e)
      "Error: ${e.message}"
    }
  }

  /** Kill any running proot process. */
  fun cancel() {
    currentProcess?.let {
      Log.i(TAG, "Cancelling running proot process")
      it.destroyForcibly()
      currentProcess = null
    }
  }

  private fun buildProotArgs(rootfs: File, filesDir: String, command: String): List<String> {
    val args = mutableListOf("--kill-on-exit")

    fun bind(src: String, dst: String? = null) {
      args.addAll(listOf("-b", if (dst != null) "$src:$dst" else src))
    }

    for (mnt in listOf(
      "/apex",
      "/odm",
      "/product",
      "/system",
      "/system_ext",
      "/vendor",
      "/linkerconfig/ld.config.txt",
      "/linkerconfig/com.android.art/ld.config.txt",
    )) {
      val f = File(mnt)
      if (f.exists()) bind(f.canonicalPath)
    }

    bind("/dev")
    bind("/dev/urandom", "/dev/random")
    bind("/proc")
    bind("/sys")
    bind("/proc/self/fd", "/dev/fd")
    bind(filesDir)

    val tmpDir = File(rootfs, "tmp").also { it.mkdirs() }
    val homeDir = File(filesDir, "home").also { it.mkdirs() }
    bind(homeDir.absolutePath, "/root")
    bind(tmpDir.absolutePath, "/dev/shm")

    bind("/proc/self/fd/0", "/dev/stdin")
    bind("/proc/self/fd/1", "/dev/stdout")
    bind("/proc/self/fd/2", "/dev/stderr")

    val fipsFile = File(tmpDir, "fips_enabled").also { it.writeText("0\n") }
    bind(fipsFile.absolutePath, "/proc/sys/crypto/fips_enabled")

    val shell = if (File(rootfs, "bin/bash").exists()) "/bin/bash" else "/bin/sh"

    args.addAll(
      listOf(
        "-r", rootfs.absolutePath,
        "-0", "--link2symlink", "--sysvipc", "-L",
        "-w", "/root",
        "/usr/bin/env",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME=/root", "USER=root", "TERM=xterm-256color",
        "TMPDIR=/tmp", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
        shell, "-c", command,
      ),
    )
    return args
  }

  private fun buildProotEnv(filesDir: String, nativeLibDir: String): Map<String, String> {
    val env = mutableMapOf(
      "PROOT_TMP_DIR" to "$filesDir/tmp",
      "PROOT_VERBOSE" to "-1",
      "LD_LIBRARY_PATH" to filesDir,
    )
    val loader = File(nativeLibDir, "libproot.so")
    val loader32 = File(nativeLibDir, "libproot32.so")
    if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
    if (loader32.exists()) env["PROOT_LOADER32"] = loader32.absolutePath
    return env
  }
}
