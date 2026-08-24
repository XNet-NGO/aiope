package ngo.xnet.aiope.feature.chat.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.feature.chat.db.AgentEntity
import ngo.xnet.aiope.feature.chat.db.AgentTaskEntity
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.db.ChatDatabase
import ngo.xnet.aiope.feature.chat.db.MemoryEntity
import ngo.xnet.aiope.feature.chat.db.ScheduledTaskEntity
import ngo.xnet.aiope.feature.chat.db.TaskRunEntity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
// Tools actually implemented by the background worker. Keep in sync with executeWorkerTool().
private val workerToolCatalog: Map<String, String> = mapOf(
  "search_web" to "Search the web for current information. Args: query: String",
  "fetch_url" to "Fetch a URL and return extracted text and images. Args: url: String",
  "http_request" to "Call any HTTP API. Args: url: String, method: String (optional), headers: Object (optional), body: String (optional), timeout_seconds: Number (optional)",
  "run_sh" to "Run a shell command on this device. Args: command: String, timeout: Number (optional)",
  "ssh_exec" to "Run a command on a remote server over SSH. Args: host: String, command: String, timeout: Number (optional)",
  "send_notification" to "Show a notification on this device. Args: title: String, body: String",
  "set_alarm" to "Set an alarm on this device. Args: hour: Number, minute: Number, label: String (optional)",
  "memory_store" to "Store a fact in persistent memory. Args: key: String, value: String",
  "memory_recall" to "Recall stored memories. Args: query: String (optional)",
  "read_file" to "Read a text file from this device. Args: path: String",
  "write_file" to "Write a text file on this device. Args: path: String, content: String",
  "list_directory" to "List a directory on this device. Args: path: String",
  "datetime_now" to "Current local date, time, timezone and day of week. Args: none",
)

/**
 * Executes one scheduled agent task run.
 *
 * The alarm receiver hands us the task id; we run the agent with context
 * carry-over (last [MAX_CONTEXT_RUNS] outputs), persist the run, advance the
 * task's progress, and re-arm the next alarm — or disable the task when the
 * max-runs cap is reached.
 */
