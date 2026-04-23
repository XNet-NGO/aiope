package com.aiope2.feature.remote.ssh

import com.aiope2.feature.remote.db.RemoteServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

@Singleton
class SshSessionManager @Inject constructor() {

  companion object {
    init {
      try {
        Security.removeProvider("BC")
        Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        net.schmizz.sshj.common.SecurityUtils.setSecurityProvider(null)
      } catch (_: Exception) {}
    }
  }

  private val sessions = ConcurrentHashMap<String, SSHClient>()

  private fun normalizeKey(raw: String): String {
    var key = raw.trim()
    if (!key.contains("\n")) key = key.replace("\\n", "\n")
    if (!key.endsWith("\n")) key += "\n"
    return key
  }

  private fun loadKey(client: SSHClient, privateKey: String) = client.loadKeys(normalizeKey(privateKey), null, null)

  suspend fun connect(server: RemoteServerEntity): String = withContext(Dispatchers.IO) {
    sessions[server.id]?.let { if (it.isConnected) return@withContext server.id }
    val client = SSHClient()
    client.addHostKeyVerifier(PromiscuousVerifier())
    client.connect(server.host, server.port)
    val privateKey = server.privateKey
    if (privateKey.isNullOrBlank()) {
      client.disconnect()
      throw IllegalStateException("No SSH private key configured for ${server.name}.")
    }
    try {
      client.authPublickey(server.user, loadKey(client, privateKey))
    } catch (e: Exception) {
      client.disconnect()
      throw IllegalStateException("SSH auth failed for ${server.name}: ${e.message}")
    }
    sessions[server.id] = client
    server.id
  }

  suspend fun connectWithKey(host: String, port: Int, user: String, privateKey: String): SSHClient = withContext(Dispatchers.IO) {
    val client = SSHClient()
    client.addHostKeyVerifier(PromiscuousVerifier())
    client.connect(host, port)
    try {
      client.authPublickey(user, loadKey(client, privateKey))
    } catch (e: Exception) {
      client.disconnect()
      throw IllegalStateException("SSH auth failed: ${e.message}")
    }
    client
  }

  suspend fun exec(serverId: String, command: String, timeout: Int = 30): ExecResult = withContext(Dispatchers.IO) {
    val client = sessions[serverId] ?: throw IllegalStateException("No active session: $serverId")
    val session = client.startSession()
    try {
      val cmd = session.exec(command)
      cmd.join(timeout.toLong(), TimeUnit.SECONDS)
      val stdout = IOUtils.readFully(cmd.inputStream).toString(Charsets.UTF_8)
      val stderr = IOUtils.readFully(cmd.errorStream).toString(Charsets.UTF_8)
      val exitCode = cmd.exitStatus ?: -1
      ExecResult(stdout.trimEnd(), stderr.trimEnd(), exitCode)
    } finally {
      session.close()
    }
  }

  suspend fun scpTo(serverId: String, localPath: String, remotePath: String) = withContext(Dispatchers.IO) {
    val client = sessions[serverId] ?: throw IllegalStateException("No active session: $serverId")
    client.newSCPFileTransfer().upload(FileSystemFile(localPath), remotePath)
  }

  fun disconnect(serverId: String) {
    sessions.remove(serverId)?.disconnect()
  }

  fun disconnectAll() {
    sessions.keys.toList().forEach { disconnect(it) }
  }

  fun isConnected(serverId: String): Boolean = sessions[serverId]?.isConnected == true
}
