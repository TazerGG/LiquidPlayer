package com.liquidplayer.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.data.repository.ScanState
import com.liquidplayer.domain.model.FolderPlaylistMode
import com.liquidplayer.domain.model.MusicFolder
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.theme.GlassDialog
import com.liquidplayer.presentation.theme.glass
import com.liquidplayer.presentation.viewmodel.FoldersViewModel
import com.liquidplayer.utils.formatDate
import com.liquidplayer.utils.pluralSongs

/** Tela de gerenciamento de diretórios (acessível pelas Configurações). */
@Composable
fun FoldersScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Pastas", subtitle = "Diretórios da sua biblioteca") {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
        }
        FoldersContent()
    }
}

@Composable
fun FoldersContent(vm: FoldersViewModel = hiltViewModel()) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val unavailable by vm.unavailable.collectAsStateWithLifecycle()
    val show = LocalShowMessage.current
    LaunchedEffect(Unit) { vm.message.collect { show(it) } }

    var reauthId by remember { mutableStateOf<Long?>(null) }
    var removing by remember { mutableStateOf<MusicFolder?>(null) }
    var creating by remember { mutableStateOf<MusicFolder?>(null) }

    // Storage Access Framework: o usuário escolhe a pasta; o app guarda a permissão persistente.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) reauthId?.let { vm.reauthorize(it, uri) } ?: vm.addFolder(uri)
        reauthId = null
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalContentBottomPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ reauthId = null; picker.launch(null) }) {
                    Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Adicionar pasta")
                }
                if (!folders.isNullOrEmpty()) FilledTonalButton({ vm.rescanAll() }) {
                    Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Atualizar todas")
                }
            }
        }
        (scan as? ScanState.Scanning)?.let { s ->
            item {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(14.dp)) {
                    Text("Lendo \"${s.folderName}\"…", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (s.total > 0) {
                        LinearProgressIndicator(progress = { s.processed.toFloat() / s.total }, Modifier.fillMaxWidth())
                        Text("${s.processed} de ${s.total} arquivos", style = MaterialTheme.typography.bodySmall)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        when {
            folders == null -> item { LoadingState() }
            folders.orEmpty().isEmpty() -> item {
                EmptyState(
                    Icons.Rounded.FolderOpen, "Nenhuma pasta adicionada",
                    "Toque em \"Adicionar pasta\" e escolha onde estão suas músicas. Subpastas são incluídas.",
                )
            }
            else -> items(folders.orEmpty(), key = { it.id }) { f ->
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 0.9f).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.accessible) Icons.Rounded.Folder else Icons.Rounded.FolderOff, null,
                            tint = if (f.accessible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${pluralSongs(f.songCount)} • atualizada: ${formatDate(f.lastScanAt)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!f.accessible) {
                        Text("Sem acesso a esta pasta (permissão perdida ou pasta movida). Reautorize para reconectar suas músicas.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!f.accessible) TextButton({ reauthId = f.id; picker.launch(Uri.parse(f.treeUri)) }) { Text("Reautorizar") }
                        TextButton({ vm.rescan(f.id) }, enabled = f.accessible) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Atualizar")
                        }
                        TextButton({ creating = f }, enabled = f.songCount > 0) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Criar playlist")
                        }
                        TextButton({ removing = f }) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Remover")
                        }
                    }
                }
            }
        }
        if (unavailable > 0) {
            item {
                Text("$unavailable música(s) com arquivo indisponível (removidas/movidas). Você pode limpar isso em Configurações.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    removing?.let { f ->
        ConfirmDialog(
            "Remover pasta?", "\"${f.name}\" sai da biblioteca (e suas músicas das playlists). Os arquivos no aparelho NÃO são apagados.",
            "Remover", { vm.remove(f.id); removing = null }, { removing = null },
        )
    }
    creating?.let { f ->
        FolderPlaylistDialog(f.name, hasSubfolders = true, onDismiss = { creating = null }) { mode ->
            vm.createPlaylists(f.id, mode); creating = null
        }
    }
}

/** Escolha entre "uma playlist com tudo" ou "uma playlist por subpasta". */
@Composable
fun FolderPlaylistDialog(folderName: String, hasSubfolders: Boolean, onDismiss: () -> Unit, onConfirm: (FolderPlaylistMode) -> Unit) {
    var mode by remember { mutableStateOf(FolderPlaylistMode.WHOLE_FOLDER) }
    GlassDialog(onDismiss) {
        Text("Criar playlist a partir da pasta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        ModeRow(mode == FolderPlaylistMode.WHOLE_FOLDER, "Uma playlist \"$folderName\"", "Todas as músicas da pasta, incluindo subpastas.") { mode = FolderPlaylistMode.WHOLE_FOLDER }
        if (hasSubfolders) ModeRow(mode == FolderPlaylistMode.PER_SUBFOLDER, "Uma playlist por subpasta", "Ex.: escolhendo \"Music\" cria \"Trap\", \"Rock\"…") { mode = FolderPlaylistMode.PER_SUBFOLDER }
        Text("Se a playlist já existir, só as músicas novas são adicionadas. Você pode editá-la depois normalmente.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancelar") }
            Button({ onConfirm(mode) }) { Text("Criar") }
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
