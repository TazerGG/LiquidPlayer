package com.liquidplayer.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.domain.model.Song
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.theme.GlassDialog
import com.liquidplayer.presentation.theme.GlassIconButton
import com.liquidplayer.presentation.viewmodel.PlaylistDetailViewModel
import com.liquidplayer.utils.formatTotalDuration
import com.liquidplayer.utils.pluralSongs
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(onBack: () -> Unit, vm: PlaylistDetailViewModel = hiltViewModel()) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val allSongs by vm.allSongs.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current

    var local by remember { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(songs) { songs?.let { local = it } }

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setCover) }

    val listState = rememberLazyListState()
    val headerCount = 1
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index - headerCount
        val t = to.index - headerCount
        if (f in local.indices && t in local.indices) local = local.toMutableList().apply { add(t, removeAt(f)) }
    }

    val pl = playlist
    if (pl == null && songs != null) { // playlist excluída
        LaunchedEffect(Unit) { onBack() }
        return
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = LocalContentBottomPadding.current)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } }
                Artwork(pl?.coverPath, Modifier.size(180.dp), RoundedCornerShape(24.dp), 64.dp, Icons.Rounded.LibraryMusic)
                Spacer(Modifier.height(12.dp))
                Text(pl?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${pluralSongs(pl?.songCount ?: 0)} • ${formatTotalDuration(pl?.totalDurationMs ?: 0)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayShuffleRow({ actions.play(local, 0) }, { actions.shuffle(local) }, enabled = local.isNotEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(Icons.Rounded.Add, "Adicionar músicas", { picking = true })
                    GlassIconButton(Icons.Rounded.QueueMusic, "Adicionar à fila", { actions.addToQueue(local) })
                    GlassIconButton(Icons.Rounded.Edit, "Renomear", { renaming = true })
                    GlassIconButton(Icons.Rounded.Image, "Alterar capa", { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    GlassIconButton(Icons.Rounded.Delete, "Excluir playlist", { deleting = true })
                }
                if (local.size > 1) Text("Segure o ícone ≡ para reordenar", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (songs == null) item { LoadingState() }
        else if (local.isEmpty()) item {
            EmptyState(Icons.Rounded.MusicNote, "Playlist vazia", "Use o botão + para adicionar músicas da sua biblioteca.")
        }
        items(local, key = { it.id }) { song ->
            ReorderableItem(reorder, key = song.id) { isDragging ->
                val bg by animateColorAsState(
                    if (isDragging) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent, label = "drag",
                )
                SongRow(
                    song, { actions.play(local, local.indexOf(song)) }, Modifier.background(bg),
                    extraMenu = { dismiss ->
                        DropdownMenuItem({ Text("Remover da playlist") }, { dismiss(); vm.remove(song) }, leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) })
                    },
                    trailing = {
                        Icon(
                            Icons.Rounded.DragHandle, "Reordenar",
                            Modifier.draggableHandle(onDragStopped = { vm.reorder(local.map { it.id }) }).padding(6.dp),
                        )
                    },
                )
            }
        }
    }

    if (renaming) TextInputDialog("Renomear playlist", pl?.name.orEmpty(), "Salvar", { vm.rename(it); renaming = false }, { renaming = false })
    if (deleting) ConfirmDialog("Excluir playlist?", "As músicas continuam na biblioteca.", "Excluir", { vm.delete(); deleting = false }, { deleting = false })
    if (picking) SongPickerDialog(allSongs, local.map { it.id }.toSet(), { picking = false }) { ids -> vm.add(ids); picking = false }
}

/** Seleção múltipla de músicas da biblioteca para acrescentar à playlist. */
@Composable
fun SongPickerDialog(all: List<Song>, excluded: Set<Long>, onDismiss: () -> Unit, onConfirm: (List<Long>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val candidates = remember(all, excluded, query) {
        val q = query.trim().lowercase()
        all.filter { it.id !in excluded && it.isAvailable && (q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)) }
    }
    GlassDialog(onDismiss) {
        Text("Adicionar músicas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.height(340.dp)) {
            items(candidates, key = { it.id }) { s ->
                val on = s.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (on) selected - s.id else selected + s.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, { selected = if (on) selected - s.id else selected + s.id })
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(s.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancelar") }
            Button({ onConfirm(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Adicionar (${selected.size})") }
        }
    }
}
