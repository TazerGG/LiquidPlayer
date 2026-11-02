package com.liquidplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidplayer.data.repository.LibraryRepository
import com.liquidplayer.data.repository.LyricsRepository
import com.liquidplayer.domain.model.Lyrics
import com.liquidplayer.domain.model.Song
import com.liquidplayer.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Ready(val lyrics: Lyrics) : LyricsUi
    data class Missing(val searching: Boolean = false, val error: String? = null) : LyricsUi
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlayerController,
    private val library: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {
    val state = controller.state
    val positionFlow = controller.positionMs
    val volume = controller.volumeFraction

    private val _lyrics = MutableStateFlow<LyricsUi>(LyricsUi.Loading)
    val lyrics: StateFlow<LyricsUi> = _lyrics.asStateFlow()
    private var currentSong: Song? = null

    init {
        viewModelScope.launch {
            controller.state.map { it.currentSong?.id }.distinctUntilChanged().collectLatest { id ->
                _lyrics.value = LyricsUi.Loading
                currentSong = id?.let { library.getSong(it) }
                val found = currentSong?.let { lyricsRepository.load(it) }
                _lyrics.value = if (found != null) LyricsUi.Ready(found) else LyricsUi.Missing()
            }
        }
    }

    fun searchOnline() {
        val song = currentSong ?: return
        viewModelScope.launch {
            _lyrics.value = LyricsUi.Missing(searching = true)
            _lyrics.value = try {
                lyricsRepository.fetchOnline(song)?.let { LyricsUi.Ready(it) }
                    ?: LyricsUi.Missing(error = "Nenhuma letra encontrada online para esta música.")
            } catch (e: Exception) {
                LyricsUi.Missing(error = "Sem conexão ou serviço indisponível. As letras locais continuam funcionando offline.")
            }
        }
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(ms: Long) = controller.seekTo(ms)
    fun toggleShuffle() = controller.toggleShuffle()
    fun cycleRepeat() = controller.cycleRepeat()
    fun skipTo(index: Int) = controller.skipToQueueItem(index)
    fun removeFromQueue(index: Int) = controller.removeFromQueue(index)
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)
    fun clearQueue() = controller.clearQueue()
    fun setVolume(f: Float) = controller.setVolume(f)
}
