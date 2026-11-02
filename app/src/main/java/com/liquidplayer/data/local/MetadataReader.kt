package com.liquidplayer.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.liquidplayer.domain.model.UNKNOWN_ALBUM
import com.liquidplayer.domain.model.UNKNOWN_ARTIST
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    val artworkPath: String?,
)

@Singleton
class MetadataReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coverCache: CoverCache,
) {
    /** Retorna null quando o arquivo não pode ser lido/decodificado (incompatível ou corrompido). */
    fun read(uri: Uri, fileName: String): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            if (duration == null && hasAudio == null) return null
            AudioMetadata(
                title = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                    ?: fileName.substringBeforeLast('.'),
                artist = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                    ?: clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    ?: UNKNOWN_ARTIST,
                album = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)) ?: UNKNOWN_ALBUM,
                durationMs = duration ?: 0L,
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                artworkPath = retriever.embeddedPicture?.let(coverCache::saveArtwork),
            )
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
