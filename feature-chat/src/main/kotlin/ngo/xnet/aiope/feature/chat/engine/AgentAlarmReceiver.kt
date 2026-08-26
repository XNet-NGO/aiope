package ngo.xnet.aiope.feature.chat.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Fires when a scheduled task's alarm goes off.
 * Lightweight: only forwards the task id to a one-time [AgentRunWorker].
 */
class AgentAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val taskId = intent.getStringExtra(AgentScheduler.EXTRA_TASK_ID) ?: return
    val request = OneTimeWorkRequestBuilder<AgentRunWorker>()
      .setInputData(Data.Builder().putString(AgentRunWorker.KEY_TASK_ID, taskId).build())
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      "agent-run-$taskId",
      ExistingWorkPolicy.REPLACE,
      request,
    )
  }
}
