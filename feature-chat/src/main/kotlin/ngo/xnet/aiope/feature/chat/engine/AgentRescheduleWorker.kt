package ngo.xnet.aiope.feature.chat.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-arms AlarmManager alarms for every enabled scheduled task.
 * Runs once on app start and after BOOT_COMPLETED (exact alarms do not survive reboot).
 */
class AgentRescheduleWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val db = AgentDb.get(applicationContext)
    try {
      for (task in db.chatDao().getEnabledScheduledTasks()) {
        val updated = AgentScheduler.schedule(applicationContext, task)
        if (updated.nextRun != task.nextRun) {
          db.chatDao().updateScheduledTaskRun(updated.id, updated.lastRun, updated.nextRun)
        }
      }
      Result.success()
    } catch (e: Exception) {
      if (runAttemptCount >= 3) Result.failure() else Result.retry()
    }
  }

  companion object {
    fun enqueue(context: Context) {
      OneTimeWorkRequestBuilder<AgentRescheduleWorker>().build().also {
        WorkManager.getInstance(context).enqueue(it)
      }
    }
  }
}
