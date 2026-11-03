package com.liquidplayer.domain.model

const val UNKNOWN_ARTIST = "Artista desconhecido"
const val UNKNOWN_ALBUM = "Álbum desconhecido"

data class Song(
    val id: Long,
    val uri: String,
    val folderId: Long,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mimeType: String,
    val artworkPath: String?,
    val lyricsUri: String?,
    val trackNumber: Int,
    val isAvailable: Boolean,
    val addedAt: Long,
)

data class FavoriteSong(val song: Song, val addedAt: Long)

data class MusicFolder(
    val id: Long,
    val treeUri: String,
    val name: String,
    val addedAt: Long,
    val lastScanAt: Long,
    val accessible: Boolean,
    val songCount: Int,
)

data class Playlist(
    val id: Long,
    val name: String,
    val coverPath: String?,
    val songCount: Int,
    val totalDurationMs: Long,
    val sourceFolderId: Long?,
    val updatedAt: Long,
)

data class LyricLine(val timeMs: Long, val text: String)

enum class LyricsSource { EXTERNAL_LRC, EXTERNAL_TXT, EMBEDDED, ONLINE }

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: LyricsSource)

enum class RepeatMode { OFF, ALL, ONE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CornerStyle(val scale: Float) { COMPACT(0.6f), DEFAULT(1f), ROUND(1.4f) }

enum class FavoriteSort { DATE_ADDED, NAME, ARTIST }

enum class FolderPlaylistMode { WHOLE_FOLDER, PER_SUBFOLDER }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: Int = 0xFF1ED760.toInt(),
    val glassIntensity: Float = 0.6f,
    val dynamicBackground: Boolean = true,
    val amoled: Boolean = false,
    val cornerStyle: CornerStyle = CornerStyle.DEFAULT,
    val restoreQueue: Boolean = true,
    val pauseOnUnplug: Boolean = true,
    val skipBrokenTracks: Boolean = true,
    val autoCheckUpdates: Boolean = true,
)

/** Resultado de uma varredura de pasta. */
data class ScanResult(
    val folderId: Long,
    val folderName: String,
    val isNewFolder: Boolean,
    val totalFiles: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val relinked: Int = 0,
    val markedUnavailable: Int = 0,
    val duplicates: Int = 0,
    val unsupported: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val hasSubfolders: Boolean = false,
    val error: String? = null,
) {
    val hasIssues get() = error != null || unsupported.isNotEmpty() || failed.isNotEmpty()
}

data class FolderPlaylistOutcome(val created: Int, val updated: Int, val songsAdded: Int)

/** Release do GitHub mais nova que a versão instalada. */
data class UpdateInfo(
    val versionName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val sha256: String?,
)
