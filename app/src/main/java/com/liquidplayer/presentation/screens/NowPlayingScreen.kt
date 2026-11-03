package com.liquidplayer.presentation.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidplayer.domain.model.Lyrics
import com.liquidplayer.domain.model.LyricsSource
import com.liquidplayer.domain.model.RepeatMode
import com.liquidplayer.player.PlayerState
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.theme.GlassChip
import com.liquidplayer.presentation.theme.GlassIconButton
import com.liquidplayer.presentation.theme.LocalAppSettings
import com.liquidplayer.presentation.theme.glass
import com.liquidplayer.presentation.viewmodel.LyricsUi
import com.liquidplayer.presentation.viewmodel.PlayerViewModel
import com.liquidplayer.utils.formatTime
import kotlinx.coroutines.flow.Flow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private enum class NpTab(val label: String) { PLAYER("Player"), LYRICS("Letra"), QUEUE("Fila") }

@Composable
fun NowPlayingScreen(onBack: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lyricsUi by vm.lyrics.collectAsStateWithLifecycle()
    val settings = LocalAppSettings.current
    val actions = LocalSongActions.current
    val favorites = LocalFavoriteIds.current
    var tab by rememberSaveable { mutableStateOf(NpTab.PLAYER) }
    BackHandler(onBack = onBack)

    val song = state.currentSong
    if (song == null) { // fila esvaziada
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val scheme = MaterialTheme.colorScheme
    val dominant = rememberDominantColor(song.artworkPath, scheme.primary)
    val bgColor by animateColorAsState(if (settings.dynamicBackground) dominant else scheme.primary, tween(800), label = "npBg")
    val isFav = song.id in favorites

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        // Fundo dinâmico: gradiente com a cor da capa + capa desfocada (Android 12+; senão só o gradiente).
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bgColor.copy(alpha = 0.6f), scheme.background))))
        if (settings.dynamicBackground && song.artworkPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            val req = remember(song.artworkPath) { ImageRequest.Builder(ctx).data(File(song.artworkPath)).crossfade(600).build() }
            AsyncImage(req, null, Modifier.fillMaxSize().blur(80.dp).alpha(0.32f), contentScale = ContentScale.Crop)
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Minimizar", onBack)
                Spacer(Modifier.weight(1f))
                NpTab.entries.forEach { GlassChip(it.label, tab == it, { tab = it }) }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(tab, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "npTab") { t ->
                    when (t) {
                        NpTab.PLAYER -> {
                            val scale by animateFloatAsState(if (state.isPlaying) 1f else 0.86f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "cover")
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Artwork(
                                    song.artworkPath,
                                    Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = scale; scaleY = scale }
                                        .shadow(24.dp, RoundedCornerShape(28.dp)),
                                    RoundedCornerShape(28.dp), 96.dp,
                                )
                            }
                        }
                        NpTab.LYRICS -> LyricsPanel(lyricsUi, vm.positionFlow, vm::seekTo, vm::searchOnline)
                        NpTab.QUEUE -> QueuePanel(state, vm)
                    }
                }
            }

            // Informações + favoritar + adicionar à playlist
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.album, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ actions.addToPlaylist(listOf(song)) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar à playlist") }
                IconButton({ actions.toggleFavorite(song) }) {
                    Icon(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isFav) "Remover dos favoritos" else "Favoritar", tint = if (isFav) scheme.primary else scheme.onSurface)
                }
            }

            SeekBar(vm.positionFlow, state.durationMs, vm::seekTo)

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.Rounded.Shuffle, if (state.shuffle) "Aleatório ativado" else "Ativar aleatório", vm::toggleShuffle, selected = state.shuffle)
                GlassIconButton(Icons.Rounded.SkipPrevious, "Anterior", vm::previous, size = 52.dp, iconSize = 28.dp)
                GlassIconButton(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pausar" else "Tocar",
                    vm::togglePlayPause, size = 72.dp, iconSize = 38.dp, filled = true,
                )
                GlassIconButton(Icons.Rounded.SkipNext, "Próxima", vm::next, size = 52.dp, iconSize = 28.dp)
                GlassIconButton(
                    if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (state.repeat) { RepeatMode.OFF -> "Repetição desativada"; RepeatMode.ALL -> "Repetir fila"; RepeatMode.ONE -> "Repetir música" },
                    vm::cycleRepeat, selected = state.repeat != RepeatMode.OFF,
                )
            }

            if (tab == NpTab.PLAYER) {
                val volume by vm.volume.collectAsStateWithLifecycle(0.5f)
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VolumeDown, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                    Slider(volume, vm::setVolume, Modifier.weight(1f))
                    Icon(Icons.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                }
            } else Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SeekBar(positionFlow: Flow<Long>, durationMs: Long, onSeek: (Long) -> Unit) {
    val position by positionFlow.collectAsStateWithLifecycle(0L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek((it * durationMs).toLong()) }; dragging = null },
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(((dragging ?: fraction) * durationMs).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --------------------------------------------------------------------------- Letras
@Composable
private fun LyricsPanel(ui: LyricsUi, positionFlow: Flow<Long>, onSeek: (Long) -> Unit, onSearchOnline: () -> Unit) {
    Box(Modifier.fillMaxSize().glass(RoundedCornerShape(28.dp), 0.6f), contentAlignment = Alignment.Center) {
        when (ui) {
            LyricsUi.Loading -> CircularProgressIndicator()
            is LyricsUi.Missing -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Lyrics, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Letra não disponível", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Coloque um arquivo .lrc ou .txt com o mesmo nome da música na mesma pasta (ou use arquivos com letra embutida) e atualize a pasta.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                ui.error?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (ui.searching) CircularProgressIndicator(Modifier.size(28.dp))
                else OutlinedButton(onSearchOnline) { Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text("Buscar letra online") }
            }
            is LyricsUi.Ready -> LyricsList(ui.lyrics, positionFlow, onSeek)
        }
    }
}

