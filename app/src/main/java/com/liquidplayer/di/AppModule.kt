package com.liquidplayer.di

import android.content.Context
import androidx.room.Room
import com.liquidplayer.data.database.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "liquid_player.db").build()

    @Provides fun songDao(db: AppDatabase): SongDao = db.songDao()
    @Provides fun folderDao(db: AppDatabase): FolderDao = db.folderDao()
    @Provides fun folderSongDao(db: AppDatabase): FolderSongDao = db.folderSongDao()
    @Provides fun playlistDao(db: AppDatabase): PlaylistDao = db.playlistDao()
    @Provides fun favoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun historyDao(db: AppDatabase): HistoryDao = db.historyDao()
    @Provides fun queueDao(db: AppDatabase): QueueDao = db.queueDao()
    @Provides fun preferenceDao(db: AppDatabase): PreferenceDao = db.preferenceDao()

    /** Escopo de vida do app (varreduras e controle do player sobrevivem à navegação entre telas). */
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
