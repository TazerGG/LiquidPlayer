package com.liquidplayer.data.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders", indices = [Index(value = ["treeUri"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val name: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
    val accessible: Boolean = true,
)

/** Metadados da música. O arquivo de áudio nunca é copiado: guardamos apenas a URI do SAF. */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["uri"], unique = true), Index("fingerprint"), Index("folderId")],
    foreignKeys = [ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.CASCADE)],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val folderId: Long,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val artworkPath: String?,
    val lyricsUri: String?,
    val trackNumber: Int,
    val fingerprint: String,
    val isAvailable: Boolean = true,
    val addedAt: Long,
)

/** Associação pasta ↔ música (inclui duplicatas que apontam para a música "canônica"). */
@Entity(
    tableName = "folder_songs",
    primaryKeys = ["folderId", "songId"],
    indices = [Index("songId")],
    foreignKeys = [
        ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FolderSongEntity(val folderId: Long, val songId: Long, val relativePath: String)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceFolderId: Long? = null,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("songId")],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistSongEntity(val playlistId: Long, val songId: Long, val position: Int, val addedAt: Long)

@Entity(
    tableName = "favorites",
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
)
data class FavoriteEntity(@PrimaryKey val songId: Long, val addedAt: Long)

@Entity(
    tableName = "play_history",
    indices = [Index("songId"), Index("playedAt")],
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
)

@Entity(
    tableName = "queue_items",
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("songId")],
)
data class QueueItemEntity(@PrimaryKey val position: Int, val songId: Long)

@Entity(tableName = "preferences")
data class PreferenceEntity(@PrimaryKey val prefKey: String, val prefValue: String)

// ---- Projeções ----
data class FolderRow(@Embedded val folder: FolderEntity, val songCount: Int)
data class MemberRow(@Embedded val song: SongEntity, val relativePath: String)
data class FavoriteRow(@Embedded val song: SongEntity, val favAddedAt: Long)
data class PlaylistRow(
    @Embedded val playlist: PlaylistEntity,
    val songCount: Int,
    val totalDuration: Long,
    val firstArtwork: String?,
)
