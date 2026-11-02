package com.liquidplayer.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.withTransaction
import com.liquidplayer.data.database.AppDatabase
import com.liquidplayer.data.database.QueueDao
import com.liquidplayer.data.database.QueueItemEntity
import com.liquidplayer.data.database.SongDao
import com.liquidplayer.data.repository.HistoryRepository
import com.liquidplayer.data.repository.PrefKeys
import com.liquidplayer.data.repository.SettingsRepository
import com.liquidplayer.data.toDomain
import com.liquidplayer.di.ApplicationScope
import com.liquidplayer.domain.model.RepeatMode
import com.liquidplayer.domain.model.Song
import com.liquidplayer.domain.model.UNKNOWN_ALBUM
import com.liquidplayer.domain.model.UNKNOWN_ARTIST
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Ponte entre a UI e o PlaybackService (via MediaController).
 * A fila é a própria playlist do player — por isso é independente de qual playlist está aberta na UI.
 * A fila, a faixa atual e a posição são salvas no Room para serem restauradas ao reabrir o app.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val queueDao: QueueDao,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var connecting = false
    private val songCache = ConcurrentHashMap<Long, Song>()
    private var cachedQueue: List<Song> = emptyList()
    private var lastLoggedId = -1L
    private var persistJob: Job? = null
    private var queueDirty = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Fluxo frio: só consulta a posição enquanto alguém (UI visível) estiver coletando — poupa bateria. */
    val positionMs: Flow<Long> = flow {
        while (true) {
            val c = controller
            emit(c?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            delay(if (c?.isPlaying == true) 250L else 500L)
        }
    }

    val volumeFraction: Flow<Float> = flow {
        while (true) {
            emit(getVolume())
            delay(400L)
        }
    }.distinctUntilChanged()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val timelineChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED)
            if (timelineChanged) queueDirty = true
            refresh(rebuildQueue = timelineChanged)
            schedulePersist()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _events.tryEmit("Não foi possível reproduzir \"${_state.value.currentSong?.title ?: "o arquivo"}\".")
        }
    }

    // ------------------------------------------------------------------ conexão
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(listener)
            scope.launch {
                restoreIfNeeded(c)
                refresh(rebuildQueue = true)
            }
            startPeriodicPersist()
        }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun restoreIfNeeded(c: MediaController) {
        if (c.mediaItemCount > 0) return // o serviço continuou vivo: a fila já está lá
        if (!settings.current().restoreQueue) return
        val saved = queueDao.getAll()
        if (saved.isEmpty()) return
        val byId = songDao.getByIds(saved.map { it.songId }).associateBy { it.id }
        val songs = saved.mapNotNull { byId[it.songId] }.filter { it.isAvailable }.map { it.toDomain() }
        if (songs.isEmpty()) return
        songs.forEach { songCache[it.id] = it }
        val currentId = settings.get(PrefKeys.PLAYER_INDEX_SONG)?.toLongOrNull()
        val index = songs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val position = if (songs[index].id == currentId) settings.get(PrefKeys.PLAYER_POSITION)?.toLongOrNull() ?: 0L else 0L
        c.shuffleModeEnabled = settings.get(PrefKeys.PLAYER_SHUFFLE)?.toBooleanStrictOrNull() ?: false
        c.repeatMode = settings.get(PrefKeys.PLAYER_REPEAT)?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() }
            .let { it ?: RepeatMode.OFF }.toPlayerMode()
        c.setMediaItems(songs.map { it.toMediaItem() }, index, position)
        c.prepare() // pausado: só toca quando o usuário apertar play
    }

    // ------------------------------------------------------------------ estado
    private fun refresh(rebuildQueue: Boolean) {
        val c = controller ?: return
        if (rebuildQueue || cachedQueue.size != c.mediaItemCount) {
            cachedQueue = List(c.mediaItemCount) { toSong(c.getMediaItemAt(it)) }
        }
        val index = c.currentMediaItemIndex
        val current = cachedQueue.getOrNull(index)
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0L
        _state.value = PlayerState(
            currentSong = current,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = duration,
            queue = cachedQueue,
            currentIndex = if (current == null) -1 else index,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            connected = true,
        )
        if (c.isPlaying && current != null && current.id != lastLoggedId) {
            lastLoggedId = current.id
            scope.launch { history.record(current.id) }
        }
    }

    private fun toSong(item: MediaItem): Song {
        val id = item.mediaId.toLongOrNull() ?: -1L
        songCache[id]?.let { return it }
        val m = item.mediaMetadata
        return Song(
            id = id, uri = item.localConfiguration?.uri?.toString().orEmpty(), folderId = 0, fileName = "",
            title = m.title?.toString().orEmpty(), artist = m.artist?.toString() ?: UNKNOWN_ARTIST,
            album = m.albumTitle?.toString() ?: UNKNOWN_ALBUM, durationMs = m.extras?.getLong(EXTRA_DURATION) ?: 0L,
            mimeType = "", artworkPath = m.artworkUri?.path, lyricsUri = null, trackNumber = 0,
            isAvailable = true, addedAt = 0,
        )
    }

    // ------------------------------------------------------------------ reprodução
    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean? = null) {
        val c = controller ?: return
        val playable = songs.filter { it.isAvailable }
        if (playable.isEmpty()) {
            _events.tryEmit("Nenhuma música disponível para reproduzir.")
            return
        }
        if (playable.size != songs.size) _events.tryEmit("${songs.size - playable.size} arquivo(s) indisponível(is) ignorado(s).")
        val startId = songs.getOrNull(startIndex)?.id
        val index = playable.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        playable.forEach { songCache[it.id] = it }
        c.setMediaItems(playable.map { it.toMediaItem() }, index, 0L)
        shuffle?.let { c.shuffleModeEnabled = it }
        c.prepare()
        c.play()
    }

    fun playShuffled(songs: List<Song>) {
        val playable = songs.filter { it.isAvailable }
        if (playable.isEmpty()) return play(songs)
        play(playable, Random.nextInt(playable.size), shuffle = true)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
            c.play()
        }
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------------------------ fila
    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        val playable = songs.filter { it.isAvailable }
        if (c.mediaItemCount == 0) return play(playable)
        val present = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }.toHashSet()
        val fresh = playable.filter { it.id.toString() !in present }
        if (fresh.isEmpty()) {
            _events.tryEmit("Já está na fila.")
            return
        }
        fresh.forEach { songCache[it.id] = it }
        c.addMediaItems(fresh.map { it.toMediaItem() })
        _events.tryEmit(if (fresh.size == 1) "Adicionada à fila." else "${fresh.size} músicas adicionadas à fila.")
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (!song.isAvailable) return
        if (c.mediaItemCount == 0) return play(listOf(song))
        songCache[song.id] = song
        val target = c.currentMediaItemIndex + 1
        val existing = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }.indexOf(song.id.toString())
        when {
            existing == c.currentMediaItemIndex -> return
            existing >= 0 -> c.moveMediaItem(existing, if (existing < target) target - 1 else target)
            else -> c.addMediaItem(target, song.toMediaItem())
        }
        _events.tryEmit("Tocará a seguir.")
    }

    fun removeFromQueue(index: Int) { controller?.removeMediaItem(index) }

    /** Remove uma música da fila pelo id (usado no gesto de arrastar da direita para a esquerda). */
    fun removeSongFromQueue(songId: Long) {
        val c = controller ?: return
        val index = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }.indexOf(songId.toString())
        if (index < 0) {
            _events.tryEmit("Esta música não está na fila.")
            return
        }
        c.removeMediaItem(index)
        _events.tryEmit("Removida da fila.")
    }
    fun moveQueueItem(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun skipToQueueItem(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0L)
        c.play()
    }
    fun clearQueue() { controller?.clearMediaItems() }

    // ------------------------------------------------------------------ volume (volume de mídia do aparelho)
    fun getVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(fraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt().coerceIn(0, max), 0)
    }

    // ------------------------------------------------------------------ persistência
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1000)
            persistNow()
        }
    }

    private fun startPeriodicPersist() {
        scope.launch {
            _state.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                if (playing) while (true) {
                    delay(10_000)
                    persistNow()
                }
            }
        }
    }

    private suspend fun persistNow() {
        val c = controller ?: return
        if (queueDirty) {
            val ids = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId.toLongOrNull() ?: -1L }.filter { it > 0 }
            db.withTransaction {
                queueDao.clear()
                queueDao.insertAll(ids.mapIndexed { i, id -> QueueItemEntity(i, id) })
            }
            queueDirty = false
        }
        settings.setMany(
            mapOf(
                PrefKeys.PLAYER_INDEX_SONG to (_state.value.currentSong?.id ?: -1L),
                PrefKeys.PLAYER_POSITION to c.currentPosition.coerceAtLeast(0L),
                PrefKeys.PLAYER_SHUFFLE to c.shuffleModeEnabled,
                PrefKeys.PLAYER_REPEAT to _state.value.repeat.name,
            ),
        )
    }

    private fun RepeatMode.toPlayerMode() = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }
}
