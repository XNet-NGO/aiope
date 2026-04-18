package com.aiope2.core.terminal.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads and configures an Alpine Linux rootfs for proot-xed.
 * Uses bsdtar for extraction, keeps all extended proot features
 * (link2symlink, sysvipc, fake root, Android GID mapping).
 */
object ProotBootstrap {

  private const val TAG = "ProotBootstrap"
  private const val ROOTFS_VERSION = "rootfs_alpine_v1"

  fun envDir(ctx: Context) = File(ctx.filesDir, "env")
  fun rootfsDir(ctx: Context) = File(envDir(ctx), "alpine")
  private fun marker(ctx: Context, name: String) = File(envDir(ctx), ".$name")

  fun isInstalled(ctx: Context): Boolean {
    val rootfs = rootfsDir(ctx)
    return marker(ctx, ROOTFS_VERSION).exists() &&
      rootfs.isDirectory &&
      (File(rootfs, "bin/busybox").exists() || File(rootfs, "bin/sh").exists())
  }

  /** Find proot-xed binary, handling Android renaming. */
  fun findProotXed(ctx: Context): File? {
    val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    for (name in arrayOf("libproot-xed.so", "libroot-xed.so")) {
      val f = File(nativeDir, name)
      if (f.isFile) return f
    }
    return null
  }

  /** Re-apply patches to an existing rootfs (e.g. after app update). */
  fun ensurePatched(ctx: Context, log: (String) -> Unit) {
    val rootfs = rootfsDir(ctx)
    if (rootfs.isDirectory) patchRootfs(rootfs, log)
  }

