package ngo.xnet.aiope.core.auth

import android.content.Context
import java.security.SecureRandom

/**
 * v1 device-local verifier. Issues random challenges and verifies factor proofs on-device using
 * the Keystore-backed identity key (for signature-based factors) or the local TOTP secret.
 *
 * NOTE: because the verifier runs on the same device that produces the proof, this provides a
 * hardware-backed UX and stable identity but limited abuse resistance. A future GatewayVerifier
 * implementing this same interface against the AIOPE Gateway would provide server-grade
 * verification without changing callers.
 */
class LocalVerifier(
  context: Context,
  private val identity: KeystoreIdentity,
) : Verifier {

  private val totp = TotpEnrollment(context)
  private val random = SecureRandom()

  override val isServerBacked: Boolean = false

  override suspend fun challenge(accountId: String, factor: AuthFactor): ByteArray =
    ByteArray(32).also { random.nextBytes(it) }

  override suspend fun verify(
    accountId: String,
    factor: AuthFactor,
    challenge: ByteArray,
    response: ByteArray,
  ): Boolean = when (factor) {
    // Biometric/security-key presence is proven by a Keystore identity signature over the challenge.
    AuthFactor.BIOMETRIC, AuthFactor.SECURITY_KEY -> identity.verify(challenge, response)
    // TOTP: response is the ASCII code bytes.
    AuthFactor.TOTP -> totp.verify(String(response, Charsets.US_ASCII))
  }
}
