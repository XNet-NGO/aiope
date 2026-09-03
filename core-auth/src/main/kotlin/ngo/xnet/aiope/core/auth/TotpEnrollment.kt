package ngo.xnet.aiope.core.auth

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * RFC 6238 TOTP with the shared secret sealed in the Android Keystore ([KeystoreSecretBox]).
 *
 * v1 is fully local: we generate the secret, seal it, and verify codes on-device. This is a
 * portable second factor for users without a hardware key. Real anti-abuse comes when the
 * secret lives on a server RP; the [Verifier] seam allows that later without changing this class.
 *
 * Base32 secret export lets the user add the same seed to a standalone authenticator app.
 */
class TotpEnrollment(context: Context) {

  private val prefs = context.getSharedPreferences("aiope_auth", Context.MODE_PRIVATE)
  private val box = KeystoreSecretBox()

  private val period = 30
  private val digits = 6
  private val algorithm = "HmacSHA1"

  fun isEnrolled(): Boolean = prefs.contains(KEY_SEALED_SECRET)

  /**
   * Generate a new random 20-byte secret, seal it, and return the Base32 secret plus a standard
   * `otpauth://` provisioning URI (issuer "AIOPE") so the user can add the seed to an
   * authenticator app via QR or manual entry. Overwrites any prior secret.
   */
  fun enroll(accountLabel: String = "AIOPE"): Enrollment {
    val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
    val base32 = base32Encode(secret)
    prefs.edit().putString(KEY_SEALED_SECRET, box.seal(secret)).apply()
    return Enrollment(base32, otpauthUri(base32, accountLabel))
  }

  /** Provisioning material returned by [enroll]. */
  data class Enrollment(val secret: String, val otpauthUri: String)

  private fun otpauthUri(base32Secret: String, account: String): String {
    val issuer = "AIOPE"
    val label = Uri.encode("$issuer:$account")
    return "otpauth://totp/$label?secret=$base32Secret&issuer=$issuer" +
      "&algorithm=SHA1&digits=$digits&period=$period"
  }

  fun disable() {
    prefs.edit().remove(KEY_SEALED_SECRET).apply()
  }

  /** Verify a user-entered [code], allowing +/- one time step for clock skew. */
  fun verify(code: String, atMillis: Long = System.currentTimeMillis()): Boolean {
    val sealed = prefs.getString(KEY_SEALED_SECRET, null) ?: return false
    val secret = box.open(sealed)
    val normalized = code.trim().replace(" ", "")
    val counter = atMillis / 1000L / period
    for (delta in -1..1) {
      if (generate(secret, counter + delta) == normalized) return true
    }
    return false
  }

  /** Current code — useful for showing "it works" during enrollment. */
  fun currentCode(atMillis: Long = System.currentTimeMillis()): String? {
    val sealed = prefs.getString(KEY_SEALED_SECRET, null) ?: return null
    return generate(box.open(sealed), atMillis / 1000L / period)
  }

  private fun generate(secret: ByteArray, counter: Long): String {
    val msg = ByteBuffer.allocate(8).putLong(counter).array()
    val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }
    val hash = mac.doFinal(msg)
    val offset = (hash[hash.size - 1] and 0x0f).toInt()
    val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
      ((hash[offset + 1].toInt() and 0xff) shl 16) or
      ((hash[offset + 2].toInt() and 0xff) shl 8) or
      (hash[offset + 3].toInt() and 0xff)
    val otp = binary % Math.pow(10.0, digits.toDouble()).toInt()
    return otp.toString().padStart(digits, '0')
  }

  private fun base32Encode(data: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val sb = StringBuilder()
    var buffer = 0
    var bitsLeft = 0
    for (b in data) {
      buffer = (buffer shl 8) or (b.toInt() and 0xff)
      bitsLeft += 8
      while (bitsLeft >= 5) {
        val index = (buffer shr (bitsLeft - 5)) and 0x1f
        sb.append(alphabet[index])
        bitsLeft -= 5
      }
    }
    if (bitsLeft > 0) {
      val index = (buffer shl (5 - bitsLeft)) and 0x1f
      sb.append(alphabet[index])
    }
    return sb.toString()
  }

  companion object {
    private const val KEY_SEALED_SECRET = "totp_sealed_secret"
  }
}
