package ngo.xnet.aiope.feature.chat.settings

import android.content.Context
import android.content.Intent

/**
 * Starts/stops the floating voice-button overlay service. Uses an explicit component by name so
 * this (feature-chat) code can control the service that lives in the :app module without a compile
 * dependency on it.
 */
object VoiceOverlayControl {
  private const val SERVICE = "ngo.xnet.aiope.AssistantOverlayService"

  @Volatile
  var isRunning: Boolean = false
    private set

  private fun intent(context: Context) = Intent().setClassName(context.packageName, SERVICE)

  fun start(context: Context) {
    try {
      context.startForegroundService(intent(context))
      isRunning = true
    } catch (_: Exception) {}
  }

  fun stop(context: Context) {
    try {
      context.stopService(intent(context))
      isRunning = false
    } catch (_: Exception) {}
  }
}
