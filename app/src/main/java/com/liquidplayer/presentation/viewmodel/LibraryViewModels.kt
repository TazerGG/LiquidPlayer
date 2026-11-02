package com.liquidplayer.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidplayer.data.repository.*
import com.liquidplayer.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ArtistItem(val name: String, val songCount: Int, val artworkPath: String?)
data class AlbumItem(val name: String, val artist: String, val songCount: Int, val artworkPath: String?)

@HiltViewModel
class HomeViewModel @Inject constructor(
    library: LibraryRepository,
    history: HistoryRepository,
    playlistRepository: PlaylistRepository,
    favoritesRepository: FavoritesRepository,
) : ViewModel() {
    val songCount = library.observeSongs().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)
    val folderCount = library.observeFolders().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)
    val recent = history.observeRecent(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = playlistRepository.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = favoritesRepository.observeSongs().map { list -> list.sortedByDescending { it.addedAt }.map { it.song }.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

data class LibraryUi(
    val loading: Boolean = true,
    val songs: List<Song> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    val query = MutableStateFlow("")

    val ui: StateFlow<LibraryUi> = combine(library.observeSongs(), query) { all, q ->
        val term = q.trim().lowercase()
        val songs = if (term.isEmpty()) all else all.filter {
            it.title.lowercase().contains(term) || it.artist.lowercase().contains(term) || it.album.lowercase().contains(term)
        }
        LibraryUi(
            loading = false,
            songs = songs,
            artists = songs.groupBy { it.artist }.map { (n, l) -> ArtistItem(n, l.size, l.firstNotNullOfOrNull { it.artworkPath }) }
                .sortedBy { it.name.lowercase() },
            albums = songs.groupBy { it.album }.map { (n, l) ->
                AlbumItem(n, l.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: "", l.size, l.firstNotNullOfOrNull { it.artworkPath })
            }.sortedBy { it.name.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUi())
}

@HiltViewModel
class CollectionViewModel @Inject constructor(handle: SavedStateHandle, library: LibraryRepository) : ViewModel() {
    val type: String = handle["type"] ?: "artist"
    val key: String = handle["key"] ?: ""
    val songs: StateFlow<List<Song>?> = library.observeSongs().map { all ->
        val list = if (type == "album") all.filter { it.album == key } else all.filter { it.artist == key }
        if (type == "album") list.sortedWith(compareBy({ if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }, { it.title.lowercase() }))
        else list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(favorites: FavoritesRepository) : ViewModel() {
    val sort = MutableStateFlow(FavoriteSort.DATE_ADDED)
    val songs: StateFlow<List<Song>?> = combine(favorites.observeSongs(), sort) { list, s ->
        when (s) {
            FavoriteSort.DATE_ADDED -> list.sortedByDescending { it.addedAt }
            FavoriteSort.NAME -> list.sortedBy { it.song.title.lowercase() }
            FavoriteSort.ARTIST -> list.sortedWith(compareBy({ it.song.artist.lowercase() }, { it.song.title.lowercase() }))
        }.map { it.song }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
