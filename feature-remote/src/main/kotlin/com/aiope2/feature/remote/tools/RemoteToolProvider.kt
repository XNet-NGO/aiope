package com.aiope2.feature.remote.tools

import com.aiope2.core.model.RemoteToolBridge
import com.aiope2.feature.remote.db.RemoteServerDao
import com.aiope2.feature.remote.ssh.SshSessionManager
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteToolProvider @Inject constructor(
  private val sshManager: SshSessionManager,
  private val serverDao: RemoteServerDao,
) : RemoteToolBridge {

  override fun buildToolDefs(): List<RemoteToolBridge.ToolDef> = listOf(
    RemoteToolBridge.ToolDef(
      name = "ssh_start",
      description = "Open persistent SSH session to a remote server. Returns session status.",
      parameters = """{"type":"object","properties":{"server":{"type":"string","description":"Server name or ID"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "ssh_exec",
      description = "Execute a shell command on an active remote SSH session. Returns stdout, stderr, and exit code.",
      parameters = """{"type":"object","properties":{"server":{"type":"string","description":"Server name or ID"},"command":{"type":"string","description":"Shell command to execute"},"timeout":{"type":"integer","description":"Timeout in seconds (default 30)"}},"required":["server","command"]}""",
      parallel = true,
    ),
    RemoteToolBridge.ToolDef(
      name = "ssh_exit",
      description = "Close an active SSH session and clean up remote processes.",
      parameters = """{"type":"object","properties":{"server":{"type":"string","description":"Server name or ID"}},"required":["server"]}""",
    ),
  )

  override suspend fun execute(name: String, args: Map<String, Any?>): String = try {
    when (name) {
      "ssh_start" -> sshStart(args)
      "ssh_exec" -> sshExec(args)
      "ssh_exit" -> sshExit(args)
      else -> """{"error":"Unknown remote tool: $name"}"""
    }
  } catch (e: Exception) {
    JSONObject().put("error", e.message ?: "Unknown error").toString()
  }

  private suspend fun sshStart(args: Map<String, Any?>): String {
    val serverName = args["server"]?.toString()
      ?: return """{"error":"server parameter required"}"""
    val server = serverDao.getByName(serverName)
      ?: serverDao.getById(serverName)
      ?: return """{"error":"Unknown server: $serverName"}"""
    sshManager.connect(server)
    serverDao.updateStatus(server.id, "online")
    return """{"status":"connected","server":"${server.name}","host":"${server.host}:${server.port}"}"""
  }

  private suspend fun sshExec(args: Map<String, Any?>): String {
    val serverName = args["server"]?.toString()
      ?: return """{"error":"server parameter required"}"""
    val command = args["command"]?.toString()
      ?: return """{"error":"command parameter required"}"""
    val timeout = (args["timeout"] as? Number)?.toInt() ?: 30
    val server = serverDao.getByName(serverName)
      ?: serverDao.getById(serverName)
      ?: return """{"error":"Unknown server: $serverName"}"""
    if (!sshManager.isConnected(server.id)) {
      return """{"error":"No active session for $serverName. Use ssh_start first."}"""
    }
    val result = sshManager.exec(server.id, command, timeout)
    serverDao.updateStatus(server.id, "online")
    return JSONObject()
      .put("stdout", result.stdout)
      .put("stderr", result.stderr)
      .put("exit_code", result.exitCode)
      .toString()
  }

  private suspend fun sshExit(args: Map<String, Any?>): String {
    val serverName = args["server"]?.toString()
      ?: return """{"error":"server parameter required"}"""
    val server = serverDao.getByName(serverName)
      ?: serverDao.getById(serverName)
      ?: return """{"error":"Unknown server: $serverName"}"""
    sshManager.disconnect(server.id)
    serverDao.updateStatus(server.id, "offline")
    return """{"status":"disconnected","server":"${server.name}"}"""
  }

  override suspend fun buildSystemContext(): String = buildString {
    val servers = serverDao.getAllOnce()
    if (servers.isEmpty()) return@buildString
    append("\n## Remote Servers\n")
    append("You can connect to and control remote Linux servers using ssh_start, ssh_exec, ssh_exit.\n\n")
    append("Available servers:\n")
    servers.forEach { s ->
      val connected = sshManager.isConnected(s.id)
      val status = if (connected) "CONNECTED" else s.status
      append("- ${s.name} (${s.host}:${s.port}) [$status]")
      s.osInfo?.let { append(" $it") }
      append("\n")
    }
  }

  override suspend fun disconnectAll() {
    serverDao.getAllOnce().forEach { sshManager.disconnect(it.id) }
  }

  override fun isConnected(serverId: String): Boolean = sshManager.isConnected(serverId)
}
