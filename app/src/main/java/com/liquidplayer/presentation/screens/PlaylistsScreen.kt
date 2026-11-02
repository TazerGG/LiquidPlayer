package com.liquidplayer.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.domain.model.Playlist
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.theme.GlassDialog
import com.liquidplayer.presentation.viewmodel.PlaylistsViewModel

@Composable
fun PlaylistsScreen(onOpen: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Playlists")
        PlaylistsContent(onOpen)
    }
}

@Composable
fun PlaylistsContent(onOpen: (Long) -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    val list = playlists

    val manual = list.orEmpty().filter { it.sourceFolderId == null }
    val fromFolders = list.orEmpty().filter { it.sourceFolderId != null }

    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalContentBottomPadding.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Button({ creating = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Nova playlist") }
        }
        if (list == null) item(span = { GridItemSpan(maxLineSpan) }) { LoadingState() }
        else if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(Icons.Rounded.LibraryMusic, "Nenhuma playlist", "Crie uma playlist ou gere uma a partir de uma pasta em Pastas.")
        }
        if (manual.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Suas playlists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(manual, key = { it.id }) { p -> PlaylistItem(p, onOpen, vm, { deleting = it }) }
        }
        if (fromFolders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Geradas a partir de pastas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(fromFolders, key = { it.id }) { p -> PlaylistItem(p, onOpen, vm, { deleting = it }) }
        }
    }

    if (creating) CreatePlaylistDialog({ creating = false }) { name, cover -> vm.create(name, cover); creating = false }
    deleting?.let { p ->
        ConfirmDialog("Excluir playlist?", "\"${p.name}\" será excluída. As músicas continuam na biblioteca.", "Excluir",
            { vm.delete(p.id); deleting = null }, { deleting = null })
    }
}

@Composable
private fun PlaylistItem(p: Playlist, onOpen: (Long) -> Unit, vm: PlaylistsViewModel, onDelete: (Playlist) -> Unit) {
    PlaylistCard(p, { onOpen(p.id) }) { dismiss ->
        DropdownMenuItem({ Text("Tocar") }, { dismiss(); vm.play(p.id, false) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
        DropdownMenuItem({ Text("Aleatório") }, { dismiss(); vm.play(p.id, true) }, leadingIcon = { Icon(Icons.Rounded.Shuffle, null) })
        DropdownMenuItem({ Text("Adicionar à fila") }, { dismiss(); vm.enqueue(p.id) }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) })
        DropdownMenuItem({ Text("Excluir") }, { dismiss(); onDelete(p) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
    }
}

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String, Uri?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cover = it }
    GlassDialog(onDismiss) {
        Text("Nova playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(6.dp)); Text(if (cover == null) "Escolher imagem de capa (opcional)" else "Imagem selecionada ✓")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancelar") }
            Button({ onCreate(name.trim(), cover) }, enabled = name.isNotBlank()) { Text("Criar") }
        }
    }
}

/** Diálogo global "Adicionar à playlist" (usado por qualquer música, favoritos, fila…). */
@Composable
fun AddToPlaylistDialog(playlists: List<Playlist>, onPick: (Playlist) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf<String?>(null) }
    if (newName != null) {
        TextInputDialog("Nova playlist", "", "Criar e adicionar", { onCreate(it) }, onDismiss)
        return
    }
    GlassDialog(onDismiss) {
        Text("Adicionar à playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { newName = "" }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text("Nova playlist", fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(playlists.size) { i ->
                val p = playlists[i]
                Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(p.coverPath, Modifier.size(44.dp), RoundedCornerShape(10.dp), icon = Icons.Rounded.LibraryMusic)
                    Spacer(Modifier.width(12.dp))
                    Column { Text(p.name, maxLines = 1, fontWeight = FontWeight.SemiBold); Text("${p.songCount} músicas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onDismiss) { Text("Cancelar") } }
    }
}
