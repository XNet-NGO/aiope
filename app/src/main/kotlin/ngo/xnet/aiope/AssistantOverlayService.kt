package ngo.xnet.aiope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A small draggable floating mic button drawn over other apps via SYSTEM_ALERT_WINDOW. Tapping it
 * brings AIOPE forward and starts the voice assistant, so the user can invoke AIOPE's voice while
 * any other app is in the foreground. Purely an overlay UI on AIOPE's own window — it does not read
 * or inject input into other apps.
 *
 * Only started when the user enables the feature AND has granted the draw-over-other-apps permission.
 */
class AssistantOverlayService : Service() {

  private var windowManager: WindowManager? = null
  private var bubble: View? = null
  private var bubbleBg: android.graphics.drawable.GradientDrawable? = null
  private val scope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
  )

  private fun controller() =
    dagger.hilt.android.EntryPointAccessors.fromApplication(
      applicationContext,
      VoiceOverlayEntryPoint::class.java,
    ).voiceController()

  /** Bubble color per voice state. */
  private fun colorFor(state: ngo.xnet.aiope.feature.chat.engine.VoiceState): Int = when (state) {
    ngo.xnet.aiope.feature.chat.engine.VoiceState.IDLE -> 0xFF1E88E5.toInt()      // blue
    ngo.xnet.aiope.feature.chat.engine.VoiceState.STARTING -> 0xFFF9A825.toInt()  // amber
    ngo.xnet.aiope.feature.chat.engine.VoiceState.LISTENING -> 0xFF43A047.toInt() // green
    ngo.xnet.aiope.feature.chat.engine.VoiceState.SPEAKING -> 0xFF8E24AA.toInt()  // purple
  }

  companion object {
    private const val CHANNEL_ID = "aiope_overlay"
    private const val NOTIFICATION_ID = 3
    const val EXTRA_START_VOICE = "aiope_start_voice"

    fun canDraw(context: android.content.Context): Boolean =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, buildNotification())
    }
    if (canDraw(this)) addBubble() else stopSelf()
  }

  private fun addBubble() {
    if (bubble != null) return
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    windowManager = wm

    val size = (56 * resources.displayMetrics.density).toInt()
    val bg = android.graphics.drawable.GradientDrawable().apply {
      shape = android.graphics.drawable.GradientDrawable.OVAL
      setColor(colorFor(ngo.xnet.aiope.feature.chat.engine.VoiceState.IDLE))
    }
    bubbleBg = bg
    val iv = ImageView(this).apply {
      setImageResource(android.R.drawable.ic_btn_speak_now)
      background = bg
      setPadding(size / 4, size / 4, size / 4, size / 4)
      alpha = 0.9f
    }

    val params = WindowManager.LayoutParams(
      size, size,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = 24
      y = 240
    }

    // Drag to move; tap (no significant movement) to trigger voice.
    var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
    iv.setOnTouchListener { _, e ->
      when (e.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
          if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
          params.x = startX + dx; params.y = startY + dy
          runCatching { wm.updateViewLayout(iv, params) }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) launchVoice()
          true
        }
        else -> false
      }
    }

    runCatching { wm.addView(iv, params) }
    bubble = iv

    // Live-tint the bubble based on the shared voice state.
    scope.launch {
      controller().state.collect { s ->
        bubbleBg?.setColor(colorFor(s))
        iv.alpha = if (s == ngo.xnet.aiope.feature.chat.engine.VoiceState.IDLE) 0.9f else 1f
      }
    }
  }

  private fun launchVoice() {
    // Headless: toggle the process-scoped voice controller directly, without launching the
    // activity. The controller is a Hilt @Singleton, reachable via an app EntryPoint.
    try {
      controller().toggle()
    } catch (e: Exception) {
      android.util.Log.e("AIOPE2", "overlay voice toggle failed: ${e.message}", e)
    }
  }

  @dagger.hilt.EntryPoint
  @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
  interface VoiceOverlayEntryPoint {
    fun voiceController(): ngo.xnet.aiope.feature.chat.engine.VoiceSessionController
  }

  override fun onDestroy() {
    runCatching { scope.cancel() }
    bubble?.let { b -> runCatching { windowManager?.removeView(b) } }
    bubble = null
    super.onDestroy()
  }

  private fun createChannel() {
    val ch = NotificationChannel(CHANNEL_ID, "AIOPE Voice Overlay", NotificationManager.IMPORTANCE_MIN).apply {
      setShowBadge(false); setSound(null, null)
    }
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
  }

  private fun buildNotification(): Notification {
    val pending = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("AIOPE voice button active")
      .setContentText("Tap the floating mic to talk to AIOPE")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(pending)
      .build()
  }
}