  /**
   * Full bootstrap: create dirs, copy talloc, download Alpine rootfs, patch.
   * Call on a background thread.
   */
  fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
    val l: (String) -> Unit = { msg ->
      Log.d(TAG, msg)
      logCb(msg)
    }
    try {
      val filesDir = ctx.filesDir
      val nativeDir = ctx.applicationInfo.nativeLibraryDir
      val envDir = envDir(ctx)
      val rootfs = rootfsDir(ctx)

      l("Creating directories...")
      listOf(
        envDir,
        rootfs,
        File(filesDir, "tmp"),
        File(filesDir, "home"),
      ).forEach { it.mkdirs() }

      // talloc — proot-xed needs libtalloc.so.2
      val tallocSrc = File(nativeDir, "libtalloc.so")
      val tallocDst = File(filesDir, "libtalloc.so.2")
      if (tallocSrc.exists()) {
        tallocSrc.inputStream().use { i ->
          tallocDst.outputStream().use { o -> i.copyTo(o) }
        }
        l("libtalloc.so.2 ready")
      } else {
        l("ERROR: libtalloc.so not found")
        return false
      }

      // bsdtar for .tar.xz extraction
      val bsdtar = File(nativeDir, "libbsdtar.so")
      if (!bsdtar.exists() || !bsdtar.canExecute()) {
        l("ERROR: libbsdtar.so not found")
        return false
      }

      // Download rootfs
      val versionMarker = marker(ctx, ROOTFS_VERSION)
      if (!versionMarker.exists() || !File(rootfs, "bin/busybox").exists()) {
        if (rootfs.isDirectory) {
          l("Removing old rootfs...")
          rootfs.deleteRecursively()
          rootfs.mkdirs()
        }

        val arch = System.getProperty("os.arch")?.lowercase() ?: "aarch64"
        val pdArch = when {
          "aarch64" in arch || "arm64" in arch -> "aarch64"
          "x86_64" in arch -> "x86_64"
          else -> "aarch64"
        }
        val url = "https://github.com/xnet-admin-1/box/releases/download/rootfs-alpine-3.21.3/box-alpine-3.21-$pdArch.tar.xz"
        val tarball = File(envDir, "rootfs.tar.xz")

        l("Downloading Alpine 3.21 rootfs ($pdArch)...")
        download(url, tarball, l)

        l("Extracting rootfs...")
        val ok = runBsdtar(bsdtar, tarball, rootfs, l)
        tarball.delete()
        if (!ok || !File(rootfs, "bin/busybox").exists()) {
          l("ERROR: Extraction failed")
          return false
        }
        versionMarker.writeText("alpine-3.21")
        l("Rootfs extracted")
      } else {
        l("Rootfs already installed")
      }

      patchRootfs(rootfs, l)

      // Initial apk update inside proot
      distroSetup(ctx, l)

      l("Alpine environment ready!")
      return true
    } catch (e: Exception) {
      l("ERROR: ${e.message}")
      Log.e(TAG, "setup failed", e)
      return false
    }
  }

  private fun runBsdtar(bsdtar: File, tarball: File, destDir: File, log: (String) -> Unit): Boolean {
    destDir.mkdirs()
    val cmd = listOf(bsdtar.absolutePath, "-xf", tarball.absolutePath, "-C", destDir.absolutePath, "--no-same-owner")
    return try {
      val pb = ProcessBuilder(cmd)
      pb.redirectErrorStream(true)
      pb.environment()["LD_LIBRARY_PATH"] = bsdtar.parentFile?.absolutePath ?: ""
      val proc = pb.start()
      val output = proc.inputStream.bufferedReader().readText()
      val ok = proc.waitFor(120, TimeUnit.SECONDS)
      val exit = if (ok) proc.exitValue() else -1
      if (exit != 0) {
        log("bsdtar error (exit $exit): ${output.take(500)}")
        false
      } else {
        true
      }
    } catch (e: Exception) {
      log("bsdtar failed: ${e.message}")
      false
    }
  }

  private fun distroSetup(ctx: Context, log: (String) -> Unit) {
    val setupMarker = File(envDir(ctx), ".distro_setup_done")
    if (setupMarker.exists()) return
    log("Running apk update...")
    try {
      ProotExecutor.exec(ctx, "apk update", timeoutMs = 60_000)
      setupMarker.writeText("done")
      log("Package index updated")
    } catch (e: Exception) {
      Log.w(TAG, "apk update failed (non-fatal): ${e.message}")
      log("apk update skipped (${e.message})")
    }
  }

  private fun download(urlStr: String, dest: File, log: (String) -> Unit) {
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
        if (++redirects > 5) {
          log("ERROR: too many redirects")
          return
        }
        continue
      }
      if (code != 200) {
        log("ERROR: HTTP $code")
        conn.disconnect()
        return
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
          if (total > 0 && downloaded % (512 * 1024) < 65536) {
            log("  ${downloaded / 1024}KB / ${total / 1024}KB")
          }
        }
      }
    }
    conn.disconnect()
    log("  Download complete (${dest.length() / 1024}KB)")
  }

  /** Fix proot stubs — ensure they're simple exit-0 scripts. */
  fun fixProotStubs(rootfs: File) {
    val stub = "#!/bin/sh\nexit 0\n"
    for (rel in listOf("sbin/ldconfig", "usr/sbin/ldconfig", "usr/bin/ldconfig")) {
      val f = File(rootfs, rel)
      if (f.isFile) {
        try {
          f.writeText(stub)
          f.setExecutable(true)
        } catch (_: Exception) {}
      }
    }
  }

  private fun patchRootfs(rootfs: File, log: (String) -> Unit) {
    // DNS
    File(rootfs, "etc").mkdirs()
    File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")

    // Standard dirs
    for (d in listOf("tmp", "var/tmp", "home", "root", "root/workspace", "root/.config")) {
      File(rootfs, d).mkdirs()
    }
    File(rootfs, "tmp").setWritable(true, false)

    // Fix permissions on bin dirs and .so files
    listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin", "usr/local/sbin").forEach { dir ->
      File(rootfs, dir).walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
    }
    listOf("lib", "usr/lib").forEach { dir ->
      File(rootfs, dir).walkTopDown()
        .filter { it.isFile && (it.name.endsWith(".so") || it.name.contains(".so.")) }
        .forEach { it.setExecutable(true) }
    }
    // ELF interpreters
    listOf(
      "lib/ld-musl-aarch64.so.1",
      "lib/ld-musl-x86_64.so.1",
      "usr/bin/env",
      "bin/sh",
      "bin/busybox",
    ).forEach { rel ->
      val f = File(rootfs, rel)
      if (f.exists() && !f.isDirectory) f.setExecutable(true)
    }

    // Proot stubs
    fixProotStubs(rootfs)

    // Shell profile
    File(rootfs, "root/.profile").writeText(
      """
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TMPDIR=/tmp
export PS1='box:\w# '
export HISTSIZE=0
alias ls='ls --color=auto'
alias ll='ls -lah'
cd ${'$'}HOME
      """.trimIndent() + "\n",
    )

    // profile.d for non-login shells
    File(rootfs, "etc/profile.d").mkdirs()
    File(rootfs, "etc/profile.d/box.sh").writeText(
      """
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export TERM=xterm-256color
export TMPDIR=/tmp
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
      """.trimIndent() + "\n",
    )

    // Tool configs
    File(rootfs, "root/.wgetrc").writeText("quiet=on\ntimeout=30\ntries=3\n")
    File(rootfs, "root/.curlrc").writeText("--silent\n--fail\n--connect-timeout 30\n--max-time 120\n--retry 3\n")
    File(rootfs, "root/.gitconfig").writeText("[core]\n\tpager = cat\n[http]\n\tsslVerify = false\n[advice]\n\tdetachedHead = false\n")

    // apk config — quiet by default
    File(rootfs, "etc/apk").mkdirs()
    val reposFile = File(rootfs, "etc/apk/repositories")
    if (!reposFile.exists() || reposFile.readText().isBlank()) {
      reposFile.writeText("https://dl-cdn.alpinelinux.org/alpine/v3.21/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.21/community\n")
    }

    // Android GIDs
    val groupFile = File(rootfs, "etc/group")
    if (groupFile.exists()) {
      val existing = groupFile.readText()
      val knownGids = mapOf(
        3003 to "aid_inet",
        3004 to "aid_net_raw",
        3005 to "aid_net_admin",
        9997 to "aid_everybody",
        9999 to "aid_nobody",
      )
      val toAdd = knownGids.filter { (gid, _) -> ":$gid:" !in existing }
        .map { (gid, name) -> "$name:x:$gid:" }
      try {
        val uid = android.os.Process.myUid()
        val dynamicGids = listOf("u0_a${uid - 10000}:x:$uid:")
          .filter { entry -> ":${entry.split(":")[2]}:" !in existing }
        val allNew = toAdd + dynamicGids
        if (allNew.isNotEmpty()) groupFile.appendText(allNew.joinToString("\n", postfix = "\n"))
      } catch (_: Exception) {
        if (toAdd.isNotEmpty()) groupFile.appendText(toAdd.joinToString("\n", postfix = "\n"))
      }
    }

    log("Rootfs patched")
  }
}
