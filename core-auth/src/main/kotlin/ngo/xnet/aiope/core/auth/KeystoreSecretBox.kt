package ngo.xnet.aiope.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (e.g. a TOTP seed) with an AES-256-GCM key held in the AndroidKeyStore.
 * The wrapping key never leaves secure hardware. Ciphertext is stored as base64(iv || ct).
 */
class KeystoreSecretBox {

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "aiope_auth_secret_wrap_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
  }

  private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private fun wrapKey(): SecretKey {
    (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  fun seal(plaintext: ByteArray): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
    val iv = cipher.iv
    val ct = cipher.doFinal(plaintext)
    return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
  }

  fun open(sealed: String): ByteArray {
    val blob = Base64.decode(sealed, Base64.NO_WRAP)
    val iv = blob.copyOfRange(0, IV_LENGTH)
    val ct = blob.copyOfRange(IV_LENGTH, blob.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, iv))
    return cipher.doFinal(ct)
  }
}