class AgentRunWorker(
  private val appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  companion object {
    const val KEY_TASK_ID = "ngo.xnet.aiope.extra.RUN_TASK_ID"
    private const val MAX_CONTEXT_RUNS = 5
    private const val OUTPUT_TRUNCATE = 4000
    private const val MAX_RETRIES = 3
  }

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
    val db = buildDb()
    try {
      val dao = db.chatDao()
      val task = dao.getScheduledTaskById(taskId) ?: return@withContext Result.success()
      if (!task.enabled) return@withContext Result.success()

      val now = System.currentTimeMillis()
      val runNumber = dao.countTaskRuns(taskId) + 1

      // Monitor entry
      val agentTaskId = UUID.randomUUID().toString().take(8)
      dao.insertAgentTask(
        AgentTaskEntity(
          id = agentTaskId,
          agentId = task.agentId,
          agentName = task.agentName,
          prompt = task.prompt,
          status = "running",
          scheduledTaskId = task.id,
        ),
      )

      // Resolve provider
      val providerEntity = dao.getActiveProvider()
      val provider = providerEntity?.let { ProviderProfile.fromJson(JSONObject(it.json)) }
      val result = if (provider != null) {
        runAgent(dao, provider, task, runNumber)
      } else {
        "Error: No active provider configured"
      }
      val status = if (result.startsWith("Error:")) "failed" else "finished"

      dao.updateAgentTask(agentTaskId, status, result, System.currentTimeMillis())

      // Persist run for context carry-over
      dao.insertTaskRun(
        TaskRunEntity(
          scheduledTaskId = task.id,
          runNumber = runNumber,
          timestamp = now,
          prompt = task.prompt,
          output = result.take(OUTPUT_TRUNCATE),
          status = status,
        ),
      )

      // Advance progress and re-arm / finish
      val completed = task.runsCompleted + 1
      val capReached = task.maxRuns > 0 && completed >= task.maxRuns
      val done = capReached || task.scheduleType == "once"
      val updated = task.copy(
        runsCompleted = completed,
        lastRun = now,
        status = if (status == "failed") {
          "failed"
        } else if (done) {
          "finished"
        } else {
          "scheduled"
        },
        enabled = !done,
        nextRun = if (done) null else AgentScheduler.computeNextRun(task, now),
      )
      dao.updateScheduledTaskProgress(updated.id, updated.runsCompleted, now, updated.nextRun, updated.enabled, updated.status)
      if (done) AgentScheduler.cancel(appContext, task.id) else AgentScheduler.schedule(appContext, updated)

      val title = if (done) {
        val cap = if (task.maxRuns > 0) "$completed/${task.maxRuns}" else "1/1"
        "Agent: ${task.agentName} — completed $cap runs"
      } else {
        "Agent: ${task.agentName} — $status"
      }
      showNotification(title, result.take(100))

      Result.success()
    } catch (e: Exception) {
      if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
    }
  }

  private suspend fun runAgent(
    dao: ChatDao,
    provider: ProviderProfile,
    task: ScheduledTaskEntity,
    runNumber: Int,
  ): String = try {
    val agent: AgentEntity? = dao.getAgentByName(task.agentName)
    val basePrompt = agent?.prompt ?: "You are a scheduled task agent. Complete the assigned task using your tools."
    // Resolve tools from timer config
    val timerTools = task.tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val toolHelp = if (timerTools.isEmpty()) {
      "- No tools enabled for this task. You can only reason and produce a text response.\n"
    } else {
      workerToolCatalog.filterKeys { it in timerTools }.entries.joinToString("\n") { (k, v) -> "- $k: ${v.substringBefore(". Args")}" } + "\n"
    }
    val systemPrompt = basePrompt + "\n\n## Environment\n- Date/Time: " +
      ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss z")) +
      "\n- Platform: Android (AIOPE scheduled task)\n- Execution: Background\n" +
      "\n## Recurring Task\nThis is a RECURRING task. Compare with previous runs (see Run Context) and REPORT WHAT CHANGED since the last run. If nothing changed, say so explicitly." +
      "\n\n## Tool Execution\nYou MUST use tools to complete your task.\n" +
      toolHelp +
      "\n\nDO NOT describe what you would do \u2014 actually DO it with tools.\nIf a tool fails, try an alternative. When finished, summarize what was accomplished."
    val modelId = agent?.model?.ifEmpty { null } ?: provider.selectedModelId
    val temperature = agent?.temperature ?: 0.7f

    // Context carry-over: last N runs
    val previous = dao.getLastTaskRuns(task.id, MAX_CONTEXT_RUNS).reversed()
    val contextSection = buildString {
      appendLine("## Run Context")
      appendLine("- This is run #$runNumber of ${if (task.maxRuns > 0) task.maxRuns.toString() else "unlimited"}.")
      if (previous.isNotEmpty()) {
        appendLine("- Previous run(s):")
        previous.forEach { r ->
          appendLine("  [run ${r.runNumber} @ ${AgentScheduler.formatTime(r.timestamp)} (${r.status})]: ${r.output.take(600)}")
        }
      } else {
        appendLine("- No previous runs yet (this is the first run).")
      }
    }
    val userPrompt = task.prompt + "\n\n" + contextSection

    val messages = listOf("system" to systemPrompt, "user" to userPrompt)

    // Resolve tools from timer config
    val toolDefs = if (timerTools.isNotEmpty()) buildWorkerToolDefs(timerTools) else emptyList()

    val orchestrator = StreamingOrchestrator(
      baseUrl = provider.effectiveApiBase(),
      apiKey = provider.apiKey,
      model = modelId,
      tools = toolDefs,
      onToolCall = { name, args -> executeWorkerTool(dao, name, args) },
      temperature = temperature,
    )

    val sb = StringBuilder()
    var streamError: String? = null
    orchestrator.stream(messages).collect { chunk ->
      if (chunk.content.isNotEmpty()) sb.append(chunk.content)
      if (chunk.error != null) streamError = chunk.error
    }

    when {
      sb.isNotEmpty() -> sb.toString()
      streamError != null -> "Error: $streamError"
      else -> "(no output)"
    }
  } catch (e: Exception) {
    "Error: ${e.message ?: "unknown"}"
  }

  private fun buildWorkerToolDefs(tools: Set<String>): List<StreamingOrchestrator.ToolDef> {
    return tools.mapNotNull { name ->
      val desc = workerToolCatalog[name] ?: return@mapNotNull null
      val props = JSONObject()
      val required = org.json.JSONArray()
      val argSpec = desc.substringAfter("Args: ", "")
      if (!argSpec.equals("none", true)) {
        argSpec.split(", ").filter { it.isNotBlank() }.forEach { p ->
          val key = p.substringBefore(":").trim()
          val spec = p.substringAfter(":").trim()
          val optional = spec.contains("optional")
          val type = when {
            spec.startsWith("Number") -> "number"
            spec.startsWith("Object") -> "object"
            spec.startsWith("String") -> "string"
            else -> "string"
          }
          props.put(key, JSONObject().put("type", type))
          if (!optional) required.put(key)
        }
      }
      val parameters = JSONObject()
        .put("type", "object")
        .put("properties", props)
        .put("required", required)
      StreamingOrchestrator.ToolDef(name, desc, parameters)
    }
  }

  private suspend fun executeWorkerTool(dao: ChatDao, name: String, args: Map<String, Any?>): String {
    return try {
      when (name) {
        "run_sh" -> {
          val cmd = args["command"]?.toString() ?: return "Error: no command"
          val timeout = ((args["timeout"] as? Number)?.toLong() ?: 30L).coerceIn(1L, 120L)
          val proc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
          runProcess(proc, timeout)
        }

        "search_web" -> {
          val query = args["query"]?.toString() ?: return "Error: no query"
          searxQuery(query)
        }

        "fetch_url" -> {
          val url = args["url"]?.toString() ?: return "Error: no url"
          fetchUrl(url)
        }

        "send_notification" -> {
          val title = args["title"]?.toString() ?: "Agent"
          val body = args["body"]?.toString() ?: ""
          showNotification(title, body)
          "Notification sent"
        }

        "set_alarm" -> {
          val hour = (args["hour"] as? Number)?.toInt() ?: return "Error: no hour"
          val minute = (args["minute"] as? Number)?.toInt() ?: 0
          val label = args["label"]?.toString() ?: "Agent Alarm"
          val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          appContext.startActivity(intent)
          "Alarm set for $hour:${minute.toString().padStart(2, '0')} — $label"
        }

        "ssh_exec" -> {
          val host = args["host"]?.toString() ?: return "Error: no host"
          val cmd = args["command"]?.toString() ?: return "Error: no command"
          val timeout = ((args["timeout"] as? Number)?.toLong() ?: 30L).coerceIn(1L, 120L)
          val proc = ProcessBuilder(
            "ssh",
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "ConnectTimeout=10",
            host,
            cmd,
          ).redirectErrorStream(true).start()
          runProcess(proc, timeout)
        }

        "memory_store" -> {
          val key = args["key"]?.toString() ?: return "Error: no key"
          val value = args["value"]?.toString() ?: return "Error: no value"
          dao.upsertMemory(MemoryEntity(key = key, content = value))
          "Stored: $key"
        }

        "memory_recall" -> {
          val query = args["query"]?.toString() ?: return "Error: no query"
          val memories = dao.getAllMemories()
          val matches = memories.filter { it.key.contains(query, true) || it.content.contains(query, true) }
          if (matches.isEmpty()) "No memories matching '$query'" else matches.joinToString("\n") { "${it.key}: ${it.content.take(200)}" }
        }

        "http_request" -> {
          val url = args["url"]?.toString() ?: return "Error: no url"
          val method = (args["method"]?.toString() ?: "GET").uppercase()
          if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) return "Error: unsupported method: $method"
          val timeoutSec = ((args["timeout_seconds"] as? Number)?.toLong() ?: 30L).coerceIn(1L, 120L)
          val headerMap = mutableMapOf<String, String>()
          when (val h = args["headers"]) {
            is JSONObject -> h.keys().forEach { k -> headerMap[k] = h.optString(k) }
            is Map<*, *> -> h.forEach { (k, v) -> headerMap[k.toString()] = v.toString() }
          }
          val bodyText = args["body"]?.toString() ?: ""
          val contentType = headerMap.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "text/plain"
          val builder = okhttp3.Request.Builder().url(url)
          if (method != "GET" && method != "HEAD") {
            val reqBody = okhttp3.RequestBody.create(contentType.toMediaTypeOrNull(), bodyText)
            when (method) {
              "POST" -> builder.post(reqBody)
              "PUT" -> builder.put(reqBody)
              "PATCH" -> builder.patch(reqBody)
              "DELETE" -> if (bodyText.isEmpty()) builder.delete() else builder.delete(reqBody)
            }
          }
          headerMap.forEach { (k, v) -> builder.header(k, v) }
          val client = httpClient.newBuilder().callTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS).build()
          client.newCall(builder.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            "HTTP ${r.code} ${r.message}\n${body.take(8000)}" + if (body.length > 8000) "\n...(truncated)" else ""
          }
        }

        "read_file" -> {
          val path = args["path"]?.toString() ?: return "Error: no path"
          val f = java.io.File(path)
          if (!f.isFile) return "Error: file not found: $path"
          val text = f.readText()
          if (text.length > 20000) text.take(20000) + "\n...(truncated, ${text.length} chars total)" else text
        }

        "write_file" -> {
          val path = args["path"]?.toString() ?: return "Error: no path"
          val content = args["content"]?.toString() ?: return "Error: no content"
          val f = java.io.File(path)
          f.parentFile?.mkdirs()
          f.writeText(content)
          "OK: wrote ${content.length} bytes to ${f.absolutePath}"
        }

        "list_directory" -> {
          val path = args["path"]?.toString() ?: return "Error: no path"
          val dir = java.io.File(path)
          if (!dir.isDirectory) return "Error: not a directory: $path"
          val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return "Error: cannot list $path"
          if (entries.isEmpty()) {
            "(empty directory)"
          } else {
            entries.take(200).joinToString("\n") { if (it.isDirectory) "${it.name}/" else "${it.name} (${it.length()} B)" }
          }
        }

        "datetime_now" -> {
          val now = java.time.ZonedDateTime.now()
          "Now: ${now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss"))}\nTimezone: ${now.zone.id} (${now.offset})\nDay of week: ${now.dayOfWeek}\nEpoch ms: ${System.currentTimeMillis()}"
        }

        else -> "Tool '$name' not available in background mode"
      }
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  private val httpClient: okhttp3.OkHttpClient by lazy { SafeOkHttp.builder().build() }

  private fun searxQuery(query: String): String {
    if (query.isBlank()) return "Error: query required"
    val u = "https://search.xnet.ngo/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json"
    val req = okhttp3.Request.Builder().url(u).header("User-Agent", "AIOPE/2.0 (Android)").build()
    return try {
      val resp = httpClient.newCall(req).execute()
      val body = resp.use { it.body?.string() ?: "" }
      if (body.isBlank()) return ddgFallback(query)
      val json = org.json.JSONObject(body)
      val results = json.optJSONArray("results") ?: return ddgFallback(query)
      val sb = StringBuilder()
      for (i in 0 until minOf(results.length(), 8)) {
        val r = results.optJSONObject(i) ?: continue
        sb.append("- ${r.optString("title")}\n  ${r.optString("url")}\n  ${r.optString("content")}\n")
      }
      sb.toString().ifBlank { ddgFallback(query) }
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  private fun ddgFallback(query: String): String {
    val u = "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
    val req = okhttp3.Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36").build()
    val html = try {
      httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
    } catch (_: Exception) {
      return "Error: search unavailable"
    }
    val pattern = Regex("""<a rel="nofollow" class="result__a" href="[^"]*uddg=([^&"]+)[^"]*">(.+?)</a>""")
    val snippetPattern = Regex("""<a class="result__snippet"[^>]*>(.+?)</a>""")
    val links = pattern.findAll(html).take(10).toList()
    val snippets = snippetPattern.findAll(html).take(10).toList()
    if (links.isEmpty()) return "No results found."
    val sb = StringBuilder()
    links.forEachIndexed { i, m ->
      val url = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
      val title = m.groupValues[2].replace(Regex("<[^>]+>"), "")
      val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "") ?: ""
      sb.append("- $title\n  $url\n  $snippet\n")
    }
    return sb.toString()
  }

  private fun fetchUrl(urlStr: String): String {
    return try {
      val fetchUrl = java.net.URL(urlStr)
      val req = okhttp3.Request.Builder().url(fetchUrl)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android) AIOPE/2.0").build()
      val resp = httpClient.newCall(req).execute()
      val ct = resp.header("Content-Type") ?: ""
      val body = resp.use { it.body?.string() ?: "" }
      if (!ct.contains("html")) return body.take(OUTPUT_TRUNCATE)
      val base = "${fetchUrl.protocol}://${fetchUrl.host}"
      val imgs = mutableListOf<String>()
      Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(body).forEach { m ->
        val src = m.groupValues[1].let {
          when {
            it.startsWith("http") -> it
            it.startsWith("/") -> "$base$it"
            else -> "$base/$it"
          }
        }
        if (src.matches(Regex(""".*\.(jpg|jpeg|png|gif|webp|svg)(\?.*)?$""", RegexOption.IGNORE_CASE)) || !src.contains(".js")) {
          imgs.add("![${m.groupValues.getOrElse(1) { "" }.take(80).ifEmpty { "image" }}]($src)")
        }
      }
      val text = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
      val result = (if (imgs.isNotEmpty()) imgs.distinct().take(20).joinToString("\n") + "\n\n" else "") + text
      result.take(OUTPUT_TRUNCATE)
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  /** Runs a process with a hard timeout; kills it if it exceeds [timeoutSec]. */
  private fun runProcess(proc: Process, timeoutSec: Long): String {
    val output = StringBuilder()
    val reader = Thread {
      try {
        proc.inputStream.bufferedReader().use { output.append(it.readText()) }
      } catch (_: Exception) {
        // stream closed by destroyForcibly()
      }
    }
    reader.start()
    val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      proc.waitFor()
      return "Error: command timed out after ${timeoutSec}s\n$output"
    }
    reader.join(2_000)
    return output.toString().take(OUTPUT_TRUNCATE)
  }

  private fun buildDb(): ChatDatabase = AgentDb.get(appContext)

  private fun showNotification(title: String, body: String) {
    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel("agent_tasks", "Agent Tasks", NotificationManager.IMPORTANCE_LOW))
    }
    val notification = NotificationCompat.Builder(appContext, "agent_tasks")
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .build()
    nm.notify(taskNotificationId(), notification)
  }

  private fun taskNotificationId(): Int = (inputData.getString(KEY_TASK_ID)?.hashCode() ?: 0) and 0xFFFF
}
