package com.aiope2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class AiopeForegroundService : Service() {

  private var wakeLock: PowerManager.WakeLock? = null

  companion object {
    const val CHANNEL_ID = "aiope2_persistent"
    const val NOTIFICATION_ID = 1
  }

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIFICATION_ID, buildNotification())
    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aiope2::background")
      .apply { acquire() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onDestroy() {
    wakeLock?.let { if (it.isHeld) it.release() }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createChannel() {
    val channel = NotificationChannel(CHANNEL_ID, "AIOPE Background", NotificationManager.IMPORTANCE_LOW).apply {
      description = "Keeps AIOPE running"
      setShowBadge(false)
      setSound(null, null)
    }
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("AIOPE 2")
      .setContentText("Running")
      .setSmallIcon(android.R.drawable.ic_menu_manage)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(pending)
      .build()
  }
}
