package com.aiope2.feature.chat.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.aiope2.core.network.ProviderProfile
import com.aiope2.feature.chat.db.AgentEntity
import com.aiope2.feature.chat.db.ChatDatabase
import com.aiope2.feature.chat.db.ScheduledTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that checks for due scheduled tasks and runs them.
 * Enqueued as a 15-minute periodic worker (Android minimum).
 */
class AgentSchedulerWorker(
  private val appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      val db = androidx.room.Room.databaseBuilder(
        appContext, ChatDatabase::class.java, "aiope2-chat.db"
      ).addMigrations(
        com.aiope2.feature.chat.di.MIGRATION_1_2,
        com.aiope2.feature.chat.di.MIGRATION_2_3,
        com.aiope2.feature.chat.di.MIGRATION_3_4,
        com.aiope2.feature.chat.di.MIGRATION_4_5,
        com.aiope2.feature.chat.di.MIGRATION_5_6,
        com.aiope2.feature.chat.di.MIGRATION_6_7,
      ).build()
      val dao = db.chatDao()
      val now = System.currentTimeMillis()
      val dueTasks = dao.getDueScheduledTasks(now)

      if (dueTasks.isEmpty()) return@withContext Result.success()

      // Load active provider for API calls
      val providerEntity = dao.getActiveProvider()
      val provider = providerEntity?.let { ProviderProfile.fromJson(JSONObject(it.json)) }

      for (task in dueTasks) {
        if (!shouldRunNow(task)) continue

        // Insert task entry so it appears in Monitor as running
        val taskId = java.util.UUID.randomUUID().toString().take(8)
        dao.insertAgentTask(
          com.aiope2.feature.chat.db.AgentTaskEntity(
            id = taskId,
            agentId = task.agentId,
            agentName = task.agentName,
            prompt = task.prompt,
            status = "running",
            scheduledTaskId = task.id,
          )
        )

        // Update schedule timing
        val nextRun = if (task.oneShot) null else computeNextRun(task)
        dao.updateScheduledTaskRun(task.id, lastRun = now, nextRun = nextRun)

        // One-shot: disable after running
        if (task.oneShot) {
          dao.insertScheduledTask(task.copy(enabled = false, lastRun = now, nextRun = null))
        }

        // Actually run the agent via gateway
        val result = if (provider != null) {
          runAgent(dao, provider, task)
        } else {
          "Error: No active provider configured"
        }

        // Store result and mark finished
        val status = if (result.startsWith("Error:")) "failed" else "finished"
        dao.updateAgentTask(taskId, status, result, System.currentTimeMillis())

        // Post notification with result preview
        showNotification(
          title = "Agent: ${task.agentName} — $status",
          body = result.take(100),
        )
      }

      db.close()
      Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }

  private suspend fun runAgent(
    dao: com.aiope2.feature.chat.db.ChatDao,
    provider: ProviderProfile,
    task: ScheduledTaskEntity,
  ): String {
    return try {
      // Resolve agent config
      val agent: AgentEntity? = dao.getAgentByName(task.agentName)
      val basePrompt = agent?.prompt ?: "You are a scheduled task agent. Complete the assigned task using your tools."
      val systemPrompt = basePrompt + "\n\n## Environment\n- Date/Time: " + java.time.LocalDateTime.now().toString() + "\n- Platform: Android (AIOPE scheduled task)\n- Execution: Background (WorkManager)\n\n## Tool Execution\nYou MUST use tools to complete your task.\n- search_web: search the web\n- fetch_url: fetch URL content\n- read_file/write_file/list_directory: filesystem\n- run_sh: shell commands\n- send_sms: send SMS\n- send_notification: show notification\n- set_alarm: set alarm\n- ssh_exec: remote command\n- memory_store/memory_recall: persistent memory\n\nDO NOT describe what you would do — actually DO it with tools.\nIf a tool fails, try an alternative. When finished, summarize what was accomplished."
      val modelId = agent?.model?.ifEmpty { null } ?: provider.selectedModelId
      val temperature = agent?.temperature ?: 0.7f

      // Build messages
      val messages = listOf("system" to systemPrompt, "user" to task.prompt)

      // Resolve tools from timer config
      val timerTools = task.tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
      val toolDefs = if (timerTools.isNotEmpty()) buildWorkerToolDefs(timerTools) else emptyList()

      // Create orchestrator with tools
      val orchestrator = StreamingOrchestrator(
        baseUrl = provider.effectiveApiBase(),
        apiKey = provider.apiKey,
        model = modelId,
        tools = toolDefs,
        onToolCall = { name, args -> executeWorkerTool(name, args) },
        temperature = temperature,
      )

      // Stream and collect
      val sb = StringBuilder()
      orchestrator.stream(messages).collect { chunk ->
        if (chunk.content.isNotEmpty()) sb.append(chunk.content)
      }

      sb.toString().ifEmpty { "(no output)" }
    } catch (e: Exception) {
      "Error: ${e.message ?: "unknown"}"
    }
  }

  /** Tool definitions available in background worker context */
  private fun buildWorkerToolDefs(allowed: Set<String>): List<StreamingOrchestrator.ToolDef> {
    val all = mapOf(
      "search_web" to ("Search the web" to """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
      "fetch_url" to ("Fetch URL content" to """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""),
      "read_file" to ("Read file contents" to """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
      "list_directory" to ("List directory" to """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
      "write_file" to ("Write file" to """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""),
      "run_sh" to ("Execute shell command" to """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""),
      "send_sms" to ("Send SMS message" to """{"type":"object","properties":{"to":{"type":"string","description":"Phone number"},"message":{"type":"string"}},"required":["to","message"]}"""),
      "send_notification" to ("Show a notification to the user" to """{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title","body"]}"""),
      "set_alarm" to ("Set an alarm" to """{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}"""),
      "ssh_exec" to ("Execute command on remote server via SSH" to """{"type":"object","properties":{"host":{"type":"string"},"command":{"type":"string"}},"required":["host","command"]}"""),
      "memory_store" to ("Store information in persistent memory" to """{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"}},"required":["key","value"]}"""),
      "memory_recall" to ("Recall information from persistent memory" to """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
    )
    return allowed.mapNotNull { name ->
      val (desc, params) = all[name] ?: return@mapNotNull null
      StreamingOrchestrator.ToolDef(name, desc, JSONObject(params))
    }
  }

  /** Execute tools in background worker context */
  private suspend fun executeWorkerTool(name: String, args: Map<String, Any?>): String {
    return try {
      when (name) {
        "run_sh" -> {
          val cmd = args["command"]?.toString() ?: return "Error: no command"
          val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
          val output = proc.inputStream.bufferedReader().readText()
          val err = proc.errorStream.bufferedReader().readText()
          proc.waitFor()
          (output + err).take(4000)
        }
        "read_file" -> {
          val path = args["path"]?.toString() ?: return "Error: no path"
          java.io.File(path).readText().take(8000)
        }
        "write_file" -> {
          val path = args["path"]?.toString() ?: return "Error: no path"
          val content = args["content"]?.toString() ?: return "Error: no content"
          java.io.File(path).apply { parentFile?.mkdirs() }.writeText(content)
          "Written to $path"
        }
        "list_directory" -> {
          val path = args["path"]?.toString() ?: return "Error: no path"
          java.io.File(path).listFiles()?.joinToString("\n") { (if (it.isDirectory) "d " else "f ") + it.name } ?: "Empty or not found"
        }
        "search_web" -> {
          val query = args["query"]?.toString() ?: return "Error: no query"
          val url = "https://search.xnet.ngo/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json"
          val response = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
          response.body?.string()?.take(4000) ?: "No results"
        }
        "fetch_url" -> {
          val url = args["url"]?.toString() ?: return "Error: no url"
          val response = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
          response.body?.string()?.take(8000) ?: "Empty response"
        }
        "send_sms" -> {
          val to = args["to"]?.toString() ?: return "Error: no recipient"
          val message = args["message"]?.toString() ?: return "Error: no message"
          android.telephony.SmsManager.getDefault().sendTextMessage(to, null, message, null, null)
          "SMS sent to $to"
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
          // Use shell ssh client
          val proc = Runtime.getRuntime().exec(arrayOf("ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10", host, cmd))
          val output = proc.inputStream.bufferedReader().readText()
          val err = proc.errorStream.bufferedReader().readText()
          proc.waitFor()
          (output + err).take(4000)
        }
        "memory_store" -> {
          val key = args["key"]?.toString() ?: return "Error: no key"
          val value = args["value"]?.toString() ?: return "Error: no value"
          val db = androidx.room.Room.databaseBuilder(appContext, ChatDatabase::class.java, "aiope2-chat.db")
            .addMigrations(com.aiope2.feature.chat.di.MIGRATION_1_2, com.aiope2.feature.chat.di.MIGRATION_2_3, com.aiope2.feature.chat.di.MIGRATION_3_4, com.aiope2.feature.chat.di.MIGRATION_4_5, com.aiope2.feature.chat.di.MIGRATION_5_6, com.aiope2.feature.chat.di.MIGRATION_6_7)
            .build()
          db.chatDao().upsertMemory(com.aiope2.feature.chat.db.MemoryEntity(key = key, content = value))
          db.close()
          "Stored: $key"
        }
        "memory_recall" -> {
          val query = args["query"]?.toString() ?: return "Error: no query"
          val db = androidx.room.Room.databaseBuilder(appContext, ChatDatabase::class.java, "aiope2-chat.db")
            .addMigrations(com.aiope2.feature.chat.di.MIGRATION_1_2, com.aiope2.feature.chat.di.MIGRATION_2_3, com.aiope2.feature.chat.di.MIGRATION_3_4, com.aiope2.feature.chat.di.MIGRATION_4_5, com.aiope2.feature.chat.di.MIGRATION_5_6, com.aiope2.feature.chat.di.MIGRATION_6_7)
            .build()
          val memories = db.chatDao().getAllMemories()
          db.close()
          val matches = memories.filter { it.key.contains(query, true) || it.content.contains(query, true) }
          if (matches.isEmpty()) "No memories matching '$query'"
          else matches.joinToString("\n") { "${it.key}: ${it.content.take(200)}" }
        }
        else -> "Tool '$name' not available in background mode"
      }
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  private fun shouldRunNow(task: ScheduledTaskEntity): Boolean {
    val cal = Calendar.getInstance()
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK).toString()

    // Check hour (if not every-hour)
    if (task.cronHour != -1 && hour != task.cronHour) return false

    // Check day of week
    if (task.cronDaysOfWeek.isNotEmpty()) {
      val allowedDays = task.cronDaysOfWeek.split(",").map { it.trim() }
      if (dayOfWeek !in allowedDays) return false
    }

    return true
  }

  private fun computeNextRun(task: ScheduledTaskEntity): Long {
    val cal = Calendar.getInstance()
    return when {
      task.cronHour == -1 -> {
        // Hourly: next run in 1 hour
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.timeInMillis
      }
      task.cronDaysOfWeek.isEmpty() -> {
        // Daily: next run tomorrow at cronHour
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, task.cronHour)
        cal.set(Calendar.MINUTE, task.cronMinute)
        cal.timeInMillis
      }
      else -> {
        // Weekly: next matching day
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val allowedDays = task.cronDaysOfWeek.split(",").map { it.trim().toIntOrNull() ?: 0 }
        repeat(7) {
          if (cal.get(Calendar.DAY_OF_WEEK) in allowedDays) {
            cal.set(Calendar.HOUR_OF_DAY, task.cronHour)
            cal.set(Calendar.MINUTE, task.cronMinute)
            return cal.timeInMillis
          }
          cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        cal.timeInMillis
      }
    }
  }

  private fun showNotification(title: String, body: String) {
    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(
        NotificationChannel("agent_timers", "Agent Timers", NotificationManager.IMPORTANCE_DEFAULT)
      )
    }
    val notification = NotificationCompat.Builder(appContext, "agent_timers")
      .setSmallIcon(android.R.drawable.ic_popup_sync)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .build()
    nm.notify(System.currentTimeMillis().toInt(), notification)
  }

  companion object {
    private const val WORK_NAME = "agent_scheduler"

    /** Enqueue the periodic scheduler (call once on app start) */
    fun enqueue(context: Context) {
      val request = PeriodicWorkRequestBuilder<AgentSchedulerWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
          Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        )
        .build()

      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }

    /** Cancel the scheduler */
    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
  }
}
