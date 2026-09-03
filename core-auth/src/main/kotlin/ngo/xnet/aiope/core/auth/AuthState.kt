package ngo.xnet.aiope.core.auth

/**
 * The authentication factors AIOPE supports. All are optional and opt-in; a user may
 * enable none, one, or several. Enabling a factor does NOT gate the app in v1 — it is a
 * per-user preference that tailored features can build on top of.
 */
enum class AuthFactor(val id: String, val displayName: String) {
  /** Device biometric / device credential via androidx.biometric. No Google Play Services. */
  BIOMETRIC("biometric", "Biometric unlock"),

  /** External hardware security key (YubiKey / Thetis / any CTAP2) over USB or NFC. */
  SECURITY_KEY("security_key", "Hardware security key"),

  /** RFC 6238 time-based one-time password, secret sealed in the Android Keystore. */
  TOTP("totp", "Authenticator app (TOTP)"),
  ;

  companion object {
    fun from(id: String): AuthFactor? = entries.firstOrNull { it.id == id }
  }
}

/**
 * Snapshot of which factors are currently enrolled/enabled plus the stable account identity.
 *
 * @param accountId stable per-account identifier (migrated from the legacy anonymous userUUID).
 * @param enabledFactors factors the user has enrolled and turned on.
 */
data class AuthState(
  val accountId: String,
  val enabledFactors: Set<AuthFactor> = emptySet(),
  val appLockEnabled: Boolean = false,
) {
  val hasAnyFactor: Boolean get() = enabledFactors.isNotEmpty()

  fun isEnabled(factor: AuthFactor): Boolean = factor in enabledFactors

  /** The gate is only effective when the user opted in AND has at least one factor. */
  val gateActive: Boolean get() = appLockEnabled && hasAnyFactor
}

/** Result of an enroll/verify operation for a single factor. */
sealed interface AuthResult {
  data object Success : AuthResult

  /**
   * Enrollment succeeded and produced provisioning material the user must save/scan.
   * Used by TOTP: [secret] is the Base32 seed, [otpauthUri] is a standard `otpauth://` URI
   * that authenticator apps can import via QR or paste.
   */
  data class Enrolled(val secret: String, val otpauthUri: String) : AuthResult

  /** User dismissed the prompt / removed the key mid-flow. Not an error to report loudly. */
  data object Cancelled : AuthResult

  /** The factor is not usable on this device (no sensor, no NFC/USB, no hardware key present). */
  data class Unavailable(val reason: String) : AuthResult

  data class Failure(val reason: String, val cause: Throwable? = null) : AuthResult
}
