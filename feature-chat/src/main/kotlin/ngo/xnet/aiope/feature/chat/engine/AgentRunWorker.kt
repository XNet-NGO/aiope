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
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    val systemPrompt = basePrompt + "\n\n## Environment\n- Date/Time: " +
      ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss z")) +
      "\n- Platform: Android (AIOPE scheduled task)\n- Execution: Background\n" +
      "\n## Recurring Task\nThis is a RECURRING task. Compare with previous runs (see Run Context) and REPORT WHAT CHANGED since the last run. If nothing changed, say so explicitly." +
      "\n\n## Tool Execution\nYou MUST use tools to complete your task.\n- search_web: search the web\n- fetch_url: fetch URL content\n- read_file/write_file/list_directory: filesystem\n- run_sh: shell commands\n- send_sms: send SMS\n- send_notification: show notification\n- set_alarm: set alarm\n- ssh_exec: remote command\n- memory_store/memory_recall: persistent memory\n\nDO NOT describe what you would do — actually DO it with tools.\nIf a tool fails, try an alternative. When finished, summarize what was accomplished."
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
    val timerTools = task.tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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
    orchestrator.stream(messages).collect { chunk ->
      if (chunk.content.isNotEmpty()) sb.append(chunk.content)
    }

    sb.toString().ifEmpty { "(no output)" }
  } catch (e: Exception) {
    "Error: ${e.message ?: "unknown"}"
  }

  private fun buildWorkerToolDefs(tools: Set<String>): List<StreamingOrchestrator.ToolDef> {
    val all = mapOf(
      "search_web" to "Search the web. Args: query: String",
      "fetch_url" to "Fetch a URL. Args: url: String",
      "read_file" to "Read a file. Args: path: String",
      "write_file" to "Write a file. Args: path: String, content: String",
      "list_directory" to "List a directory. Args: path: String",
      "run_sh" to "Run a shell command. Args: command: String, timeout: Int",
      "send_sms" to "Send an SMS. Args: to: String, body: String",
      "send_notification" to "Show a notification. Args: title: String, body: String",
      "set_alarm" to "Set an alarm. Args: hour: Int, minute: Int, label: String",
      "ssh_exec" to "Run a remote command. Args: host: String, command: String, timeout: Int",
      "memory_store" to "Store a memory. Args: key: String, value: String",
      "memory_recall" to "Recall memories. Args: query: String",
    )
    return tools.mapNotNull { name ->
      val desc = all[name] ?: return@mapNotNull null
      val params = JSONObject()
      desc.substringAfter("Args: ").split(", ").forEach { p ->
        val key = p.substringBefore(":")
        val type = p.substringAfter(":").trim()
        params.put(key, type)
      }
      StreamingOrchestrator.ToolDef(name, desc, params)
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

        else -> "Tool '$name' not available in background mode"
      }
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
