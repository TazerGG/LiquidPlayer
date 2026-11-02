package com.liquidplayer.data

import com.liquidplayer.data.database.FolderRow
import com.liquidplayer.data.database.PlaylistRow
import com.liquidplayer.data.database.SongEntity
import com.liquidplayer.domain.model.MusicFolder
import com.liquidplayer.domain.model.Playlist
import com.liquidplayer.domain.model.Song

fun SongEntity.toDomain() = Song(
    id = id, uri = uri, folderId = folderId, fileName = fileName, title = title, artist = artist,
    album = album, durationMs = durationMs, mimeType = mimeType, artworkPath = artworkPath,
    lyricsUri = lyricsUri, trackNumber = trackNumber, isAvailable = isAvailable, addedAt = addedAt,
)

fun FolderRow.toDomain() = MusicFolder(
    id = folder.id, treeUri = folder.treeUri, name = folder.name, addedAt = folder.addedAt,
    lastScanAt = folder.lastScanAt, accessible = folder.accessible, songCount = songCount,
)

fun PlaylistRow.toDomain() = Playlist(
    id = playlist.id, name = playlist.name, coverPath = playlist.coverPath ?: firstArtwork,
    songCount = songCount, totalDurationMs = totalDuration, sourceFolderId = playlist.sourceFolderId,
    updatedAt = playlist.updatedAt,
)
