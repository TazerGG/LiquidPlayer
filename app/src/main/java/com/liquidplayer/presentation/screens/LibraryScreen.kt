package com.liquidplayer.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.navigation.Routes
import com.liquidplayer.presentation.theme.GlassChip
import com.liquidplayer.presentation.theme.glass
import com.liquidplayer.presentation.viewmodel.CollectionViewModel
import com.liquidplayer.presentation.viewmodel.LibraryViewModel
import com.liquidplayer.utils.pluralSongs

private enum class LibraryTab(val label: String) { SONGS("Músicas"), ARTISTS("Artistas"), ALBUMS("Álbuns"), FOLDERS("Pastas"), PLAYLISTS("Playlists") }

@Composable
fun LibraryScreen(onNavigate: (String) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    var tab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    val bottom = LocalContentBottomPadding.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Biblioteca")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryTab.entries.forEach { GlassChip(it.label, tab == it, { tab = it }) }
        }
        Spacer(Modifier.height(8.dp))
        if (tab in listOf(LibraryTab.SONGS, LibraryTab.ARTISTS, LibraryTab.ALBUMS)) {
            GlassSearchField(query, { vm.query.value = it }, hint = "Buscar músicas, artistas, álbuns")
        }
        when (tab) {
            LibraryTab.SONGS -> when {
                ui.loading -> LoadingState()
                ui.songs.isEmpty() -> EmptyState(
                    Icons.Rounded.MusicOff,
                    if (query.isBlank()) "Biblioteca vazia" else "Nada encontrado",
                    if (query.isBlank()) "Adicione pastas na aba Pastas para listar suas músicas." else "Tente outro termo de busca.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                    item { PlayShuffleRow({ actions.play(ui.songs, 0) }, { actions.shuffle(ui.songs) }) }
                    itemsIndexed(ui.songs, key = { _, s -> s.id }) { i, s -> SongRow(s, { actions.play(ui.songs, i) }) }
                }
            }
            LibraryTab.ARTISTS -> if (ui.artists.isEmpty()) EmptyState(Icons.Rounded.Person, "Nenhum artista", "Os artistas aparecem quando há músicas na biblioteca.")
            else LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                items(ui.artists, key = { it.name }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onNavigate(Routes.collection("artist", a.name)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(a.artworkPath, Modifier.size(52.dp), CircleShape, icon = Icons.Rounded.Person)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pluralSongs(a.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            LibraryTab.ALBUMS -> if (ui.albums.isEmpty()) EmptyState(Icons.Rounded.Album, "Nenhum álbum", "Os álbuns aparecem quando há músicas na biblioteca.")
            else LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.albums, key = { it.name }) { al ->
                    Column(
                        Modifier.glass(RoundedCornerShape(20.dp), 0.9f).clickable { onNavigate(Routes.collection("album", al.name)) }.padding(10.dp),
                    ) {
                        Artwork(al.artworkPath, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(14.dp), 44.dp, Icons.Rounded.Album)
                        Spacer(Modifier.height(8.dp))
                        Text(al.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(al.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LibraryTab.FOLDERS -> FoldersContent()
            LibraryTab.PLAYLISTS -> PlaylistsContent(onOpen = { onNavigate(Routes.playlist(it)) })
        }
    }
}

/** Lista de músicas de um artista ou álbum. */
@Composable
fun CollectionScreen(onBack: () -> Unit, vm: CollectionViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    val list = songs
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(vm.key, subtitle = if (vm.type == "album") "Álbum" else "Artista") {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
        }
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState(Icons.Rounded.MusicOff, "Sem músicas", "Nenhuma música disponível aqui.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = LocalContentBottomPadding.current)) {
                item { PlayShuffleRow({ actions.play(list, 0) }, { actions.shuffle(list) }) }
                itemsIndexed(list, key = { _, s -> s.id }) { i, s -> SongRow(s, { actions.play(list, i) }) }
            }
        }
    }
}
