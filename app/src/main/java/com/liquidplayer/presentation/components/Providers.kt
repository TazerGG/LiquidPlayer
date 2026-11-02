package com.liquidplayer.presentation.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liquidplayer.domain.model.Song

/** Ações comuns a qualquer lista de músicas (tocar, fila, favoritos, playlists). */
@Stable
interface SongActions {
    fun play(songs: List<Song>, index: Int = 0)
    fun shuffle(songs: List<Song>)
    fun playNext(song: Song)
    fun addToQueue(songs: List<Song>)
    fun removeFromQueue(song: Song)
    fun toggleFavorite(song: Song)
    fun addToPlaylist(songs: List<Song>)
}

val LocalSongActions = staticCompositionLocalOf<SongActions> { error("SongActions não fornecido") }
val LocalFavoriteIds = compositionLocalOf<Set<Long>> { emptySet() }
val LocalCurrentSongId = compositionLocalOf<Long?> { null }
/** Espaço inferior reservado para o mini player + barra de navegação flutuantes. */
val LocalContentBottomPadding = compositionLocalOf<Dp> { 0.dp }

/** Mostra uma mensagem (Snackbar) a partir de qualquer tela. */
val LocalShowMessage = staticCompositionLocalOf<(String) -> Unit> { {} }
