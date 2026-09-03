package ngo.xnet.aiope.core.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Biometric / device-credential factor via androidx.biometric. Contains no Google Play Services
 * dependency. Uses BIOMETRIC_STRONG, falling back to device credential (PIN/pattern/password).
 */
class BiometricUnlock {

  private val allowedAuthenticators =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

  fun isAvailable(activity: FragmentActivity): Boolean =
    when (BiometricManager.from(activity).canAuthenticate(allowedAuthenticators)) {
      BiometricManager.BIOMETRIC_SUCCESS -> true
      else -> false
    }

  /** Present the biometric prompt and await the result. */
  suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): AuthResult =
    suspendCancellableCoroutine { cont ->
      val availability = BiometricManager.from(activity).canAuthenticate(allowedAuthenticators)
      if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
        cont.resume(AuthResult.Unavailable("Biometric not available (code $availability)"))
        return@suspendCancellableCoroutine
      }

      val executor = ContextCompat.getMainExecutor(activity)
      val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (cont.isActive) cont.resume(AuthResult.Success)
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (!cont.isActive) return
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
              errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
              errorCode == BiometricPrompt.ERROR_CANCELED
            cont.resume(if (cancelled) AuthResult.Cancelled else AuthResult.Failure("$errString ($errorCode)"))
          }

          override fun onAuthenticationFailed() {
            // A single non-match; the prompt stays up. Don't resume here.
          }
        },
      )

      val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(allowedAuthenticators)
        .build()

      prompt.authenticate(info)
    }
}
