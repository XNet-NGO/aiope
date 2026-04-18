package com.aiope2.feature.chat.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.ChatDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext ctx: Context): ChatDatabase = Room.databaseBuilder(ctx, ChatDatabase::class.java, "aiope2-chat.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    .build()

  @Provides
  fun provideDao(db: ChatDatabase): ChatDao = db.chatDao()
}
