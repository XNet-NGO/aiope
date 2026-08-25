package ngo.xnet.aiope.feature.chat.di

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ngo.xnet.aiope.feature.chat.db.AgentSeeder
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.db.ChatDatabase
import ngo.xnet.aiope.feature.chat.engine.AgentDb
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

val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN tools TEXT NOT NULL DEFAULT ''")
  }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // Recreate scheduled_tasks with v8 schedule model (once|interval|daily|weekly|monthly)
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS scheduled_tasks_new (" +
        "id TEXT NOT NULL PRIMARY KEY, agentId TEXT NOT NULL, agentName TEXT NOT NULL, " +
        "prompt TEXT NOT NULL, tools TEXT NOT NULL DEFAULT '', " +
        "scheduleType TEXT NOT NULL DEFAULT 'once', intervalValue INTEGER NOT NULL DEFAULT 0, " +
        "intervalUnit TEXT NOT NULL DEFAULT 'min', timeHour INTEGER NOT NULL DEFAULT 0, " +
        "timeMinute INTEGER NOT NULL DEFAULT 0, daysOfWeek TEXT NOT NULL DEFAULT '', " +
        "dayOfMonth INTEGER NOT NULL DEFAULT 1, maxRuns INTEGER NOT NULL DEFAULT 0, " +
        "runsCompleted INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'scheduled', " +
        "reportMode TEXT NOT NULL DEFAULT 'notification', conversationId TEXT, " +
        "enabled INTEGER NOT NULL DEFAULT 1, lastRun INTEGER, nextRun INTEGER, createdAt INTEGER NOT NULL)",
    )
    // Map legacy cron model -> v8 schedule model
    db.execSQL(
      "INSERT INTO scheduled_tasks_new (id, agentId, agentName, prompt, tools, scheduleType, " +
        "intervalValue, intervalUnit, timeHour, timeMinute, daysOfWeek, dayOfMonth, maxRuns, " +
        "runsCompleted, status, reportMode, conversationId, enabled, lastRun, nextRun, createdAt) " +
        "SELECT id, agentId, agentName, prompt, COALESCE(tools, ''), " +
        "CASE WHEN oneShot = 1 THEN 'once' WHEN cronHour = -1 THEN 'interval' " +
        "WHEN COALESCE(cronDaysOfWeek, '') <> '' THEN 'weekly' ELSE 'daily' END, " +
        "CASE WHEN cronHour = -1 THEN 60 ELSE 0 END, 'min', " +
        "CASE WHEN cronHour >= 0 THEN cronHour ELSE 0 END, COALESCE(cronMinute, 0), " +
        "COALESCE(cronDaysOfWeek, ''), 1, 0, 0, 'scheduled', COALESCE(reportMode, 'notification'), " +
        "conversationId, enabled, lastRun, NULL, createdAt FROM scheduled_tasks",
    )
    db.execSQL("DROP TABLE scheduled_tasks")
    db.execSQL("ALTER TABLE scheduled_tasks_new RENAME TO scheduled_tasks")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS task_runs (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT, scheduledTaskId TEXT NOT NULL, " +
        "runNumber INTEGER NOT NULL, timestamp INTEGER NOT NULL, " +
        "prompt TEXT NOT NULL DEFAULT '', output TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'finished')",
    )
  }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE messages ADD COLUMN inputTokens INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE messages ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE messages ADD COLUMN latencyMs INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE messages ADD COLUMN modelUsed TEXT NOT NULL DEFAULT ''")
  }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS goals (" +
        "id TEXT NOT NULL PRIMARY KEY, " +
        "title TEXT NOT NULL, " +
        "detail TEXT NOT NULL DEFAULT '', " +
        "status TEXT NOT NULL DEFAULT 'active', " +
        "progress INTEGER NOT NULL DEFAULT 0, " +
        "createdAt INTEGER NOT NULL, " +
        "updatedAt INTEGER NOT NULL)",
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext ctx: Context): ChatDatabase {
    val db = AgentDb.get(ctx)
    CoroutineScope(Dispatchers.IO).launch {
      AgentSeeder.seedIfEmpty(db.chatDao())
    }
    return db
  }

  @Provides
  fun provideDao(db: ChatDatabase): ChatDao = db.chatDao()
}
