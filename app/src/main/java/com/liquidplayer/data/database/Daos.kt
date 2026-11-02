package com.liquidplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE folderId = :folderId")
    suspend fun getByFolder(folderId: Long): List<SongEntity>

    @Query("SELECT * FROM songs WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<SongEntity>): List<Long>

    @Update
    suspend fun update(song: SongEntity)

    @Query("UPDATE songs SET isAvailable = :available WHERE id IN (:ids)")
    suspend fun setAvailable(ids: List<Long>, available: Boolean)

    @Query("UPDATE songs SET lyricsUri = :uri WHERE id = :id")
    suspend fun setLyricsUri(id: Long, uri: String?)

    @Query("DELETE FROM songs WHERE isAvailable = 0")
    suspend fun deleteUnavailable(): Int

    @Query("SELECT COUNT(*) FROM songs WHERE isAvailable = 0")
    fun observeUnavailableCount(): Flow<Int>

    @Query("SELECT artworkPath FROM songs WHERE artworkPath IS NOT NULL")
    suspend fun allArtworkPaths(): List<String>
}

@Dao
interface FolderDao {
    @Query(
        "SELECT f.*, (SELECT COUNT(*) FROM folder_songs fs JOIN songs s ON s.id = fs.songId " +
            "WHERE fs.folderId = f.id AND s.isAvailable = 1) AS songCount " +
            "FROM folders f ORDER BY f.name COLLATE NOCASE",
    )
    fun observeAll(): Flow<List<FolderRow>>

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE treeUri = :uri")
    suspend fun getByUri(uri: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FolderSongDao {
    @Query("DELETE FROM folder_songs WHERE folderId = :folderId")
    suspend fun clear(folderId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<FolderSongEntity>)

    @Query(
        "SELECT s.*, fs.relativePath AS relativePath FROM songs s " +
            "JOIN folder_songs fs ON fs.songId = s.id WHERE fs.folderId = :folderId AND s.isAvailable = 1",
    )
    suspend fun getMembers(folderId: Long): List<MemberRow>
}

private const val PLAYLIST_ROW_SQL =
    "SELECT p.*, COUNT(s.id) AS songCount, COALESCE(SUM(s.durationMs), 0) AS totalDuration, " +
        "(SELECT s2.artworkPath FROM playlist_songs ps2 JOIN songs s2 ON s2.id = ps2.songId " +
        "WHERE ps2.playlistId = p.id AND s2.artworkPath IS NOT NULL ORDER BY ps2.position LIMIT 1) AS firstArtwork " +
        "FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlistId = p.id " +
        "LEFT JOIN songs s ON s.id = ps.songId "

@Dao
interface PlaylistDao {
    @Query(PLAYLIST_ROW_SQL + "GROUP BY p.id ORDER BY p.updatedAt DESC")
    fun observeRows(): Flow<List<PlaylistRow>>

    @Query(PLAYLIST_ROW_SQL + "WHERE p.id = :id GROUP BY p.id")
    fun observeRow(id: Long): Flow<PlaylistRow?>

    @Query(
        "SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id " +
            "WHERE ps.playlistId = :id ORDER BY ps.position",
    )
    fun observeSongs(id: Long): Flow<List<SongEntity>>

    @Query(
        "SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id " +
            "WHERE ps.playlistId = :id ORDER BY ps.position",
    )
    suspend fun getSongs(id: Long): List<SongEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<PlaylistSongEntity>): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :id")
    suspend fun songIds(id: Long): List<Long>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun setPosition(playlistId: Long, songId: Long, position: Int)

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET coverPath = :path, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, path: String?, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE sourceFolderId = :folderId AND name = :name LIMIT 1")
    suspend fun findFromFolder(folderId: Long, name: String): PlaylistEntity?
}

@Dao
interface FavoriteDao {
    @Query("SELECT songId FROM favorites")
    fun observeIds(): Flow<List<Long>>

    @Query("SELECT s.*, f.addedAt AS favAddedAt FROM songs s JOIN favorites f ON f.songId = s.id")
    fun observeSongs(): Flow<List<FavoriteRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun remove(songId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Query(
        "SELECT s.* FROM songs s JOIN (SELECT songId, MAX(playedAt) AS last FROM play_history GROUP BY songId) h " +
            "ON h.songId = s.id WHERE s.isAvailable = 1 ORDER BY h.last DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<SongEntity>>

    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY playedAt DESC LIMIT 500)")
    suspend fun trim()

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun getAll(): List<QueueItemEntity>

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences")
    fun observeAll(): Flow<List<PreferenceEntity>>

    @Query("SELECT prefValue FROM preferences WHERE prefKey = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: PreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<PreferenceEntity>)
}
