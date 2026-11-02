package com.liquidplayer.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class, SongEntity::class, FolderSongEntity::class, PlaylistEntity::class,
        PlaylistSongEntity::class, FavoriteEntity::class, PlayHistoryEntity::class,
        QueueItemEntity::class, PreferenceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun folderDao(): FolderDao
    abstract fun folderSongDao(): FolderSongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun queueDao(): QueueDao
    abstract fun preferenceDao(): PreferenceDao
}
