package com.liquidplayer.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.liquidplayer.domain.model.RepeatMode
import com.liquidplayer.domain.model.Song
import java.io.File

/** Uma posição da fila. [queued] = adicionada manualmente pelo usuário (toca antes do restante do contexto). */
data class QueueEntry(val key: String, val song: Song, val queued: Boolean)

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val queue: List<Song> = emptyList(),
    val entries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val connected: Boolean = false,
)

internal const val EXTRA_DURATION = "dur"

/** O mediaId é o id da música no Room; a URI é a do SAF (arquivo original, sem cópia). */
fun Song.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(artworkPath?.let { Uri.fromFile(File(it)) })
        .setExtras(Bundle().apply { putLong(EXTRA_DURATION, durationMs) })
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(Uri.parse(uri))
        .setMediaMetadata(metadata)
        .build()
}
