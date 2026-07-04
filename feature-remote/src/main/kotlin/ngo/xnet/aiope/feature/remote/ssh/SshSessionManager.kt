package ngo.xnet.aiope.feature.remote.ssh

import ngo.xnet.aiope.feature.remote.db.RemoteServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.xfer.FileSystemFile
import java.security.Security // kept for potential future use
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
        // Android ships a stripped BouncyCastle that lacks X25519/Ed25519.
        // Remove it and insert the full BC from our dependency.
        java.security.Security.removeProvider("BC")
        val bcProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        java.security.Security.insertProviderAt(bcProvider, 1)
        net.schmizz.sshj.common.SecurityUtils.setSecurityProvider("BC")
      } catch (_: Exception) {}
    }
  }

  private val sessions = ConcurrentHashMap<String, SSHClient>()
  private val knownHosts = ConcurrentHashMap<String, String>() // "host:port" -> fingerprint

  private fun normalizeKey(raw: String): String {
    var key = raw.trim()
    if (!key.contains("\n")) key = key.replace("\\n", "\n")
    if (!key.endsWith("\n")) key += "\n"
    return key
  }

  private fun loadKey(client: SSHClient, privateKey: String): net.schmizz.sshj.userauth.keyprovider.KeyProvider {
    val keyContent = normalizeKey(privateKey)
    // Always write to temp file — SSHJ's OpenSSHKeyFile works best from file
    val tmp = java.io.File.createTempFile("sshkey", null)
    try {
      tmp.writeText(keyContent)
      // Use client.loadKeys which auto-detects format including OpenSSH
      // The key detection needs the DefaultConfig's key file factories
      val keys = client.loadKeys(tmp.absolutePath, null as net.schmizz.sshj.userauth.password.PasswordFinder?)
      android.util.Log.e("AIOPE_SSH", "Key loaded via loadKeys: ${keys.private?.algorithm}")
      return keys
    } catch (e: Exception) {
      android.util.Log.e("AIOPE_SSH", "loadKeys failed: ${e.message}, trying OpenSSHKeyFile directly")
      // Direct OpenSSHKeyFile with file path
      try {
        val keyFile = net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile()
        keyFile.init(java.io.File(tmp.absolutePath))
        android.util.Log.e("AIOPE_SSH", "OpenSSHKeyFile loaded: ${keyFile.private?.algorithm}")
        return keyFile
      } catch (e2: Exception) {
        android.util.Log.e("AIOPE_SSH", "OpenSSHKeyFile also failed: ${e2.message}")
        throw IllegalStateException("Failed to load SSH key: ${e2.message ?: e.message}")
      }
    } finally {
      tmp.delete()
    }
  }

  /** TOFU verifier: trusts first-seen host key, rejects changes */
  private fun addTofuVerifier(client: SSHClient, host: String, port: Int) {
    val hostKey = "$host:$port"
    client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
      override fun verify(hostname: String?, p: Int, key: java.security.PublicKey?): Boolean {
        val fp = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
        val stored = knownHosts[hostKey]
        return if (stored == null) {
          knownHosts[hostKey] = fp
          true
        } else {
          stored == fp
        }
      }
      override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
    })
  }

  suspend fun connect(server: RemoteServerEntity): String = withContext(Dispatchers.IO) {
    sessions[server.id]?.let { if (it.isConnected) return@withContext server.id }
    val client = SSHClient()
    addTofuVerifier(client, server.host, server.port)
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
    addTofuVerifier(client, host, port)
    try {
      client.connect(host, port)
    } catch (e: Exception) {
      throw IllegalStateException("Connect failed to $host:$port — ${e.message ?: e.javaClass.simpleName}")
    }
    try {
      val keyProvider = loadKey(client, privateKey)
      android.util.Log.e("AIOPE_SSH", "Key loaded: private=${keyProvider.private?.algorithm} public=${keyProvider.public?.algorithm}")
      client.authPublickey(user, keyProvider)
    } catch (e: Exception) {
      android.util.Log.e("AIOPE_SSH", "Auth failed", e)
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
    val client = sessions.remove(serverId)
    if (client != null) {
      Thread { try { client.disconnect() } catch (_: Exception) {} }.start()
    }
  }

  fun disconnectAll() {
    sessions.keys.toList().forEach { disconnect(it) }
  }

  fun isConnected(serverId: String): Boolean = sessions[serverId]?.isConnected == true
}
