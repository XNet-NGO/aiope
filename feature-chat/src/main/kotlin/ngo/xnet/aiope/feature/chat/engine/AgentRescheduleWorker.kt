package ngo.xnet.aiope.feature.chat.engine

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ngo.xnet.aiope.feature.chat.db.ChatDatabase
import ngo.xnet.aiope.feature.chat.di.MIGRATION_1_2
import ngo.xnet.aiope.feature.chat.di.MIGRATION_2_3
import ngo.xnet.aiope.feature.chat.di.MIGRATION_3_4
import ngo.xnet.aiope.feature.chat.di.MIGRATION_4_5
import ngo.xnet.aiope.feature.chat.di.MIGRATION_5_6
import ngo.xnet.aiope.feature.chat.di.MIGRATION_6_7
import ngo.xnet.aiope.feature.chat.di.MIGRATION_7_8

/**
 * Re-arms AlarmManager alarms for every enabled scheduled task.
 * Runs once on app start and after BOOT_COMPLETED (exact alarms do not survive reboot).
 */
class AgentRescheduleWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val db = Room.databaseBuilder(applicationContext, ChatDatabase::class.java, "aiope-chat.db")
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
      .build()
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
    } finally {
      db.close()
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
