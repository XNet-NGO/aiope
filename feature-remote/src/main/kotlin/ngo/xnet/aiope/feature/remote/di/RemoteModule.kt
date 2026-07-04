package ngo.xnet.aiope.feature.remote.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ngo.xnet.aiope.core.model.RemoteToolBridge
import ngo.xnet.aiope.feature.remote.db.RemoteDatabase
import ngo.xnet.aiope.feature.remote.db.RemoteServerDao
import ngo.xnet.aiope.feature.remote.ssh.SshSessionManager
import ngo.xnet.aiope.feature.remote.tools.RemoteToolProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE remote_servers ADD COLUMN privateKey TEXT")
    db.execSQL("ALTER TABLE remote_servers ADD COLUMN publicKey TEXT")
  }
}

@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

  @Provides
  @Singleton
  fun provideRemoteDatabase(@ApplicationContext context: Context): RemoteDatabase = Room.databaseBuilder(context, RemoteDatabase::class.java, "aiope_remote.db")
    .addMigrations(MIGRATION_1_2)
    .fallbackToDestructiveMigration()
    .build()

  @Provides
  fun provideRemoteServerDao(db: RemoteDatabase): RemoteServerDao = db.remoteServerDao()

  @Provides
  @Singleton
  fun provideSshSessionManager(): SshSessionManager = SshSessionManager()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteBindsModule {
  @Binds
  @Singleton
  abstract fun bindRemoteToolBridge(impl: RemoteToolProvider): RemoteToolBridge
}
