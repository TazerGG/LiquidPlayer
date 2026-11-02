package com.liquidplayer.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidplayer.domain.model.Playlist
import com.liquidplayer.domain.model.Song
import com.liquidplayer.presentation.theme.GlassDialog
import com.liquidplayer.presentation.theme.glass
import com.liquidplayer.utils.formatTime
import com.liquidplayer.utils.pluralSongs
import java.io.File

/** Capa carregada de forma assíncrona (Coil) a partir da miniatura em cache; placeholder em gradiente. */
@Composable
fun Artwork(
    path: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    iconSize: Dp = 24.dp,
    icon: ImageVector = Icons.Rounded.MusicNote,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(scheme.primary.copy(0.55f), scheme.tertiary.copy(0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            val context = LocalContext.current
            val request = remember(path) { ImageRequest.Builder(context).data(File(path)).crossfade(true).build() }
            AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, null, Modifier.size(iconSize), tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

/**
 * Linha de música. Gestos: arrastar da ESQUERDA para a DIREITA adiciona à fila;
 * da DIREITA para a ESQUERDA remove da fila. A linha volta ao lugar depois do gesto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraMenu: @Composable (dismiss: () -> Unit) -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    swipeActions: Boolean = true,
) {
    val actions = LocalSongActions.current
    val favorites = LocalFavoriteIds.current
    val isCurrent = LocalCurrentSongId.current == song.id
    val isFav = song.id in favorites
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val currentSong by rememberUpdatedState(song)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> actions.addToQueue(listOf(currentSong))
                SwipeToDismissBoxValue.EndToStart -> actions.removeFromQueue(currentSong)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // não "descarta" a linha: ela volta ao lugar
        },
    )
    val dragging = dismissState.dismissDirection != SwipeToDismissBoxValue.Settled

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = swipeActions,
        enableDismissFromEndToStart = swipeActions,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (dragging) MaterialTheme.colorScheme.background else Color.Transparent)
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
                .alpha(if (song.isAvailable) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Artwork(song.artworkPath, Modifier.size(52.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (song.isAvailable) "${song.artist} • ${formatTime(song.durationMs)}" else "Arquivo indisponível",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isFav) Icon(Icons.Rounded.Favorite, null, Modifier.size(18.dp).padding(end = 2.dp), tint = accent)
            trailing?.invoke()
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                ) {
                    val close = { menu = false }
                    DropdownMenuItem(
                        text = { Text("Tocar a seguir") }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                        onClick = { close(); actions.playNext(song) },
                    )
                    DropdownMenuItem(
                        text = { Text("Adicionar à fila") }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                        onClick = { close(); actions.addToQueue(listOf(song)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remover da fila") }, leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) },
                        onClick = { close(); actions.removeFromQueue(song) },
                    )
                    DropdownMenuItem(
                        text = { Text("Adicionar à playlist") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                        onClick = { close(); actions.addToPlaylist(listOf(song)) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFav) "Remover dos favoritos" else "Adicionar aos favoritos") },
                        leadingIcon = { Icon(if (isFav) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite, null) },
                        onClick = { close(); actions.toggleFavorite(song) },
                    )
                    extraMenu(close)
                }
            }
        }
    }
}

/** Fundo revelado ao arrastar: azul/destaque = adicionar à fila (→), vermelho = remover da fila (←). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val scheme = MaterialTheme.colorScheme
    val add = direction == SwipeToDismissBoxValue.StartToEnd
    val color by animateColorAsState(
        when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> scheme.primary
            SwipeToDismissBoxValue.EndToStart -> scheme.error
            SwipeToDismissBoxValue.Settled -> Color.Transparent
        },
        label = "swipeBg",
    )
    val fg = if (add) scheme.onPrimary else scheme.onError
    Row(
        Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
        horizontalArrangement = if (add) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            if (add) {
                Icon(Icons.Rounded.QueueMusic, null, tint = fg)
                Spacer(Modifier.width(8.dp))
                Text("Adicionar à fila", color = fg, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Remover da fila", color = fg, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.RemoveCircleOutline, null, tint = fg)
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay, enabled = enabled, shape = CircleShape) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Tocar")
        }
        FilledTonalButton(onClick = onShuffle, enabled = enabled, shape = CircleShape) {
            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Aleatório")
        }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier, menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).glass(RoundedCornerShape(22.dp), 0.9f).clickable(onClick = onClick).padding(10.dp),
    ) {
        Box {
            Artwork(
                playlist.coverPath, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp), 44.dp,
                if (playlist.sourceFolderId != null) Icons.Rounded.Folder else Icons.Rounded.LibraryMusic,
            )
            if (menu != null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Opções", tint = Color.White) }
                    DropdownMenu(
                        open, { open = false }, shape = RoundedCornerShape(18.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    ) { menu { open = false } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        Text(pluralSongs(playlist.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TextInputDialog(
    title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
    label: String = "Nome",
) {
    var text by remember { mutableStateOf(initial) }
    GlassDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancelar") }
            Button({ if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancelar") }
            Button(onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(confirmLabel) }
        }
    }
}

/** Campo de busca translúcido. */
@Composable
fun GlassSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Buscar") {
    TextField(
        value, onChange, modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(50)).glass(RoundedCornerShape(50), 0.8f),
        placeholder = { Text(hint) }, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Rounded.Close, "Limpar") } },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
