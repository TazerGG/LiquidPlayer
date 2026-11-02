package com.liquidplayer.presentation.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.domain.model.FavoriteSort
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.theme.GlassChip
import com.liquidplayer.presentation.viewmodel.FavoritesViewModel
import com.liquidplayer.utils.pluralSongs

@Composable
fun FavoritesScreen(vm: FavoritesViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    val list = songs

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Favoritos", subtitle = list?.let { pluralSongs(it.size) }) {
            if (!list.isNullOrEmpty()) {
                IconButton({ actions.addToQueue(list) }) { Icon(Icons.Rounded.QueueMusic, "Adicionar à fila") }
                IconButton({ actions.addToPlaylist(list) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar a uma playlist") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Recentes", sort == FavoriteSort.DATE_ADDED, { vm.sort.value = FavoriteSort.DATE_ADDED })
            GlassChip("Nome", sort == FavoriteSort.NAME, { vm.sort.value = FavoriteSort.NAME })
            GlassChip("Artista", sort == FavoriteSort.ARTIST, { vm.sort.value = FavoriteSort.ARTIST })
        }
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState(Icons.Rounded.FavoriteBorder, "Sem favoritas ainda", "Toque no coração na tela de reprodução ou no menu ⋮ de uma música.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = LocalContentBottomPadding.current)) {
                item { PlayShuffleRow({ actions.play(list, 0) }, { actions.shuffle(list) }) }
                itemsIndexed(list, key = { _, s -> s.id }) { i, s -> SongRow(s, { actions.play(list, i) }) }
            }
        }
    }
}
