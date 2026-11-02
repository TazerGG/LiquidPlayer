package com.liquidplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidplayer.data.repository.*
import com.liquidplayer.domain.model.*
import com.liquidplayer.domain.usecase.CreatePlaylistFromFolderUseCase
import com.liquidplayer.player.PlayerController
import com.liquidplayer.presentation.components.SongActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ViewModel raiz: tema, ações globais sobre músicas, diálogo "adicionar à playlist" e resultado de varreduras. */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    favoritesRepository: FavoritesRepository,
    playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
    private val folderPlaylists: CreatePlaylistFromFolderUseCase,
    private val controller: PlayerController,
) : ViewModel() {

    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val favoriteIds = favoritesRepository.observeIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val allPlaylists = playlistRepository.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val player = controller.state
    val positionFlow = controller.positionMs
    val scanState = libraryRepository.scanState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: Flow<String> = merge(controller.events, _messages)

    val pendingAdd = MutableStateFlow<List<Song>?>(null)
    val scanDialog = MutableStateFlow<ScanResult?>(null)

    init {
        controller.connect()
        viewModelScope.launch {
            libraryRepository.scanResults.collect { r ->
                if (r.hasIssues || (r.isNewFolder && r.totalFiles > 0)) scanDialog.value = r
                else _messages.tryEmit(
                    "${r.folderName}: ${r.added} nova(s), ${r.updated} atualizada(s), ${r.markedUnavailable} indisponível(is).",
                )
            }
        }
    }

    val actions = object : SongActions {
        override fun play(songs: List<Song>, index: Int) = controller.play(songs, index)
        override fun shuffle(songs: List<Song>) = controller.playShuffled(songs)
        override fun playNext(song: Song) = controller.playNext(song)
        override fun addToQueue(songs: List<Song>) = controller.addToQueue(songs)
        override fun removeFromQueue(song: Song) = controller.removeSongFromQueue(song.id)
        override fun toggleFavorite(song: Song) { viewModelScope.launch { favorites.toggle(song.id) } }
        override fun addToPlaylist(songs: List<Song>) { pendingAdd.value = songs }
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()

    fun addPendingTo(playlist: Playlist) {
        val songs = pendingAdd.value ?: return
        pendingAdd.value = null
        viewModelScope.launch {
            val n = playlists.addSongs(playlist.id, songs.map { it.id })
            _messages.emit(if (n == 0) "Já estava em \"${playlist.name}\"." else "Adicionada a \"${playlist.name}\" ($n).")
        }
    }

    fun createPlaylistAndAdd(name: String) {
        val songs = pendingAdd.value ?: return
        pendingAdd.value = null
        viewModelScope.launch {
            val id = playlists.create(name)
            playlists.addSongs(id, songs.map { it.id })
            _messages.emit("Playlist \"$name\" criada.")
        }
    }

    fun dismissAdd() { pendingAdd.value = null }
    fun dismissScan() { scanDialog.value = null }

    fun createFromScan(result: ScanResult, mode: FolderPlaylistMode) {
        scanDialog.value = null
        viewModelScope.launch {
            val o = folderPlaylists(result.folderId, mode)
            _messages.emit(
                if (o.created + o.updated == 0) "Nenhuma música encontrada para criar a playlist."
                else "${o.created} playlist(s) criada(s), ${o.songsAdded} música(s) adicionada(s).",
            )
        }
    }
}
