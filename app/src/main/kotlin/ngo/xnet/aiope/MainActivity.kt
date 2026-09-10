package ngo.xnet.aiope

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import ngo.xnet.aiope.core.navigation.AppComposeNavigator
import ngo.xnet.aiope.feature.chat.settings.ProviderStore
import ngo.xnet.aiope.feature.chat.settings.ToolStore
import ngo.xnet.aiope.ui.AiopeMain
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  @Inject lateinit var composeNavigator: AppComposeNavigator

  @Inject lateinit var providerStore: ProviderStore

  @Inject lateinit var toolStore: ToolStore

  @Inject lateinit var chatDao: ngo.xnet.aiope.feature.chat.db.ChatDao

  private val runtimePermissions = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
  }.toTypedArray()

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { results ->
    // After runtime permissions, request All Files Access if needed
    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
      try {
        startActivity(
          Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
          ),
        )
      } catch (_: Exception) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    handleAssistIntent(intent)

    // Request permissions on first launch
    val needed = runtimePermissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isNotEmpty()) {
      permissionLauncher.launch(needed.toTypedArray())
    } else if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
      try {
        startActivity(
          Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
          ),
        )
      } catch (_: Exception) {}
    }

    // Request battery optimization exemption
    val pm = getSystemService(android.os.PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
      try {
        startActivity(
          Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
          ),
        )
      } catch (_: Exception) {}
    }

    // Start foreground service
    startForegroundService(Intent(this, AiopeForegroundService::class.java))

    setContent { AiopeMain(composeNavigator = composeNavigator, providerStore = providerStore, toolStore = toolStore, chatDao = chatDao) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleAssistIntent(intent)
  }

  /**
   * When launched via the assist gesture (ACTION_ASSIST) or the floating voice button, capture any
   * assist context the system provides about the foreground app and flag that voice should start.
   * The chat layer reads these from AssistBridge to enrich the prompt and auto-open voice.
   */
  private fun handleAssistIntent(intent: Intent?) {
    intent ?: return
    val isAssist = intent.action == Intent.ACTION_ASSIST
    val startVoice = intent.getBooleanExtra(AssistantOverlayService.EXTRA_START_VOICE, false) || isAssist
    if (startVoice) ngo.xnet.aiope.core.preferences.AssistBridge.startVoice = true

    // Assist context: the assistant may receive a snapshot of the foreground app's visible text.
    if (isAssist) {
      val sb = StringBuilder()
      try {
        (intent.getParcelableExtra(Intent.EXTRA_ASSIST_CONTEXT) as? Bundle)?.let { b ->
          b.keySet().forEach { k -> b.getCharSequence(k)?.let { sb.append(it).append('\n') } }
        }
        intent.getCharSequenceExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD)?.let { sb.append(it).append('\n') }
      } catch (_: Exception) {}
      if (sb.isNotBlank()) ngo.xnet.aiope.core.preferences.AssistBridge.pendingContext = sb.toString().trim()
    }
  }
}