private fun activeLine(lyrics: Lyrics, position: Long): Int {
    var lo = 0
    var hi = lyrics.lines.lastIndex
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (lyrics.lines[mid].timeMs <= position) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

@Composable
private fun LyricsList(lyrics: Lyrics, positionFlow: Flow<Long>, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val position by if (lyrics.synced) positionFlow.collectAsStateWithLifecycle(0L) else remember { mutableStateOf(0L) }
    val active by remember(lyrics) { derivedStateOf { if (lyrics.synced) activeLine(lyrics, position) else -1 } }

    // Rolagem automática: mantém a linha atual no terço superior da área visível.
    LaunchedEffect(active) {
        if (active >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(active, scrollOffset = -(listState.layoutInfo.viewportSize.height / 3))
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 90.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val isActive = i == active
            val color by animateColorAsState(
                if (!lyrics.synced) MaterialTheme.colorScheme.onSurface
                else if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = if (i < active) 0.35f else 0.5f),
                tween(300), label = "lyric",
            )
            Text(
                line.text.ifBlank { "♪" },
                Modifier.fillMaxWidth().then(if (lyrics.synced) Modifier.clickable { onSeek(line.timeMs) } else Modifier),
                color = color,
                style = if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
            )
        }
        item {
            Text(
                when (lyrics.source) {
                    LyricsSource.EXTERNAL_LRC -> "Fonte: arquivo .lrc"
                    LyricsSource.EXTERNAL_TXT -> "Fonte: arquivo .txt"
                    LyricsSource.EMBEDDED -> "Fonte: letra embutida no arquivo"
                    LyricsSource.ONLINE -> "Fonte: LRCLIB (salva no aparelho)"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------------- Fila
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuePanel(state: PlayerState, vm: PlayerViewModel) {
    var items by remember { mutableStateOf(state.entries) }
    LaunchedEffect(state.entries) { items = state.entries }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in items.indices && to.index in items.indices) {
            items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
            vm.moveQueueItem(from.index, to.index)
        }
    }
    LaunchedEffect(Unit) { if (state.currentIndex >= 0) listState.scrollToItem(state.currentIndex) }
    val queuedCount = items.count { it.queued }

    Column(Modifier.fillMaxSize().glass(RoundedCornerShape(28.dp), 0.6f)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fila • ${items.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (queuedCount > 0) "$queuedCount adicionada(s) por você tocam antes do restante"
                    else "Adicione músicas à fila: elas tocam antes do restante da playlist",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (queuedCount > 0) TextButton(vm::clearQueue) { Text("Limpar fila") }
        }
        Text(
            "Arraste para a esquerda para remover.",
            Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(state = listState) {
            items(items, key = { it.key }) { entry ->
                ReorderableItem(reorder, key = entry.key) { isDragging ->
                    val song = entry.song
                    val index = items.indexOfFirst { it.key == entry.key }
                    val current = index == state.currentIndex
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                val i = items.indexOfFirst { it.key == entry.key }
                                if (i >= 0) {
                                    items = items.toMutableList().apply { removeAt(i) }
                                    vm.removeFromQueue(i)
                                }
                            }
                            false
                        },
                    )
                    val swiping = dismissState.dismissDirection != SwipeToDismissBoxValue.Settled
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false, // já está na lista
                        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    when {
                                        isDragging || swiping -> MaterialTheme.colorScheme.surfaceVariant
                                        entry.queued -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else -> Color.Transparent
                                    },
                                )
                                .clickable { vm.skipTo(items.indexOfFirst { it.key == entry.key }) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(song.artworkPath, Modifier.size(44.dp), RoundedCornerShape(10.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(
                                    if (entry.queued) "Na fila • ${song.artist}" else song.artist,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.queued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (current) Icon(Icons.Rounded.GraphicEq, "Tocando agora", tint = MaterialTheme.colorScheme.primary)
                            IconButton({ vm.removeFromQueue(items.indexOfFirst { it.key == entry.key }) }) { Icon(Icons.Rounded.Close, "Remover da fila") }
                            Icon(Icons.Rounded.DragHandle, "Reordenar", Modifier.draggableHandle().padding(6.dp))
                        }
                    }
                }
            }
        }
    }
}
