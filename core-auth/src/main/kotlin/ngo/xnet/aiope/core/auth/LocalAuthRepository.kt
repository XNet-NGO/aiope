package ngo.xnet.aiope.core.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-local [AuthRepository] for v1. Each factor is opt-in and independently toggleable.
 * An optional app-launch gate ([setAppLock]) requires one enrolled factor before the UI opens.
 * Verification is delegated to a [Verifier] ([LocalVerifier] in v1).
 */
class LocalAuthRepository(
  private val context: Context,
  private val settings: AuthSettings,
  private val identity: KeystoreIdentity,
  private val biometric: BiometricUnlock,
  private val totp: TotpEnrollment,
  private val securityKey: SecurityKeyAuthenticator,
) : AuthRepository {

  private val _state = MutableStateFlow(snapshot())
  override val state: StateFlow<AuthState> = _state.asStateFlow()

  private fun snapshot() = AuthState(
    accountId = settings.accountId(),
    enabledFactors = settings.enabledFactors(),
    appLockEnabled = settings.isAppLockEnabled(),
  )

  private fun refresh() {
    _state.value = snapshot()
  }

  override suspend fun isAvailable(factor: AuthFactor): Boolean = when (factor) {
    AuthFactor.BIOMETRIC -> true // Confirmed against the activity at enroll time.
    AuthFactor.SECURITY_KEY -> securityKey.isAvailable()
    AuthFactor.TOTP -> true
  }

  override suspend fun enroll(activity: FragmentActivity, factor: AuthFactor): AuthResult {
    // Ensure the account identity key exists before enrolling any signature-based factor.
    identity.ensureKeyPair()
    val result = when (factor) {
      AuthFactor.BIOMETRIC ->
        biometric.authenticate(activity, "Enable biometric unlock", "Confirm to enable this factor")
      AuthFactor.SECURITY_KEY ->
        securityKey.awaitKeyPresence(activity)
      AuthFactor.TOTP -> {
        val enrollment = totp.enroll(accountLabel = settings.accountId())
        AuthResult.Enrolled(enrollment.secret, enrollment.otpauthUri)
      }
    }
    if (result is AuthResult.Success || result is AuthResult.Enrolled) {
      settings.setEnabled(factor, true)
      refresh()
    }
    return result
  }

  override suspend fun verify(activity: FragmentActivity, factor: AuthFactor): AuthResult {
    if (!_state.value.isEnabled(factor)) return AuthResult.Failure("Factor not enrolled")
    return when (factor) {
      AuthFactor.BIOMETRIC ->
        biometric.authenticate(activity, "Verify it's you", "Authenticate to continue")
      AuthFactor.SECURITY_KEY ->
        securityKey.awaitKeyPresence(activity)
      AuthFactor.TOTP ->
        AuthResult.Success // Code entry handled by the UI, then passed to verifyTotpCode().
    }
  }

  override suspend fun disable(factor: AuthFactor) {
    if (factor == AuthFactor.TOTP) totp.disable()
    settings.setEnabled(factor, false)
    refresh()
  }

  override fun setAppLock(enabled: Boolean) {
    settings.setAppLockEnabled(enabled)
    refresh()
  }

  override fun verifyTotpCode(code: String): Boolean = totp.verify(code)

  /** Exposes the TOTP helper for the UI (e.g. live current-code display). */
  fun totpEnrollment(): TotpEnrollment = totp
}
