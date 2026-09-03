package ngo.xnet.aiope.feature.chat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import ngo.xnet.aiope.core.auth.AuthFactor
import ngo.xnet.aiope.core.auth.AuthResult

/**
 * Enforces the optional app-launch gate. If the gate is inactive (user hasn't opted in or has no
 * factor enrolled), [content] renders immediately. Otherwise the user must pass one enrolled
 * factor before [content] is shown.
 */
@Composable
fun AuthGate(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val repo = remember { authRepository(context) }
  val activity = context as? FragmentActivity
  val state by repo.state.collectAsState()

  // If the gate isn't active, don't block anything.
  if (!state.gateActive) {
    content()
    return
  }

  var unlocked by rememberSaveable { mutableStateOf(false) }

  // Re-lock whenever the app leaves the foreground, so returning requires authenticating again.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) unlocked = false
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  if (unlocked) {
    content()
    return
  }

  val scope = rememberCoroutineScope()
  var error by remember { mutableStateOf("") }
  var totpCode by remember { mutableStateOf("") }
  var awaitingKey by remember { mutableStateOf(false) }
  val factors = state.enabledFactors

  // Auto-trigger biometric on entry when enrolled (it has its own system UI). Security key
  // requires an explicit tap, so we don't auto-start it here.
  LaunchedEffect(Unit) {
    val act = activity ?: return@LaunchedEffect
    if (AuthFactor.BIOMETRIC in factors) {
      when (val r = repo.verify(act, AuthFactor.BIOMETRIC)) {
        is AuthResult.Success, is AuthResult.Enrolled -> unlocked = true
        is AuthResult.Cancelled -> error = "Authentication cancelled."
        is AuthResult.Unavailable -> error = "Unavailable: ${r.reason}"
        is AuthResult.Failure -> error = r.reason
      }
    }
  }

  Surface(Modifier.fillMaxSize()) {
    Column(
      Modifier.fillMaxSize().padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
      Spacer(Modifier.height(16.dp))
      Text("AIOPE is locked", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(8.dp))
      Text("Authenticate to continue.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(24.dp))

      if (AuthFactor.BIOMETRIC in factors) {
        Button(onClick = {
          val act = activity ?: return@Button
          scope.launch {
            when (val r = repo.verify(act, AuthFactor.BIOMETRIC)) {
              is AuthResult.Success, is AuthResult.Enrolled -> unlocked = true
              is AuthResult.Cancelled -> error = "Authentication cancelled."
              is AuthResult.Unavailable -> error = "Unavailable: ${r.reason}"
              is AuthResult.Failure -> error = r.reason
            }
          }
        }) { Text("Unlock with biometrics") }
        Spacer(Modifier.height(16.dp))
      }

      if (AuthFactor.SECURITY_KEY in factors) {
        Button(onClick = {
          val act = activity ?: return@Button
          error = ""
          awaitingKey = true
          scope.launch {
            when (val r = repo.verify(act, AuthFactor.SECURITY_KEY)) {
              is AuthResult.Success, is AuthResult.Enrolled -> unlocked = true
              is AuthResult.Cancelled -> error = "Authentication cancelled."
              is AuthResult.Unavailable -> error = "Unavailable: ${r.reason}"
              is AuthResult.Failure -> error = r.reason
            }
            awaitingKey = false
          }
        }) { Text("Unlock with security key") }
        if (awaitingKey) {
          Spacer(Modifier.height(8.dp))
          Text("Tap your key to the back of the phone or plug it in…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
        }
        Spacer(Modifier.height(16.dp))
      }

      if (AuthFactor.TOTP in factors) {
        OutlinedTextField(
          value = totpCode,
          onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
          label = { Text("6-digit code") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
          if (repo.verifyTotpCode(totpCode)) unlocked = true else error = "Invalid code."
        }) { Text("Unlock with code") }
      }

      if (error.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
