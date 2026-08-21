package ngo.xnet.aiope.feature.remote.ssh

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.EdECPublicKey

/**
 * Generates Ed25519 SSH keypairs for daemon authentication.
 */
object KeyGen {

  /** Returns (privateKeyPem, publicKeyOpenSsh) */
  fun generate(): Pair<String, String> {
    val kpg = KeyPairGenerator.getInstance("Ed25519")
    val kp = kpg.generateKeyPair()
    val privPem = encodePkcs8Pem(kp.private.encoded)
    val pubSsh = encodeOpenSshPublic(kp.public as EdECPublicKey)
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

  private fun encodeOpenSshPublic(pub: EdECPublicKey): String {
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

  private fun intToBytes(v: Int) = byteArrayOf(
    (v shr 24).toByte(),
    (v shr 16).toByte(),
    (v shr 8).toByte(),
    v.toByte(),
  )
}
