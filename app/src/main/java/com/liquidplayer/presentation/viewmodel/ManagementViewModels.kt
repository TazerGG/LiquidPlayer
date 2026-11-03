package com.liquidplayer.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidplayer.data.repository.*
import com.liquidplayer.domain.model.*
import com.liquidplayer.domain.usecase.CreatePlaylistFromFolderUseCase
import com.liquidplayer.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val folderPlaylists: CreatePlaylistFromFolderUseCase,
) : ViewModel() {
    val folders = library.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null as List<MusicFolder>?)
    val scanState = library.scanState
    val unavailable = library.observeUnavailableCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val message = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun addFolder(uri: Uri) = viewModelScope.launch {
        val (id, isNew) = library.addFolder(uri)
        if (!isNew) message.emit("Esta pasta já está na biblioteca. Atualizando…")
        library.scheduleScan(id, isNew)
    }

    fun rescan(id: Long) = library.scheduleScan(id)
    fun rescanAll() = library.scheduleScanAll()
    fun remove(id: Long) { viewModelScope.launch { library.removeFolder(id) } }

    fun reauthorize(id: Long, uri: Uri) {
        viewModelScope.launch {
            library.reauthorize(id, uri)
            library.scheduleScan(id)
        }
    }

    fun createPlaylists(folderId: Long, mode: FolderPlaylistMode) {
        viewModelScope.launch {
            val o = folderPlaylists(folderId, mode)
            message.emit(
                when {
                    o.created + o.updated == 0 && mode == FolderPlaylistMode.PER_SUBFOLDER -> "Nenhuma subpasta com músicas."
                    o.created + o.updated == 0 -> "Nada novo para adicionar: a playlist já está completa."
                    else -> "${o.created} playlist(s) criada(s), ${o.updated} atualizada(s) (${o.songsAdded} músicas)."
                },
            )
        }
    }
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepository,
    private val controller: PlayerController,
) : ViewModel() {
    val playlists = repo.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null as List<Playlist>?)

    fun create(name: String, cover: Uri?) { viewModelScope.launch { repo.create(name, cover) } }
    fun delete(id: Long) { viewModelScope.launch { repo.delete(id) } }
    fun play(id: Long, shuffle: Boolean) {
        viewModelScope.launch {
            val songs = repo.getSongs(id)
            if (shuffle) controller.playShuffled(songs) else controller.play(songs)
        }
    }
    fun enqueue(id: Long) { viewModelScope.launch { controller.addToQueue(repo.getSongs(id)) } }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: PlaylistRepository,
    library: LibraryRepository,
) : ViewModel() {
    val playlistId: Long = handle["playlistId"] ?: -1L
    val playlist = repo.observePlaylist(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null as Playlist?)
    val songs = repo.observeSongs(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null as List<Song>?)
    val allSongs = library.observeSongs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(name: String) { viewModelScope.launch { repo.rename(playlistId, name) } }
    fun setCover(uri: Uri) { viewModelScope.launch { repo.setCover(playlistId, uri) } }
    fun removeCover() { viewModelScope.launch { repo.setCover(playlistId, null) } }
    fun delete() { viewModelScope.launch { repo.delete(playlistId) } }
    fun remove(song: Song) { viewModelScope.launch { repo.removeSong(playlistId, song.id) } }
    fun add(ids: List<Long>) { viewModelScope.launch { repo.addSongs(playlistId, ids) } }
    fun reorder(ids: List<Long>) { viewModelScope.launch { repo.reorder(playlistId, ids) } }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val history: HistoryRepository,
    library: LibraryRepository,
    private val libraryRepo: LibraryRepository,
    private val updates: UpdateRepository,
) : ViewModel() {
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val unavailable = library.observeUnavailableCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val updateState = updates.state
    val currentVersion = updates.currentVersion

    fun checkUpdates() = updates.check(manual = true)
    fun showUpdate() = updates.showPrompt()

    fun set(key: String, value: Any) { viewModelScope.launch { settingsRepository.set(key, value) } }
    fun clearHistory() { viewModelScope.launch { history.clear(); message.emit("Histórico limpo.") } }
    fun removeUnavailable() {
        viewModelScope.launch { message.emit("${libraryRepo.removeUnavailable()} música(s) indisponível(is) removida(s).") }
    }
}
