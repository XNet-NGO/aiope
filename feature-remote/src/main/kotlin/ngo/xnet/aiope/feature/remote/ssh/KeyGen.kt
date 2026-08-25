package ngo.xnet.aiope.feature.remote.ssh

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Generates SSH keypairs for daemon authentication.
 * Tries Ed25519 first (API 33+), falls back to RSA 4096.
 */
object KeyGen {

  /** Returns (privateKeyPem, publicKeyOpenSsh) */
  fun generate(): Pair<String, String> = try {
    generateEd25519()
  } catch (e: Exception) {
    android.util.Log.w("KeyGen", "Ed25519 failed (${e.javaClass.simpleName}): ${e.message}")
    generateRsa()
  }
  }

  private fun generateEd25519(): Pair<String, String> {
    val kpg = KeyPairGenerator.getInstance("Ed25519")
    val kp = kpg.generateKeyPair()
    val privPem = encodePkcs8Pem(kp.private.encoded)
    val pub = kp.public as EdECPublicKey
    val pubSsh = encodeOpenSshEd25519(pub)
    return privPem to pubSsh
  }

  private fun generateRsa(): Pair<String, String> {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(4096)
    val kp = kpg.generateKeyPair()
    val privPem = encodePkcs8Pem(kp.private.encoded)
    val pub = kp.public as RSAPublicKey
    val pubSsh = encodeOpenSshRsa(pub)
    return privPem to pubSsh
  }

  private fun encodePkcs8Pem(encoded: ByteArray): String {
    val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
    val sb = StringBuilder()
    sb.append("-----BEGIN PRIVATE KEY-----\n")
    b64.chunked(64).forEach { sb.append(it).append("\n") }
    sb.append("-----END PRIVATE KEY-----")
    return sb.toString()
  }

  private fun encodeOpenSshEd25519(pub: EdECPublicKey): String {
    val point = pub.point
    val yBytes = point.y.toByteArray()
    val keyBytes = ByteArray(32)
    for (i in 0 until minOf(yBytes.size, 32)) {
      keyBytes[i] = yBytes[yBytes.size - 1 - i]
    }
    if (point.isXOdd) keyBytes[31] = (keyBytes[31].toInt() or 0x80).toByte()

    val typeStr = "ssh-ed25519"
    val blob = ByteArrayOutputStream()
    blob.write(intToBytes(typeStr.length))
    blob.write(typeStr.toByteArray())
    blob.write(intToBytes(keyBytes.size))
    blob.write(keyBytes)
    return "$typeStr ${Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)} aiope@device"
  }

  private fun encodeOpenSshRsa(pub: RSAPublicKey): String {
    val typeStr = "ssh-rsa"
    val e = pub.publicExponent.toByteArray()
    val n = pub.modulus.toByteArray()
    val blob = ByteArrayOutputStream()
    blob.write(intToBytes(typeStr.length))
    blob.write(typeStr.toByteArray())
    blob.write(intToBytes(e.size))
    blob.write(e)
    blob.write(intToBytes(n.size))
    blob.write(n)
    return "$typeStr ${Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)} aiope@device"
  }

  private fun intToBytes(v: Int) = byteArrayOf(
    (v shr 24).toByte(),
    (v shr 16).toByte(),
    (v shr 8).toByte(),
    v.toByte(),
  )
}
