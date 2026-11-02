package com.liquidplayer.domain.usecase

import com.liquidplayer.data.database.FolderDao
import com.liquidplayer.data.database.FolderSongDao
import com.liquidplayer.data.database.MemberRow
import com.liquidplayer.data.database.PlaylistDao
import com.liquidplayer.data.repository.PlaylistRepository
import com.liquidplayer.domain.model.FolderPlaylistMode
import com.liquidplayer.domain.model.FolderPlaylistOutcome
import javax.inject.Inject

/**
 * Cria (ou atualiza) playlists a partir de uma pasta da biblioteca.
 *  - WHOLE_FOLDER: uma playlist com o nome da pasta, incluindo todas as subpastas.
 *  - PER_SUBFOLDER: uma playlist para cada subpasta de primeiro nível (ex.: escolhendo "Music" → "Trap", "Rock").
 * Se a playlist já existir (mesma pasta + nome), só acrescenta o que faltar — nada é duplicado.
 */
class CreatePlaylistFromFolderUseCase @Inject constructor(
    private val folderDao: FolderDao,
    private val folderSongDao: FolderSongDao,
    private val playlistDao: PlaylistDao,
    private val playlists: PlaylistRepository,
) {
    suspend operator fun invoke(folderId: Long, mode: FolderPlaylistMode): FolderPlaylistOutcome {
        val folder = folderDao.getById(folderId) ?: return FolderPlaylistOutcome(0, 0, 0)
        val members = folderSongDao.getMembers(folderId)
        val groups: Map<String, List<MemberRow>> = when (mode) {
            FolderPlaylistMode.WHOLE_FOLDER -> mapOf(folder.name to members)
            FolderPlaylistMode.PER_SUBFOLDER -> members
                .filter { it.relativePath.isNotEmpty() }
                .groupBy { it.relativePath.substringBefore('/') }
        }
        var created = 0
        var updated = 0
        var songsAdded = 0
        for ((name, rows) in groups) {
            if (rows.isEmpty()) continue
            val ordered = rows.sortedWith(
                compareBy<MemberRow>(
                    { it.relativePath.lowercase() },
                    { if (it.song.trackNumber > 0) it.song.trackNumber else Int.MAX_VALUE },
                    { it.song.fileName.lowercase() },
                ),
            ).map { it.song.id }
            val existing = playlistDao.findFromFolder(folderId, name)
            val playlistId = existing?.id ?: playlists.create(name, sourceFolderId = folderId)
            val added = playlists.addSongs(playlistId, ordered)
            if (existing == null) created++ else if (added > 0) updated++
            songsAdded += added
        }
        return FolderPlaylistOutcome(created, updated, songsAdded)
    }
}
