package com.aiope2.feature.chat.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiope2.feature.chat.db.AgentSeeder
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.ChatDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE messages ADD COLUMN imagePaths TEXT NOT NULL DEFAULT ''")
  }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS memories (key TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'general', createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    )
  }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS providers (id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, isActive INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS tool_toggles (toolId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS mcp_servers (id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS model_cache (builtinId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, cachedAt INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS settings_kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
  }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS agents (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL, model TEXT NOT NULL DEFAULT '', tools TEXT NOT NULL DEFAULT '', maxContext INTEGER NOT NULL DEFAULT 32000, temperature REAL NOT NULL DEFAULT 0.7, topP REAL NOT NULL DEFAULT 0.9, topK INTEGER NOT NULL DEFAULT 0, builtin INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY, agentId TEXT NOT NULL, agentName TEXT NOT NULL, prompt TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'queued', result TEXT NOT NULL DEFAULT '', toolCalls TEXT NOT NULL DEFAULT '', startedAt INTEGER NOT NULL, finishedAt INTEGER, conversationId TEXT, scheduledTaskId TEXT)")
    db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_tasks (id TEXT NOT NULL PRIMARY KEY, agentId TEXT NOT NULL, agentName TEXT NOT NULL, prompt TEXT NOT NULL, cronHour INTEGER NOT NULL DEFAULT -1, cronMinute INTEGER NOT NULL DEFAULT 0, cronDaysOfWeek TEXT NOT NULL DEFAULT '', oneShot INTEGER NOT NULL DEFAULT 0, reportMode TEXT NOT NULL DEFAULT 'notification', conversationId TEXT, enabled INTEGER NOT NULL DEFAULT 1, lastRun INTEGER, nextRun INTEGER, createdAt INTEGER NOT NULL)")
  }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN oneShot INTEGER NOT NULL DEFAULT 0")
  }
}

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext ctx: Context): ChatDatabase {
    val db = Room.databaseBuilder(ctx, ChatDatabase::class.java, "aiope2-chat.db")
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
      .addCallback(object : androidx.room.RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) { /* seeded in open */ }
        override fun onOpen(db: SupportSQLiteDatabase) {
          // Handled after build via coroutine below
        }
      })
      .build()
    CoroutineScope(Dispatchers.IO).launch {
      AgentSeeder.seedIfEmpty(db.chatDao())
    }
    return db
  }

  @Provides
  fun provideDao(db: ChatDatabase): ChatDao = db.chatDao()
}
