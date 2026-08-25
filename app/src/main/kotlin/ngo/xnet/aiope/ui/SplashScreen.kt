package ngo.xnet.aiope.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ngo.xnet.aiope.feature.chat.ui.CuORadius

/**
 * First-run welcome + permission setup, shown instead of the old animated splash.
 *
 * CuO needs a specific set of runtime permissions to be a useful on-device agent; asking for them
 * one at a time mid-conversation is exactly the flow that gets users lost. This screen lists every
 * capability once, shows live granted/denied state (including the two special cases that cannot go
 * through the normal dialog: All-Files access and exact alarms, which deep-link to system settings),
 * and lets the user continue whenever they are done — every tool also works with a permission
 * denied here because ToolExecutor re-requests at use time.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
  val ctx = LocalContext.current
  val cs = MaterialTheme.colorScheme

  var mic by remember { mutableStateOf(granted(ctx, Manifest.permission.RECORD_AUDIO)) }
  var camera by remember { mutableStateOf(granted(ctx, Manifest.permission.CAMERA)) }
  var location by remember {
    mutableStateOf(granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION))
  }
  var notifications by remember { mutableStateOf(granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) }
  var calendar by remember { mutableStateOf(granted(ctx, Manifest.permission.READ_CALENDAR)) }
  var contacts by remember { mutableStateOf(granted(ctx, Manifest.permission.READ_CONTACTS)) }
  var allFiles by remember { mutableStateOf(hasAllFiles()) }
  var exactAlarms by remember { mutableStateOf(hasExactAlarms(ctx)) }

  val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    mic = granted(ctx, Manifest.permission.RECORD_AUDIO)
    camera = granted(ctx, Manifest.permission.CAMERA)
    location = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    notifications = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    calendar = granted(ctx, Manifest.permission.READ_CALENDAR)
    contacts = granted(ctx, Manifest.permission.READ_CONTACTS)
  }
  val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    allFiles = hasAllFiles()
    exactAlarms = hasExactAlarms(ctx)
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(cs.background)
      .padding(horizontal = 20.dp),
  ) {
    Spacer(Modifier.height(36.dp))
    Text("Welcome to", fontSize = 15.sp, color = cs.onSurfaceVariant)
    Text("CuO Agentic Android", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
    Text(
      "An AI agent that lives on your device. Grant what you're comfortable with — tools you skip will simply ask again when they're needed.",
      fontSize = 13.sp,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
    )

    Surface(shape = RoundedCornerShape(CuORadius.xl), color = cs.surfaceContainer.copy(alpha = 0.5f)) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PermRow(Icons.Default.Mic, "Microphone", "Voice dictation & live calls", mic) {
          multiLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        PermRow(Icons.Default.PhotoCamera, "Camera", "Photos & vision input", camera) {
          multiLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        PermRow(Icons.Default.LocationOn, "Location", "Nearby places & GPS tools", location) {
          multiLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        PermRow(Icons.Default.Notifications, "Notifications", "Agent reports & scheduled runs", notifications) {
          multiLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        PermRow(Icons.Default.CalendarMonth, "Calendar & Contacts", "Events, reminders, contacts", calendar && contacts) {
          multiLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS))
        }
        PermRow(Icons.Default.Folder, "All files access", "Read/write files anywhere on device", allFiles) {
          settingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
          )
        }
        PermRow(Icons.Default.Alarm, "Alarms & reminders", "Precise timing for scheduled tasks", exactAlarms) {
          settingsLauncher.launch(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
      }
    }

    Spacer(Modifier.weight(1f))
    Surface(
      shape = RoundedCornerShape(CuORadius.xl),
      color = cs.primary,
      modifier = Modifier
        .fillMaxWidth()
        .clickable {
          ctx.getSharedPreferences("cuo_main", Context.MODE_PRIVATE)
            .edit().putBoolean("welcome_seen", true).apply()
          onFinished()
        },
    ) {
      Text(
        "Get started",
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = cs.onPrimary,
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 16.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
    }
    Spacer(Modifier.height(28.dp))
  }
}

@Composable
private fun PermRow(icon: ImageVector, title: String, subtitle: String, isGranted: Boolean, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 10.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(shape = CircleShape, color = if (isGranted) cs.primaryContainer else cs.surfaceContainerHigh, modifier = Modifier.size(38.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (isGranted) cs.onPrimaryContainer else cs.onSurfaceVariant)
      }
    }
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
      Text(subtitle, fontSize = 11.sp, color = cs.onSurfaceVariant)
    }
    Icon(
      Icons.Default.CheckCircle,
      contentDescription = if (isGranted) "Granted" else "Not granted",
      Modifier.size(22.dp),
      tint = if (isGranted) cs.primary else cs.outlineVariant,
    )
  }
}

private fun granted(ctx: Context, perm: String): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun hasAllFiles(): Boolean = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

private fun hasExactAlarms(ctx: Context): Boolean = android.os.Build.VERSION.SDK_INT < 31 || ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
