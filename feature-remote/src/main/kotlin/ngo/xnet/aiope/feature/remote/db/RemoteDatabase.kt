package ngo.xnet.aiope.feature.remote.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "remote_servers")
data class RemoteServerEntity(
  @PrimaryKey val id: String,
  val name: String,
  val host: String,
  val port: Int = 2222,
  val user: String,
  val bootstrapPort: Int = 22,
  val privateKey: String? = null,
  val publicKey: String? = null,
  val status: String = "offline",
  val lastSeen: Long = 0,
  val osInfo: String? = null,
  val daemonVersion: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface RemoteServerDao {
  @Query("SELECT * FROM remote_servers ORDER BY name")
  fun getAll(): Flow<List<RemoteServerEntity>>

  @Query("SELECT * FROM remote_servers")
  suspend fun getAllOnce(): List<RemoteServerEntity>

  @Query("SELECT * FROM remote_servers WHERE id = :id")
  suspend fun getById(id: String): RemoteServerEntity?

  @Query("SELECT * FROM remote_servers WHERE name = :name COLLATE NOCASE LIMIT 1")
  suspend fun getByName(name: String): RemoteServerEntity?

  @Upsert
  suspend fun upsert(server: RemoteServerEntity)

  @Delete
  suspend fun delete(server: RemoteServerEntity)

  @Query("UPDATE remote_servers SET status = :status, lastSeen = :time WHERE id = :id")
  suspend fun updateStatus(id: String, status: String, time: Long = System.currentTimeMillis())

  @Query("UPDATE remote_servers SET osInfo = :osInfo, daemonVersion = :version WHERE id = :id")
  suspend fun updateHealth(id: String, osInfo: String?, version: String?)

  @Query("DELETE FROM remote_servers WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Database(
  entities = [RemoteServerEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class RemoteDatabase : RoomDatabase() {
  abstract fun remoteServerDao(): RemoteServerDao
}
