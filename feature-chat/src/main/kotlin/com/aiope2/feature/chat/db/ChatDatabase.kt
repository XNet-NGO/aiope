package com.aiope2.feature.chat.db

import androidx.room.*

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String = "New Chat",
  val agentName: String = "default",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "messages",
  foreignKeys = [
    ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
  ],
)
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val role: String, val content: String, val imagePaths: String = "", val timestamp: Long = System.currentTimeMillis())

@Entity(tableName = "memories")
data class MemoryEntity(
  @PrimaryKey val key: String,
  val content: String,
  val category: String = "general",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "providers")
data class ProviderEntity(
  @PrimaryKey val id: String,
  val json: String, // Full ProviderProfile JSON
  val isActive: Boolean = false,
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tool_toggles")
data class ToolToggleEntity(
  @PrimaryKey val toolId: String,
  val enabled: Boolean,
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
  @PrimaryKey val id: String,
  val json: String, // Full McpServerConfig JSON
)

@Entity(tableName = "model_cache")
data class ModelCacheEntity(
  @PrimaryKey val builtinId: String,
  val json: String, // JSONArray of ModelDef
  val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "settings_kv")
data class SettingsKvEntity(
  @PrimaryKey val key: String,
  val value: String,
)

@Dao
interface ChatDao {
  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  suspend fun getConversations(): List<ConversationEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertConversation(conversation: ConversationEntity)

  @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
  suspend fun getMessages(convId: String): List<MessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessage(message: MessageEntity)

  @Query("UPDATE messages SET content = :content WHERE id = :id")
  suspend fun updateMessageContent(id: String, content: String)

  @Query("UPDATE conversations SET updatedAt = :time, title = :title WHERE id = :id")
  suspend fun updateConversation(id: String, title: String, time: Long = System.currentTimeMillis())

  @Query("DELETE FROM messages WHERE conversationId = :convId AND timestamp >= :afterTimestamp")
  suspend fun deleteMessagesAfter(convId: String, afterTimestamp: Long)

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun deleteConversation(id: String)

  // Memory
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMemory(memory: MemoryEntity)

  @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
  suspend fun getAllMemories(): List<MemoryEntity>

  @Query("SELECT * FROM memories WHERE key LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
  suspend fun searchMemories(query: String): List<MemoryEntity>

  @Query("DELETE FROM memories WHERE key = :key")
  suspend fun deleteMemory(key: String)

  // Providers
  @Query("SELECT * FROM providers ORDER BY updatedAt DESC")
  suspend fun getProviders(): List<ProviderEntity>

  @Query("SELECT * FROM providers WHERE isActive = 1 LIMIT 1")
  suspend fun getActiveProvider(): ProviderEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertProvider(provider: ProviderEntity)

  @Query("UPDATE providers SET isActive = 0")
  suspend fun clearActiveProvider()

  @Query("UPDATE providers SET isActive = 1 WHERE id = :id")
  suspend fun setActiveProvider(id: String)

  @Query("DELETE FROM providers WHERE id = :id")
  suspend fun deleteProvider(id: String)

  // Tool toggles
  @Query("SELECT * FROM tool_toggles WHERE toolId = :toolId")
  suspend fun getToolToggle(toolId: String): ToolToggleEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertToolToggle(toggle: ToolToggleEntity)

  // MCP servers
  @Query("SELECT * FROM mcp_servers")
  suspend fun getMcpServers(): List<McpServerEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMcpServer(server: McpServerEntity)

  @Query("DELETE FROM mcp_servers WHERE id = :id")
  suspend fun deleteMcpServer(id: String)

  @Query("DELETE FROM mcp_servers")
  suspend fun deleteAllMcpServers()

  // Model cache
  @Query("SELECT * FROM model_cache WHERE builtinId = :builtinId")
  suspend fun getModelCache(builtinId: String): ModelCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertModelCache(cache: ModelCacheEntity)

  // Settings KV
  @Query("SELECT value FROM settings_kv WHERE key = :key")
  suspend fun getSetting(key: String): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSetting(setting: SettingsKvEntity)
}

@Database(
  entities = [
    ConversationEntity::class, MessageEntity::class, MemoryEntity::class,
    ProviderEntity::class, ToolToggleEntity::class, McpServerEntity::class,
    ModelCacheEntity::class, SettingsKvEntity::class,
  ],
  version = 4,
)
abstract class ChatDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao
}
