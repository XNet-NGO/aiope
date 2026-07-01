package com.aiope2.feature.chat.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.aiope2.feature.chat.db.ChatDatabase
import com.aiope2.feature.chat.db.ScheduledTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
      ).build()
      val dao = db.chatDao()
      val now = System.currentTimeMillis()
      val dueTasks = dao.getDueScheduledTasks(now)

      if (dueTasks.isEmpty()) return@withContext Result.success()

      for (task in dueTasks) {
        if (!shouldRunNow(task)) continue

        // Insert task entry so it appears in Monitor
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

        // Post notification that timer fired
        showNotification(
          title = "Agent: ${task.agentName}",
          body = "Scheduled task started: ${task.prompt.take(60)}",
        )

        // Mark task as finished in Monitor
        dao.updateAgentTask(taskId, "finished", "Scheduled run completed", System.currentTimeMillis())
      }

      Result.success()
    } catch (e: Exception) {
      Result.retry()
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
