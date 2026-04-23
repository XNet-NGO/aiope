package com.aiope2.feature.remote.ssh

import android.content.Context
import com.aiope2.feature.remote.db.RemoteServerDao
import com.aiope2.feature.remote.db.RemoteServerEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
      ?: throw IllegalStateException("No private key configured for ${server.name}")

    serverDao.updateStatus(server.id, "deploying")

    // Connect to bootstrap port (standard SSH) using the stored key
    val bootstrapClient = sshManager.connectWithKey(
      host = server.host,
      port = server.bootstrapPort,
      user = server.user,
      privateKey = privateKey,
    )

    try {
      // Copy installer from assets to temp file
      val installer = File(context.cacheDir, "aiope-remote-installer.sh")
      context.assets.open("aiope-remote-installer.sh").use { input ->
        installer.outputStream().use { output -> input.copyTo(output) }
      }

      // SCP installer to remote
      bootstrapClient.newSCPFileTransfer().upload(
        net.schmizz.sshj.xfer.FileSystemFile(installer),
        "/tmp/aiope-remote-installer.sh",
      )

      // Execute installer
      val session = bootstrapClient.startSession()
      val cmd = session.exec("chmod +x /tmp/aiope-remote-installer.sh && /tmp/aiope-remote-installer.sh")
      cmd.join(120, java.util.concurrent.TimeUnit.SECONDS)
      val stdout = net.schmizz.sshj.common.IOUtils.readFully(cmd.inputStream).toString(Charsets.UTF_8)
      val stderr = net.schmizz.sshj.common.IOUtils.readFully(cmd.errorStream).toString(Charsets.UTF_8)
      val exitCode = cmd.exitStatus ?: -1
      session.close()

      if (exitCode != 0) {
        throw RuntimeException("Installer failed (exit $exitCode): ${stderr.take(500)}")
      }

      // Update server to use daemon port and connect
      val updated = server.copy(port = 2222, status = "online")
      serverDao.upsert(updated)

      // Try connecting to the daemon
      try {
        sshManager.connect(updated)

        // Get health info
        val health = sshManager.exec(updated.id, "__aiope_health__")
        if (health.exitCode == 0) {
          try {
            val json = JSONObject(health.stdout)
            serverDao.updateHealth(
              updated.id,
              "${json.optString("os")} ${json.optString("arch")} - ${json.optString("hostname")}",
              json.optString("version", null),
            )
          } catch (_: Exception) {}
        }
      } catch (_: Exception) {
        // Daemon might need a moment to start
        serverDao.updateStatus(updated.id, "online")
      }

      installer.delete()
    } finally {
      bootstrapClient.disconnect()
    }
  }
}
