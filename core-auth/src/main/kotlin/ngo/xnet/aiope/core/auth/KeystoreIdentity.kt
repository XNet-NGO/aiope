package ngo.xnet.aiope.core.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Manages the account's device-bound identity key: an EC P-256 keypair generated in the
 * AndroidKeyStore (StrongBox-backed when the device supports it). The public key + a stable
 * account id form the "user record" that a future gateway RP would register and verify.
 *
 * The private key never leaves the secure hardware; we only ever get a handle to sign with it.
 */
class KeystoreIdentity {

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "aiope_account_identity_ec_p256"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
  }

  private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  /** True if the identity keypair already exists. */
  fun exists(): Boolean = keyStore.containsAlias(KEY_ALIAS)

  /**
   * Ensure the identity keypair exists, creating it if needed.
   *
   * @param requireUserAuth when true, the private key can only be used after a successful
   *   biometric/device-credential auth (ties the key to user presence). We keep this false for
   *   the base identity so it works before any factor is enrolled; factor-specific keys can opt in.
   * @param useStrongBox request StrongBox; automatically falls back if unsupported.
   */
  fun ensureKeyPair(requireUserAuth: Boolean = false, useStrongBox: Boolean = true): KeyPair {
    if (!exists()) generateKeyPair(requireUserAuth, useStrongBox)
    val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
    val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
    return KeyPair(publicKey, privateKey)
  }

  fun publicKey(): PublicKey? =
    if (exists()) keyStore.getCertificate(KEY_ALIAS).publicKey else null

  /** X.509 SubjectPublicKeyInfo, base64 — the form a server RP would store. */
  fun publicKeyBase64(): String? =
    publicKey()?.let { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }

  /** Sign [data] with the identity private key (SHA256withECDSA). */
  fun sign(data: ByteArray): ByteArray {
    val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
    return Signature.getInstance(SIGNATURE_ALGORITHM).run {
      initSign(privateKey)
      update(data)
      sign()
    }
  }

  fun verify(data: ByteArray, signature: ByteArray): Boolean {
    val pub = publicKey() ?: return false
    return Signature.getInstance(SIGNATURE_ALGORITHM).run {
      initVerify(pub)
      update(data)
      verify(signature)
    }
  }

  fun delete() {
    if (exists()) keyStore.deleteEntry(KEY_ALIAS)
  }

  private fun generateKeyPair(requireUserAuth: Boolean, useStrongBox: Boolean) {
    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
    try {
      generator.initialize(buildSpec(requireUserAuth, useStrongBox))
      generator.generateKeyPair()
    } catch (e: Exception) {
      // StrongBox may be advertised but reject the spec on some devices; retry without it.
      if (useStrongBox) {
        generator.initialize(buildSpec(requireUserAuth, useStrongBox = false))
        generator.generateKeyPair()
      } else {
        throw e
      }
    }
  }

  private fun buildSpec(requireUserAuth: Boolean, useStrongBox: Boolean): KeyGenParameterSpec {
    val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
      .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
    if (requireUserAuth) {
      builder.setUserAuthenticationRequired(true)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        builder.setInvalidatedByBiometricEnrollment(true)
      }
    }
    if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      builder.setIsStrongBoxBacked(true)
    }
    return builder.build()
  }
}
