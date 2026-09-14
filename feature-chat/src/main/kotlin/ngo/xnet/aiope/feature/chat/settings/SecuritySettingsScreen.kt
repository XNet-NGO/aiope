package ngo.xnet.aiope.feature.chat.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import ngo.xnet.aiope.core.auth.AuthFactor
import ngo.xnet.aiope.core.auth.AuthRepository
import ngo.xnet.aiope.core.auth.AuthResult

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AuthEntryPoint {
  fun authRepository(): AuthRepository
}

internal fun authRepository(context: Context): AuthRepository =
  EntryPointAccessors.fromApplication(context.applicationContext, AuthEntryPoint::class.java).authRepository()

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaceDaoEntryPoint {
  fun chatDao(): ngo.xnet.aiope.feature.chat.db.ChatDao
}

internal fun faceChatDao(context: Context): ngo.xnet.aiope.feature.chat.db.ChatDao =
  EntryPointAccessors.fromApplication(context.applicationContext, FaceDaoEntryPoint::class.java).chatDao()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecuritySettingsScreen(onBack: () -> Unit) {
  val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
  val scaffoldColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { authRepository(context) }
  val activity = context as? FragmentActivity

  val state by repo.state.collectAsState()
  var status by remember { mutableStateOf("") }
  var totpSecret by remember { mutableStateOf<String?>(null) }
  var totpUri by remember { mutableStateOf<String?>(null) }
  var totpCode by remember { mutableStateOf("") }
  var awaitingKey by remember { mutableStateOf(false) }

  // Assistant (default digital assistant) role state.
  var assistantAvailable by remember { mutableStateOf(AssistantRole.isAvailable(context)) }
  var assistantHeld by remember { mutableStateOf(AssistantRole.isHeld(context)) }
  val assistantRoleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    ngo.xnet.aiope.core.preferences.AuthInterop.end()
    assistantHeld = AssistantRole.isHeld(context)
    status = if (result.resultCode == android.app.Activity.RESULT_OK || assistantHeld) {
      "AIOPE is now the device assistant."
    } else {
      "Assistant role not granted."
    }
  }
  // Refresh held status whenever this screen composes (e.g. returning from system settings).
  androidx.compose.runtime.LaunchedEffect(Unit) {
    assistantHeld = AssistantRole.isHeld(context)
    assistantAvailable = AssistantRole.isAvailable(context)
  }
  var keyJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

  fun toggle(factor: AuthFactor, enable: Boolean) {
    val act = activity
    if (enable && act == null) {
      status = "This screen must run in a FragmentActivity to enroll ${factor.displayName}."
      return
    }
    val job = scope.launch {
      if (enable) {
        if (factor == AuthFactor.SECURITY_KEY) awaitingKey = true
        when (val r = repo.enroll(act!!, factor)) {
          is AuthResult.Success -> status = "${factor.displayName} enabled."
          is AuthResult.Enrolled -> {
            totpSecret = r.secret
            totpUri = r.otpauthUri
            status = "${factor.displayName} enabled. Save the setup code below."
          }
          is AuthResult.Cancelled -> status = "Cancelled."
          is AuthResult.Unavailable -> status = "${factor.displayName} unavailable: ${r.reason}"
          is AuthResult.Failure -> status = "Failed: ${r.reason}"
        }
        awaitingKey = false
      } else {
        repo.disable(factor)
        if (factor == AuthFactor.TOTP) {
          totpSecret = null
          totpUri = null
        }
        status = "${factor.displayName} disabled."
      }
    }
    keyJob = job
  }

  fun copy(label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    status = "$label copied to clipboard."
  }

  Scaffold(
    containerColor = scaffoldColor,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        title = { Text("Security") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
      Text(
        "Optional sign-in factors. Verification is on-device in this version; a future update can verify against a server for stronger abuse resistance.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
      )

      // On-device face identities (opt-in personalization).
      ngo.xnet.aiope.feature.chat.face.FaceIdentitiesSection(context)
      HorizontalDivider()

      FactorRow(
        title = AuthFactor.BIOMETRIC.displayName,
        subtitle = "Fingerprint, face, or device PIN/pattern. No Google services.",
        enabled = state.isEnabled(AuthFactor.BIOMETRIC),
        onToggle = { toggle(AuthFactor.BIOMETRIC, it) },
      )
      HorizontalDivider()
      FactorRow(
        title = AuthFactor.SECURITY_KEY.displayName,
        subtitle = "YubiKey, Thetis, or any CTAP2 key over USB or NFC.",
        enabled = state.isEnabled(AuthFactor.SECURITY_KEY),
        onToggle = { toggle(AuthFactor.SECURITY_KEY, it) },
      )
      HorizontalDivider()
      FactorRow(
        title = AuthFactor.TOTP.displayName,
        subtitle = "Time-based codes (RFC 6238). Secret sealed in hardware Keystore.",
        enabled = state.isEnabled(AuthFactor.TOTP),
        onToggle = { toggle(AuthFactor.TOTP, it) },
      )

      // TOTP provisioning material — shown right after enrolling so the user can set up their app.
      if (state.isEnabled(AuthFactor.TOTP) && totpSecret != null) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
          Column(Modifier.padding(16.dp)) {
            Text("Authenticator setup", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
              "Add this account to your authenticator app (Aegis, Google Authenticator, etc.) using the secret or the otpauth link, then verify a code below.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("Secret key", style = MaterialTheme.typography.labelMedium)
            Text(totpSecret!!.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            Row {
              TextButton(onClick = { copy("Secret", totpSecret!!) }) { Text("Copy secret") }
              totpUri?.let { uri -> TextButton(onClick = { copy("otpauth URI", uri) }) { Text("Copy otpauth link") } }
            }
          }
        }
      }

      // TOTP verify box (usable once enrolled).
      if (state.isEnabled(AuthFactor.TOTP)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
          OutlinedTextField(
            value = totpCode,
            onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
            label = { Text("Enter 6-digit code to verify") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
          )
          Spacer(Modifier.height(8.dp))
          Button(onClick = {
            if (repo.verifyTotpCode(totpCode)) {
              status = "TOTP code valid. Setup complete."
              totpSecret = null
              totpUri = null
              totpCode = ""
            } else {
              status = "TOTP code invalid."
            }
          }) { Text("Verify") }
        }
      }

      HorizontalDivider(Modifier.padding(top = 8.dp))

      // The app-launch gate. Only effective when a factor is enrolled.
      ListItem(
        headlineContent = { Text("Require authentication to open the app") },
        supportingContent = {
          Text(
            if (state.hasAnyFactor) "Ask for an enrolled factor each time the app opens."
            else "Enable a factor above first to use this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        trailingContent = {
          Switch(
            checked = state.appLockEnabled,
            enabled = state.hasAnyFactor,
            onCheckedChange = { repo.setAppLock(it) },
          )
        },
      )

      if (assistantAvailable) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        ListItem(
          headlineContent = { Text("Set AIOPE as device assistant") },
          supportingContent = {
            Text(
              if (assistantHeld) "AIOPE is the default assistant. Assist gesture / long-press home opens AIOPE."
              else "Make AIOPE the default digital assistant (assist gesture, long-press home).",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          trailingContent = {
            if (assistantHeld) {
              Text("✔", color = MaterialTheme.colorScheme.primary)
            } else {
              TextButton(onClick = {
                val intent = AssistantRole.requestIntent(context)
                if (intent != null) {
                  ngo.xnet.aiope.core.preferences.AuthInterop.begin()
                  assistantRoleLauncher.launch(intent)
                } else {
                  status = "Assistant role unavailable on this device."
                }
              }) { Text("Set") }
            }
          },
        )
      }

      // Floating voice button (overlay over other apps).
      HorizontalDivider(Modifier.padding(top = 8.dp))
      var overlayEnabled by remember { mutableStateOf(VoiceOverlayControl.isRunning) }
      val overlayPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
      ) { _ ->
        ngo.xnet.aiope.core.preferences.AuthInterop.end()
        if (android.provider.Settings.canDrawOverlays(context)) {
          VoiceOverlayControl.start(context); overlayEnabled = true
          status = "Floating voice button enabled."
        } else {
          status = "Overlay permission not granted."
        }
      }
      ListItem(
        headlineContent = { Text("Floating voice button") },
        supportingContent = {
          Text(
            "Show a draggable mic over other apps to talk to AIOPE from anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        trailingContent = {
          Switch(
            checked = overlayEnabled,
            onCheckedChange = { on ->
              if (on) {
                if (android.provider.Settings.canDrawOverlays(context)) {
                  VoiceOverlayControl.start(context); overlayEnabled = true
                } else {
                  ngo.xnet.aiope.core.preferences.AuthInterop.begin()
                  overlayPermLauncher.launch(
                    android.content.Intent(
                      android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                      android.net.Uri.parse("package:${context.packageName}"),
                    ),
                  )
                }
              } else {
                VoiceOverlayControl.stop(context); overlayEnabled = false
              }
            },
          )
        },
      )

      if (status.isNotEmpty()) {
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
      }
    }

    if (awaitingKey) {
      AlertDialog(
        onDismissRequest = {},
        title = { Text("Insert or tap your security key") },
        text = {
          Column {
            Text("Tap your key to the back of the phone (NFC) or plug it in via USB.")
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = {
            keyJob?.cancel()
            awaitingKey = false
            status = "Cancelled."
          }) { Text("Cancel") }
        },
      )
    }
  }
}

@Composable
private fun FactorRow(title: String, subtitle: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    trailingContent = { Switch(checked = enabled, onCheckedChange = onToggle) },
  )
}
