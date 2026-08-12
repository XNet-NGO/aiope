package ngo.xnet.aiope.feature.chat.engine

import android.content.Context
import androidx.room.Room
import ngo.xnet.aiope.feature.chat.db.ChatDatabase
import ngo.xnet.aiope.feature.chat.di.MIGRATION_1_2
import ngo.xnet.aiope.feature.chat.di.MIGRATION_2_3
import ngo.xnet.aiope.feature.chat.di.MIGRATION_3_4
import ngo.xnet.aiope.feature.chat.di.MIGRATION_4_5
import ngo.xnet.aiope.feature.chat.di.MIGRATION_5_6
import ngo.xnet.aiope.feature.chat.di.MIGRATION_6_7
import ngo.xnet.aiope.feature.chat.di.MIGRATION_7_8
import kotlin.jvm.Volatile

/**
 * Process-wide Room instance shared by scheduled-task workers and Hilt.
 *
 * Workers (constructed by WorkManager) previously opened their own Room
 * instance per run; several agents firing at once meant multiple instances
 * on the same SQLite file, risking "database is locked" races. Everyone
 * (workers, reschedule worker, and [ngo.xnet.aiope.feature.chat.di.ChatModule])
 * now resolves the single instance from here. Never call [ChatDatabase.close].
 */
object AgentDb {
  @Volatile private var instance: ChatDatabase? = null

  fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
    instance ?: Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, "aiope-chat.db")
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
      .build()
      .also { instance = it }
  }
}
