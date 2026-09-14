package ngo.xnet.aiope.feature.chat.face

import ngo.xnet.aiope.core.auth.KeystoreSecretBox
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.db.EnrolledFaceEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Data layer for consent-based, on-device face identities.
 *
 * Guardrails:
 *  - Enrollment is explicit and opt-in — an identity exists only if [enroll] was called
 *    for a user who chose to enroll themselves.
 *  - Embeddings are sealed with an AndroidKeyStore AES-256-GCM key ([KeystoreSecretBox])
 *    and stored only as ciphertext; they never leave the device.
 *  - Fully revocable via [delete] / [clear].
 */
class FaceEnrollmentStore(private val dao: ChatDao) {

    private val box = KeystoreSecretBox()

    data class Identity(val id: String, val label: String, val embedding: FloatArray)

    /** Enroll (or update) an identity with a chosen label and its 512-d embedding. */
    suspend fun enroll(label: String, embedding: FloatArray, id: String = UUID.randomUUID().toString()): String {
        val sealed = box.seal(floatsToBytes(embedding))
        dao.upsertEnrolledFace(EnrolledFaceEntity(id = id, label = label.trim(), sealedEmbedding = sealed))
        return id
    }

    /** Decrypt and return all enrolled identities. */
    suspend fun all(): List<Identity> =
        dao.getEnrolledFaces().mapNotNull { e ->
            try {
                Identity(e.id, e.label, bytesToFloats(box.open(e.sealedEmbedding)))
            } catch (_: Throwable) {
                null // corrupt / undecryptable row — skip rather than crash
            }
        }

    /** Enrolled identities as (label, embedding) pairs for matching. May contain multiple
     *  entries per label (multi-angle enrollment). */
    suspend fun labeledEmbeddings(): List<Pair<String, FloatArray>> =
        all().map { it.label to it.embedding }

    /** Distinct enrolled labels with how many angle samples each has. */
    suspend fun labelCounts(): Map<String, Int> =
        dao.getEnrolledFaces().groupingBy { it.label }.eachCount()

    suspend fun delete(id: String) = dao.deleteEnrolledFace(id)

    /** Remove ALL samples for a given label (identity). */
    suspend fun deleteByLabel(label: String) {
        dao.getEnrolledFaces().filter { it.label == label }.forEach { dao.deleteEnrolledFace(it.id) }
    }

    suspend fun clear() = dao.clearEnrolledFaces()

    suspend fun count(): Int = dao.getEnrolledFaces().size

    private fun floatsToBytes(f: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in f) bb.putFloat(x)
        return bb.array()
    }

    private fun bytesToFloats(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = bb.getFloat()
        return out
    }
}
