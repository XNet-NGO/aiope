package ngo.xnet.aiope.feature.remote.tools

import ngo.xnet.aiope.core.model.RemoteToolBridge
import ngo.xnet.aiope.feature.remote.db.RemoteServerDao
import ngo.xnet.aiope.feature.remote.db.RemoteServerEntity
import ngo.xnet.aiope.feature.remote.ssh.SshSessionManager
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
    // --- Remote browser control (drives a real Firefox/Chrome on the server) ---
    RemoteToolBridge.ToolDef(
      name = "remote_browser_start",
      description = "Start a browser on a remote server for automation. engine 'firefox' or 'chrome' — the two engines are 1:1 (identical tools and flags). By DEFAULT the browser is HEADED (visible, attaches to the server's active graphical session, auto-falls-back to HEADLESS when no display exists) and SHARES the user's real logged-in session — it drives a persistent AIOPE profile SEEDED from the user's real profile so cookies/auth/tokens carry over (the real profile is never driven or modified). Pass share_session=false for a clean throwaway profile (no auth), or mode='headless' to force headless. refresh=true pulls in fresh auth from the user's real profile (auth-only, keeps the agent's accumulated session data); reseed=true fully resets the AIOPE profile from the user's real profile (recovery; closes any running browser on the source first). Relay the user's explicit mode/session choice when they state one. Requires an active ssh session.",
      parameters = """{"type":"object","properties":{"server":{"type":"string","description":"Server name or ID"},"engine":{"type":"string","enum":["firefox","chrome"],"description":"Browser engine (default firefox)"},"share_session":{"type":"boolean","description":"Share the user's logged-in session via the seeded AIOPE profile. DEFAULT true — pass false only for a clean throwaway profile with no auth."},"refresh":{"type":"boolean","description":"Both engines: auth-only refresh from the user's real profile (keeps agent session data; implies share_session)"},"reseed":{"type":"boolean","description":"Both engines: full reset of the AIOPE profile from the user's real profile (recovery; implies share_session)"},"mode":{"type":"string","enum":["headed","headless"],"description":"Force display mode. Omit for default (headed with auto-fallback to headless). Relay the user's explicit choice when they state one."}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_navigate",
      description = "Navigate the remote browser to a URL.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"},"url":{"type":"string"}},"required":["server","url"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_content",
      description = "Get the visible text content of the current remote page (paginated).",
      parameters = """{"type":"object","properties":{"server":{"type":"string"},"offset":{"type":"integer"},"limit":{"type":"integer"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_elements",
      description = "List interactive elements (links, buttons, inputs) with selectors on the current remote page.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_click",
      description = "Click an element by CSS selector on the remote page. May return permission_required if the action would submit to a non-allowlisted host; re-issue with confirm=true to proceed.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"},"selector":{"type":"string"},"form_action_host":{"type":"string","description":"Host the form submits to, if known"},"confirm":{"type":"boolean"}},"required":["server","selector"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_fill",
      description = "Fill an input by CSS selector on the remote page. May return permission_required for non-allowlisted submit targets; re-issue with confirm=true to proceed.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"},"selector":{"type":"string"},"value":{"type":"string"},"form_action_host":{"type":"string"},"confirm":{"type":"boolean"}},"required":["server","selector","value"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_status",
      description = "Get the current URL and title of the remote browser.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_eval",
      description = "Evaluate a JavaScript expression in the remote page, out of page context (CSP-immune). Returns the JSON result value.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"},"script":{"type":"string"}},"required":["server","script"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_screenshot",
      description = "Capture a JPEG screenshot of the remote page. Returns base64-encoded image data.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_back",
      description = "Navigate the remote browser back one history entry.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_scroll",
      description = "Scroll the remote page up or down by a pixel amount.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"},"dir":{"type":"string","enum":["up","down"]},"px":{"type":"integer","description":"pixels to scroll (default 500)"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_detect",
      description = "List browsers (Firefox/Chrome), versions, and display/headless capability available on a remote server. Use to decide which engine to start.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"}},"required":["server"]}""",
    ),
    RemoteToolBridge.ToolDef(
      name = "remote_browser_stop",
      description = "Stop the remote browser automation session.",
      parameters = """{"type":"object","properties":{"server":{"type":"string"}},"required":["server"]}""",
    ),
  )

  override suspend fun execute(name: String, args: Map<String, Any?>): String = try {
    when (name) {
      "ssh_start" -> sshStart(args)
      "ssh_exec" -> sshExec(args)
      "ssh_exit" -> sshExit(args)
      "remote_browser_start" -> browserStart(args)
      "remote_browser_navigate" -> browserVerb(args, "navigate", mapOf("url" to args["url"]))
      "remote_browser_content" -> browserVerb(args, "content", mapOf("offset" to args["offset"], "limit" to args["limit"]))
      "remote_browser_elements" -> browserVerb(args, "elements", emptyMap())
      "remote_browser_click" -> browserVerb(args, "click", mapOf("selector" to args["selector"], "form_action_host" to args["form_action_host"], "confirm" to args["confirm"]))
      "remote_browser_fill" -> browserVerb(args, "fill", mapOf("selector" to args["selector"], "value" to args["value"], "form_action_host" to args["form_action_host"], "confirm" to args["confirm"]))
      "remote_browser_status" -> browserVerb(args, "status", emptyMap())
      "remote_browser_eval" -> browserVerb(args, "eval", mapOf("script" to args["script"]))
      "remote_browser_screenshot" -> browserVerb(args, "screenshot", emptyMap())
      "remote_browser_back" -> browserVerb(args, "back", emptyMap())
      "remote_browser_scroll" -> browserVerb(args, "scroll", mapOf("dir" to args["dir"], "px" to args["px"]))
      "remote_browser_detect" -> browserVerb(args, "detect", emptyMap())
      "remote_browser_stop" -> browserVerb(args, "stop", emptyMap())
      else -> """{"error":"Unknown remote tool: $name"}"""
    }
  } catch (e: Exception) {
    JSONObject().put("error", e.message ?: "Unknown error").toString()
  }

  private suspend fun resolveServer(args: Map<String, Any?>): RemoteServerEntity? {
    val serverName = args["server"]?.toString() ?: return null
    return serverDao.getByName(serverName) ?: serverDao.getById(serverName)
  }

  // browserStart launches the chosen engine on the server, then refreshes the
  // stored browser registry from the daemon's detect output.
  private suspend fun browserStart(args: Map<String, Any?>): String {
    val server = resolveServer(args)
      ?: return """{"error":"Unknown server: ${args["server"]}"}"""
    if (!sshManager.isConnected(server.id)) {
      return """{"error":"No active session for ${server.name}. Use ssh_start first."}"""
    }
    val engine = args["engine"]?.toString()?.takeIf { it.isNotBlank() } ?: "firefox"
    // Refresh the registry snapshot (best-effort).
    runCatching {
      val det = sshManager.exec(server.id, "__aiope_browser__detect")
      if (det.exitCode == 0 && det.stdout.isNotBlank()) {
        serverDao.updateBrowsers(server.id, det.stdout)
      }
    }
    val payload = JSONObject().put("engine", engine)
    (args["share_session"] as? Boolean)?.let { payload.put("share_session", it) }
    (args["refresh"] as? Boolean)?.let { payload.put("refresh", it) }
    (args["reseed"] as? Boolean)?.let { payload.put("reseed", it) }
    (args["mode"] as? String)?.takeIf { it.isNotBlank() }?.let { payload.put("mode", it) }
    val res = sshManager.exec(server.id, "__aiope_browser__start $payload")
    return res.stdout.ifBlank { """{"error":"empty response","stderr":"${res.stderr.take(300)}"}""" }
  }

  // browserVerb sends a __aiope_browser__<verb> command with a JSON payload built
  // from the provided (non-null) params, and returns the daemon's JSON response.
  private suspend fun browserVerb(args: Map<String, Any?>, verb: String, params: Map<String, Any?>): String {
    val server = resolveServer(args)
      ?: return """{"error":"Unknown server: ${args["server"]}"}"""
    if (!sshManager.isConnected(server.id)) {
      return """{"error":"No active session for ${server.name}. Use ssh_start first."}"""
    }
    val payload = JSONObject()
    for ((k, v) in params) if (v != null) payload.put(k, v)
    val cmd = if (payload.length() > 0) "__aiope_browser__$verb $payload" else "__aiope_browser__$verb"
    val res = sshManager.exec(server.id, cmd)
    return res.stdout.ifBlank { """{"error":"empty response","stderr":"${res.stderr.take(300)}"}""" }
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
    append("You can connect to and control remote Linux servers using ssh_start, ssh_exec, ssh_exit.\n")
    append("Servers with a browser can be driven with remote_browser_* tools (start, navigate, content, elements, click, fill, status, stop). ")
    append("A desktop server's browser shares the user's logged-in session (cookies/auth); a headless server runs headless. ")
    append("Pick the server and engine that match the user's request.\n\n")
    append("Available servers:\n")
    servers.forEach { s ->
      val connected = sshManager.isConnected(s.id)
      val status = if (connected) "CONNECTED" else s.status
      append("- ${s.name} (${s.host}:${s.port}) [$status]")
      s.osInfo?.let { append(" $it") }
      append("\n")
      appendBrowsers(s.browsers)
    }
  }

  // appendBrowsers renders a server's stored browser registry (JSON from
  // __aiope_browser__detect) as human/agent-readable lines so plain-speech
  // prompts can resolve to the right server + engine.
  private fun StringBuilder.appendBrowsers(browsersJson: String?) {
    if (browsersJson.isNullOrBlank()) return
    runCatching {
      val root = JSONObject(browsersJson)
      val obj = root.optJSONObject("data") ?: root
      val mode = obj.optString("suggest_mode", "")
      val display = obj.optBoolean("display_found", false)
      val arr = obj.optJSONArray("browsers") ?: return
      if (arr.length() == 0) return
      val engines = (0 until arr.length()).joinToString(", ") { i ->
        val b = arr.getJSONObject(i)
        val eng = b.optString("engine")
        val ver = b.optString("version").substringAfterLast(' ').ifBlank { "" }
        val snap = if (b.optBoolean("is_snap", false)) " (snap)" else ""
        if (ver.isNotBlank()) "$eng $ver$snap" else "$eng$snap"
      }
      val where = if (display) "desktop/shared-session" else "headless"
      append("    browsers: $engines — $where${if (mode.isNotBlank()) " (mode: $mode)" else ""}\n")
    }
  }

  override suspend fun disconnectAll() {
    serverDao.getAllOnce().forEach { sshManager.disconnect(it.id) }
  }

  override fun isConnected(serverId: String): Boolean = sshManager.isConnected(serverId)
}
