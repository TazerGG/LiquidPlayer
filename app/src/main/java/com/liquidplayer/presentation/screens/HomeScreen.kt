package com.liquidplayer.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.domain.model.Song
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.navigation.Routes
import com.liquidplayer.presentation.theme.glass
import com.liquidplayer.presentation.viewmodel.HomeViewModel
import com.liquidplayer.utils.pluralSongs

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val songCount by vm.songCount.collectAsStateWithLifecycle()
    val folderCount by vm.folderCount.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current

    LazyColumn(contentPadding = PaddingValues(bottom = LocalContentBottomPadding.current)) {
        item { ScreenHeader("Início", subtitle = if (songCount > 0) pluralSongs(songCount) + " na sua biblioteca" else "Sua música, offline") }

        if (folderCount == 0) {
            item {
                EmptyState(
                    Icons.Rounded.CreateNewFolder, "Adicione sua primeira pasta",
                    "O app só toca as músicas das pastas que você escolher. Nada é varrido automaticamente.",
                ) { Button({ onNavigate(Routes.FOLDERS) }) { Text("Adicionar pasta") } }
            }
        } else {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickCard(Icons.Rounded.LibraryMusic, "Biblioteca", Modifier.weight(1f)) { onNavigate(Routes.LIBRARY) }
                        QuickCard(Icons.Rounded.Favorite, "Favoritas", Modifier.weight(1f)) { onNavigate(Routes.FAVORITES) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickCard(Icons.Rounded.QueueMusic, "Playlists", Modifier.weight(1f)) { onNavigate(Routes.PLAYLISTS) }
                        QuickCard(Icons.Rounded.Folder, "Pastas", Modifier.weight(1f)) { onNavigate(Routes.FOLDERS) }
                    }
                }
            }
            item {
                SectionTitle("Suas playlists") {
                    TextButton({ onNavigate(Routes.PLAYLISTS) }) { Text(if (playlists.isEmpty()) "Criar" else "Ver todas") }
                }
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "Você ainda não tem playlists. Crie uma ou gere a partir de uma pasta.",
                        Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(playlists, key = { it.id }) { p ->
                            PlaylistCard(p, { onNavigate(Routes.playlist(p.id)) }, Modifier.width(150.dp))
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionTitle("Tocadas recentemente") }
                item { SongCards(recent) { i -> actions.play(recent, i) } }
            }
            if (favorites.isNotEmpty()) {
                item {
                    SectionTitle("Favoritas") {
                        TextButton({ actions.play(favorites, 0) }) { Text("Tocar") }
                    }
                }
                item { SongCards(favorites) { i -> actions.play(favorites, i) } }
            }
            if (songCount == 0) {
                item {
                    EmptyState(Icons.Rounded.MusicOff, "Nenhuma música encontrada", "As pastas adicionadas ainda não têm arquivos compatíveis. Verifique em Pastas.")
                }
            }
        }
    }
}

@Composable
private fun QuickCard(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.glass(RoundedCornerShape(20.dp), 0.9f).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SongCards(songs: List<Song>, onClick: (Int) -> Unit) {
    val current = LocalCurrentSongId.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(songs.size, key = { songs[it].id }) { i ->
            val s = songs[i]
            Column(Modifier.width(140.dp).clickable { onClick(i) }) {
                Artwork(s.artworkPath, Modifier.size(140.dp), RoundedCornerShape(18.dp), 40.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    color = if (current == s.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
