package com.liquidplayer.data.repository

import com.liquidplayer.data.database.FavoriteDao
import com.liquidplayer.data.database.FavoriteEntity
import com.liquidplayer.data.database.HistoryDao
import com.liquidplayer.data.database.PlayHistoryEntity
import com.liquidplayer.data.toDomain
import com.liquidplayer.domain.model.FavoriteSong
import com.liquidplayer.domain.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(private val dao: FavoriteDao) {
    fun observeIds(): Flow<Set<Long>> = dao.observeIds().map { it.toHashSet() }
    fun observeSongs(): Flow<List<FavoriteSong>> =
        dao.observeSongs().map { rows -> rows.map { FavoriteSong(it.song.toDomain(), it.favAddedAt) } }

    suspend fun toggle(songId: Long) {
        if (dao.isFavorite(songId)) dao.remove(songId) else dao.add(FavoriteEntity(songId, System.currentTimeMillis()))
    }
}

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {
    fun observeRecent(limit: Int = 20): Flow<List<Song>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun record(songId: Long) {
        dao.insert(PlayHistoryEntity(songId = songId, playedAt = System.currentTimeMillis()))
        dao.trim()
    }

    suspend fun clear() = dao.clear()
}
