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

@Entity(tableName = "agents")
data class AgentEntity(
  @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
  val name: String,
  val prompt: String,
  val model: String = "", // empty = use active
  val tools: String = "", // comma-separated tool names
  val maxContext: Int = 32000,
  val temperature: Float = 0.7f,
  val topP: Float = 0.9f,
  val topK: Int = 0,
  val builtin: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
  @PrimaryKey val id: String,
  val agentId: String,
  val agentName: String,
  val prompt: String,
  val status: String = "queued", // queued, running, finished, failed
  val result: String = "",
  val toolCalls: String = "", // JSON array
  val startedAt: Long = System.currentTimeMillis(),
  val finishedAt: Long? = null,
  val conversationId: String? = null,
  val scheduledTaskId: String? = null,
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
  @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
  val agentId: String,
  val agentName: String,
  val prompt: String,
  val cronHour: Int = -1, // -1 = every hour
  val cronMinute: Int = 0,
  val cronDaysOfWeek: String = "", // empty = every day, "1,2,3,4,5" = weekdays
  val oneShot: Boolean = false, // true = run once then auto-disable
  val reportMode: String = "notification", // notification, conversation, both
  val conversationId: String? = null,
  val enabled: Boolean = true,
  val lastRun: Long? = null,
  val nextRun: Long? = null,
  val createdAt: Long = System.currentTimeMillis(),
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

  @Query("SELECT * FROM tool_toggles")
  suspend fun getToolToggles(): List<ToolToggleEntity>

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

  @Query("SELECT * FROM settings_kv WHERE key LIKE :prefix || '%'")
  suspend fun getSettingsByPrefix(prefix: String): List<SettingsKvEntity>

  @Query("DELETE FROM providers")
  suspend fun deleteAllProviders()

  // Agents
  @Query("SELECT * FROM agents ORDER BY builtin DESC, name ASC")
  suspend fun getAgents(): List<AgentEntity>

  @Query("SELECT * FROM agents WHERE id = :id")
  suspend fun getAgent(id: String): AgentEntity?

  @Query("SELECT * FROM agents WHERE name = :name LIMIT 1")
  suspend fun getAgentByName(name: String): AgentEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAgent(agent: AgentEntity)

  @Query("DELETE FROM agents WHERE id = :id AND builtin = 0")
  suspend fun deleteAgent(id: String)

  // Agent Tasks
  @Query("SELECT * FROM agent_tasks ORDER BY startedAt DESC LIMIT :limit")
  suspend fun getAgentTasks(limit: Int = 50): List<AgentTaskEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAgentTask(task: AgentTaskEntity)

  @Query("UPDATE agent_tasks SET status = :status, result = :result, finishedAt = :finishedAt WHERE id = :id")
  suspend fun updateAgentTask(id: String, status: String, result: String, finishedAt: Long? = System.currentTimeMillis())

  // Scheduled Tasks
  @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
  suspend fun getScheduledTasks(): List<ScheduledTaskEntity>

  @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND (nextRun IS NULL OR nextRun <= :now)")
  suspend fun getDueScheduledTasks(now: Long = System.currentTimeMillis()): List<ScheduledTaskEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertScheduledTask(task: ScheduledTaskEntity)

  @Query("UPDATE scheduled_tasks SET lastRun = :lastRun, nextRun = :nextRun WHERE id = :id")
  suspend fun updateScheduledTaskRun(id: String, lastRun: Long, nextRun: Long?)

  @Query("DELETE FROM scheduled_tasks WHERE id = :id")
  suspend fun deleteScheduledTask(id: String)
}

@Database(
  entities = [
    ConversationEntity::class, MessageEntity::class, MemoryEntity::class,
    ProviderEntity::class, ToolToggleEntity::class, McpServerEntity::class,
    ModelCacheEntity::class, SettingsKvEntity::class,
    AgentEntity::class, AgentTaskEntity::class, ScheduledTaskEntity::class,
  ],
  version = 6,
)
abstract class ChatDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao
}
