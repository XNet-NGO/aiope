package ngo.xnet.aiope.core.auth

/**
 * The verification seam.
 *
 * A factor's "proof" (a biometric-gated Keystore signature, a CTAP2 assertion from a security
 * key, or a TOTP code) is only meaningfully anti-abuse if a party the operator controls issues
 * the challenge and verifies the response. In v1 that party is the device itself
 * ([LocalVerifier]) — this gives a real Keystore/hardware-backed UX and a stable identity, but
 * NOT server-grade abuse resistance, because the verifier runs on the same (attackable) device.
 *
 * When AIOPE gains an operator-controlled backend (the AIOPE Gateway — a Go/SQLite service with
 * bearer-token auth, see `internal/gateway` + `internal/web` in the ax lineage), a
 * `GatewayVerifier` can implement this same interface: POST a challenge request, receive a
 * random challenge, sign/assert on-device, POST the response for server verification. Nothing
 * above this interface changes.
 */
interface Verifier {

  /** A random challenge to be signed/answered by a factor. */
  suspend fun challenge(accountId: String, factor: AuthFactor): ByteArray

  /**
   * Verify a factor's response to [challenge]. In v1 this is checked locally; later it is
   * checked by the gateway RP.
   *
   * @param response factor-specific proof bytes (signature, CTAP2 assertion, or TOTP code bytes).
   */
  suspend fun verify(accountId: String, factor: AuthFactor, challenge: ByteArray, response: ByteArray): Boolean

  /**
   * Whether this verifier is backed by an operator-controlled server. `false` for the v1
   * device-local verifier; UI can surface this so users understand the trust model.
   */
  val isServerBacked: Boolean
}
