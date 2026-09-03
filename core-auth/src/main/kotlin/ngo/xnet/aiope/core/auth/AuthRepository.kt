package ngo.xnet.aiope.core.auth

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade over AIOPE's optional authentication factors.
 *
 * v1 semantics:
 *  - Every factor is opt-in and independently toggleable from Settings.
 *  - Enabling a factor is NOT a gate; the app remains fully usable without any factor.
 *  - Verification is device-local (see [Verifier]); the seam allows a future server RP.
 */
interface AuthRepository {

  /** Observable state: account id + currently enabled factors. */
  val state: StateFlow<AuthState>

  /** Whether [factor] can be enrolled on this device right now (hardware/OS support). */
  suspend fun isAvailable(factor: AuthFactor): Boolean

  /**
   * Enroll and enable [factor]. Requires an [activity] because biometric and security-key
   * flows present system UI. On success the factor is persisted as enabled and [state] updates.
   */
  suspend fun enroll(activity: FragmentActivity, factor: AuthFactor): AuthResult

  /**
   * Verify a previously enrolled [factor] (used by tailored features that want a fresh proof
   * of presence). Prompts the user as needed.
   */
  suspend fun verify(activity: FragmentActivity, factor: AuthFactor): AuthResult

  /** Disable and forget [factor]. Removes any sealed key material / secret for it. */
  suspend fun disable(factor: AuthFactor)

  /**
   * Turn the app-launch gate on/off. Only effective while at least one factor is enrolled
   * (see [AuthState.gateActive]).
   */
  fun setAppLock(enabled: Boolean)

  /** Verify a user-entered TOTP [code] against the enrolled secret. */
  fun verifyTotpCode(code: String): Boolean
}
