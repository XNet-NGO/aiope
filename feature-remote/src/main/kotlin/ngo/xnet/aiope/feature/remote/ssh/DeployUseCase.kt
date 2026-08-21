package ngo.xnet.aiope.feature.remote.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.xfer.FileSystemFile
import ngo.xnet.aiope.feature.remote.db.RemoteServerDao
import ngo.xnet.aiope.feature.remote.db.RemoteServerEntity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeployUseCase @Inject constructor(
  @ApplicationContext private val context: Context,
  private val sshManager: SshSessionManager,
  private val serverDao: RemoteServerDao,
) {

  suspend fun deploy(server: RemoteServerEntity) {
    val privateKey = server.privateKey
    val password = server.password
    if (privateKey.isNullOrBlank() && password.isNullOrBlank()) {
      throw IllegalStateException("No private key or password configured for ${server.name}")
    }

    serverDao.updateStatus(server.id, "deploying")

    val bootstrapClient = if (!privateKey.isNullOrBlank()) {
      sshManager.connectWithKey(host = server.host, port = server.bootstrapPort, user = server.user, privateKey = privateKey)
    } else {
      sshManager.connectWithPassword(host = server.host, port = server.bootstrapPort, user = server.user, password = password!!)
    }

    try {
      // Clean existing install
      val cleanCmd = if (server.osType == "windows") {
        "powershell -Command \"Stop-Process -Name aiope-remote -Force -ErrorAction SilentlyContinue; schtasks /Delete /TN 'AIOPE Remote' /F 2>\$null\""
      } else {
        "systemctl stop aiope-remote 2>/dev/null; pkill -f aiope-remote 2>/dev/null; rm -f ~/.local/bin/aiope-remote"
      }
      val cleanSession = bootstrapClient.startSession()
      cleanSession.exec(cleanCmd).join(15, TimeUnit.SECONDS)
      cleanSession.close()

      if (server.osType == "windows") {
        deployWindows(bootstrapClient, server.publicKey)
      } else {
        deployLinux(bootstrapClient, server.publicKey)
      }

      // Update server to use daemon port
      val updated = server.copy(port = 2222, status = "online")
      serverDao.upsert(updated)

      // Try connecting to the daemon
      try {
        sshManager.connect(updated)
        val health = sshManager.exec(updated.id, "__aiope_health__")
        if (health.exitCode == 0) {
          try {
            val json = JSONObject(health.stdout)
            serverDao.updateHealth(updated.id, "${json.optString("os")} ${json.optString("arch")} - ${json.optString("hostname")}", json.optString("version", null))
          } catch (_: Exception) {}
        }
      } catch (_: Exception) {
        serverDao.updateStatus(updated.id, "online")
      }
    } finally {
      bootstrapClient.disconnect()
    }
  }

  private fun deployLinux(client: SSHClient, pubKey: String?) {
    val installer = File(context.cacheDir, "aiope-remote-installer.sh")
    context.assets.open("aiope-remote-installer.sh").use { input ->
      installer.outputStream().use { output -> input.copyTo(output) }
    }
    try {
      client.newSCPFileTransfer().upload(FileSystemFile(installer), "/tmp/aiope-remote-installer.sh")
      val session = client.startSession()
      val cmd = session.exec("chmod +x /tmp/aiope-remote-installer.sh && /tmp/aiope-remote-installer.sh")
      cmd.join(120, TimeUnit.SECONDS)
      val stderr = IOUtils.readFully(cmd.errorStream).toString(Charsets.UTF_8)
      val exitCode = cmd.exitStatus ?: -1
      session.close()
      if (exitCode != 0) throw RuntimeException("Installer failed (exit $exitCode): ${stderr.take(500)}")

      if (!pubKey.isNullOrBlank()) {
        val keySession = client.startSession()
        keySession.exec("mkdir -p ~/.aiope && echo '$pubKey' >> ~/.aiope/authorized_keys && chmod 600 ~/.aiope/authorized_keys").join(10, TimeUnit.SECONDS)
        keySession.close()
      }
    } finally {
      installer.delete()
    }
  }

  private fun deployWindows(client: SSHClient, pubKey: String?) {
    // Extract payload.tar.gz from the self-extracting installer on-device
    // then upload payload + ps1 installer to remote and run it
    val installer = File(context.cacheDir, "aiope-remote-installer.sh")
    context.assets.open("aiope-remote-installer.sh").use { input ->
      installer.outputStream().use { output -> input.copyTo(output) }
    }

    try {
      // Get user home
      val homeSession = client.startSession()
      val homeCmd = homeSession.exec("powershell -Command \"Write-Host \$env:USERPROFILE\"")
      homeCmd.join(5, TimeUnit.SECONDS)
      val homeOut = IOUtils.readFully(homeCmd.inputStream).toString(Charsets.UTF_8).trim()
      homeSession.close()
      val remoteDir = "$homeOut/.aiope"

      // Create staging dir on remote
      val mkdirSession = client.startSession()
      mkdirSession.exec("powershell -Command \"New-Item -ItemType Directory -Path '$remoteDir' -Force | Out-Null\"").join(10, TimeUnit.SECONDS)
      mkdirSession.close()

      // Extract the tar.gz payload from installer locally (find marker, write remainder)
      val installerBytes = installer.readBytes()
      val marker = "__ARCHIVE_BELOW__\n".toByteArray()
      val markerIdx = findBytes(installerBytes, marker)
      if (markerIdx < 0) throw RuntimeException("Could not find archive marker in installer")
      val payloadFile = File(context.cacheDir, "payload.tar.gz")
      payloadFile.outputStream().use { it.write(installerBytes, markerIdx + marker.size, installerBytes.size - markerIdx - marker.size) }

      // Upload payload.tar.gz and installer-windows.ps1 to remote
      client.newSCPFileTransfer().upload(FileSystemFile(payloadFile), "$remoteDir/payload.tar.gz")

      // Extract the ps1 from the payload (it's inside the tar.gz)
      // Run: extract tar.gz on remote, then run the ps1
      val runSession = client.startSession()
      val runCmd = runSession.exec(
        "powershell -Command \"" +
          "tar xzf '$remoteDir/payload.tar.gz' -C '$remoteDir'; " +
          "& '$remoteDir/installer-windows.ps1'; " +
          "Remove-Item '$remoteDir/payload.tar.gz' -Force -ErrorAction SilentlyContinue\"",
      )
      runCmd.join(120, TimeUnit.SECONDS)
      val stdout = IOUtils.readFully(runCmd.inputStream).toString(Charsets.UTF_8)
      val stderr = IOUtils.readFully(runCmd.errorStream).toString(Charsets.UTF_8)
      val exitCode = runCmd.exitStatus ?: -1
      runSession.close()

      android.util.Log.i("AIOPE_DEPLOY", "Windows installer output: $stdout")
      if (exitCode != 0) throw RuntimeException("Windows installer failed (exit $exitCode): ${stderr.take(500)}")

      // Install public key
      if (!pubKey.isNullOrBlank()) {
        val keySession = client.startSession()
        keySession.exec("powershell -Command \"Set-Content -Path '$remoteDir/authorized_keys' -Value '$pubKey'\"").join(10, TimeUnit.SECONDS)
        keySession.close()
      }

      payloadFile.delete()
    } finally {
      installer.delete()
    }
  }

  private fun findBytes(data: ByteArray, pattern: ByteArray): Int {
    outer@ for (i in 0..data.size - pattern.size) {
      for (j in pattern.indices) {
        if (data[i + j] != pattern[j]) continue@outer
      }
      return i
    }
    return -1
  }
}
