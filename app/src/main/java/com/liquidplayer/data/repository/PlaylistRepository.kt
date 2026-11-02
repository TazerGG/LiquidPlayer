package com.liquidplayer.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.liquidplayer.data.database.AppDatabase
import com.liquidplayer.data.database.PlaylistDao
import com.liquidplayer.data.database.PlaylistEntity
import com.liquidplayer.data.database.PlaylistSongEntity
import com.liquidplayer.data.local.CoverCache
import com.liquidplayer.data.toDomain
import com.liquidplayer.domain.model.Playlist
import com.liquidplayer.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: PlaylistDao,
    private val coverCache: CoverCache,
) {
    fun observePlaylists(): Flow<List<Playlist>> = dao.observeRows().map { rows -> rows.map { it.toDomain() } }
    fun observePlaylist(id: Long): Flow<Playlist?> = dao.observeRow(id).map { it?.toDomain() }
    fun observeSongs(id: Long): Flow<List<Song>> = dao.observeSongs(id).map { l -> l.map { it.toDomain() } }
    suspend fun getSongs(id: Long): List<Song> = dao.getSongs(id).map { it.toDomain() }

    suspend fun create(name: String, coverUri: Uri? = null, sourceFolderId: Long? = null): Long {
        val cover = coverUri?.let { withContext(Dispatchers.IO) { coverCache.savePlaylistCover(it) } }
        val now = System.currentTimeMillis()
        return dao.insertPlaylist(
            PlaylistEntity(name = name.trim(), coverPath = cover, createdAt = now, updatedAt = now, sourceFolderId = sourceFolderId),
        )
    }

    /** Adiciona sem duplicar. Retorna quantas músicas realmente entraram. */
    suspend fun addSongs(playlistId: Long, songIds: List<Long>): Int = db.withTransaction {
        val present = dao.songIds(playlistId).toHashSet()
        val newIds = songIds.distinct().filter { it !in present }
        if (newIds.isEmpty()) return@withTransaction 0
        var position = dao.maxPosition(playlistId)
        val now = System.currentTimeMillis()
        dao.insertCrossRefs(newIds.map { PlaylistSongEntity(playlistId, it, ++position, now) })
        dao.touch(playlistId, now)
        newIds.size
    }

    suspend fun removeSong(playlistId: Long, songId: Long) {
        dao.removeSong(playlistId, songId)
        dao.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun reorder(playlistId: Long, orderedSongIds: List<Long>) = db.withTransaction {
        orderedSongIds.forEachIndexed { index, id -> dao.setPosition(playlistId, id, index) }
        dao.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim(), System.currentTimeMillis())

    suspend fun setCover(id: Long, uri: Uri?) {
        val old = dao.getById(id)?.coverPath
        val path = uri?.let { withContext(Dispatchers.IO) { coverCache.savePlaylistCover(it) } }
        dao.setCover(id, path, System.currentTimeMillis())
        withContext(Dispatchers.IO) { coverCache.deletePlaylistCover(old) }
    }

    suspend fun delete(id: Long) {
        val old = dao.getById(id)?.coverPath
        dao.delete(id)
        withContext(Dispatchers.IO) { coverCache.deletePlaylistCover(old) }
    }
}
