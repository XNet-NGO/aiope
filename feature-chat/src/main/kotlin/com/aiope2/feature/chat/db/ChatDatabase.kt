package com.aiope2.feature.chat.db

import androidx.room.*

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String = "New Chat",
  val agentName: String = "default",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", foreignKeys = [
  ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)
])
data class MessageEntity(
  @PrimaryKey val id: String,
  val conversationId: String,
  val role: String, // user, assistant, system, tool
  val content: String,
  val timestamp: Long = System.currentTimeMillis()
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
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1)
abstract class ChatDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao
}
