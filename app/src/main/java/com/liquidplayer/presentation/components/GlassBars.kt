package com.liquidplayer.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.player.PlayerState
import com.liquidplayer.presentation.theme.GlassIconButton
import com.liquidplayer.presentation.theme.glass
import kotlinx.coroutines.flow.Flow

data class BottomTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun GlassBottomBar(tabs: List<BottomTab>, selectedRoute: String?, onSelect: (BottomTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).glass(shape, 1f).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == selectedRoute
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent,
                label = "tabBg",
            )
            val fg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabFg",
            )
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(bg).clickable { onSelect(tab) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(24.dp))
                Text(tab.label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
            }
        }
    }
}

@Composable
fun MiniPlayer(
    state: PlayerState,
    positionFlow: Flow<Long>,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.currentSong ?: return
    val position by positionFlow.collectAsStateWithLifecycle(0L)
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp).glass(shape, 1f).clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkPath, Modifier.size(46.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassIconButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pausar" else "Tocar", onPlayPause, size = 42.dp, filled = true)
            Spacer(Modifier.width(6.dp))
            IconButton(onNext) { Icon(Icons.Rounded.SkipNext, "Próxima") }
        }
        val progress = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = androidx.compose.ui.graphics.Color.Transparent,
        )
    }
}
