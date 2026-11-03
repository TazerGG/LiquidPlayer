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
 *
 * A lista do player tem duas camadas, como no Spotify:
 *  - FILA DO USUÁRIO: músicas adicionadas manualmente. Ficam logo depois da música atual e tocam ANTES do contexto.
 *    Aceitam qualquer música (mesmo já presente na playlist) e podem se repetir.
 *  - CONTEXTO: a playlist/álbum/lista que está tocando.
 * Cada entrada tem um mediaId único: "<songId>" (contexto) ou "<songId>#q<n>" (fila do usuário).
 *
 * O embaralhar é feito pelo app (o ExoPlayer fica sempre com shuffle desligado), para que a fila do usuário
 * continue tocando em primeiro lugar mesmo com o modo aleatório ativo.
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
    private var cachedEntries: List<QueueEntry> = emptyList()
    private var lastLoggedId = -1L
    private var persistJob: Job? = null
    private var queueDirty = false
    private var shuffleOn = false
    private var originalOrder: List<String> = emptyList() // chaves do contexto na ordem original
    private var queuedCounter = 0L
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
            val transition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            if (timelineChanged) queueDirty = true
            if (transition) removePlayedQueued()
            refresh(rebuildQueue = timelineChanged || transition)
            schedulePersist()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _events.tryEmit("Não foi possível reproduzir \"${_state.value.currentSong?.title ?: "o arquivo"}\".")
        }
    }

    // ------------------------------------------------------------------ chaves da fila
    private fun songIdOf(mediaId: String): Long = mediaId.substringBefore('#').toLongOrNull() ?: -1L
    private fun isQueued(mediaId: String): Boolean = mediaId.contains('#')
    private fun mediaIdAt(c: MediaController, i: Int): String = c.getMediaItemAt(i).mediaId
    private fun Song.toQueuedItem(): MediaItem = toMediaItem().buildUpon().setMediaId("$id#q${++queuedCounter}").build()

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
            c.shuffleModeEnabled = false // o embaralhar é do app
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
        val byId = songDao.getByIds(saved.map { it.songId }.distinct()).associateBy { it.id }
        val queuedPos = settings.get(PrefKeys.QUEUED_POSITIONS)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toHashSet()
            ?: hashSetOf()
        val entries = saved.mapNotNull { q -> byId[q.songId]?.takeIf { it.isAvailable }?.toDomain()?.let { q.position to it } }
        if (entries.isEmpty()) return
        entries.forEach { songCache[it.second.id] = it.second }
        val items = entries.map { (pos, song) -> if (pos in queuedPos) song.toQueuedItem() else song.toMediaItem() }

        val savedIndex = settings.get(PrefKeys.PLAYER_INDEX)?.toIntOrNull()
        val savedSongId = settings.get(PrefKeys.PLAYER_INDEX_SONG)?.toLongOrNull()
        var index = entries.indexOfFirst { it.first == savedIndex && it.second.id == savedSongId }
        if (index < 0) index = entries.indexOfFirst { it.second.id == savedSongId }
        if (index < 0) index = 0
        val position = if (entries[index].second.id == savedSongId) settings.get(PrefKeys.PLAYER_POSITION)?.toLongOrNull() ?: 0L else 0L

        shuffleOn = settings.get(PrefKeys.PLAYER_SHUFFLE)?.toBooleanStrictOrNull() ?: false
        originalOrder = settings.get(PrefKeys.ORIGINAL_ORDER)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val repeat = settings.get(PrefKeys.PLAYER_REPEAT)?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF
        c.repeatMode = repeat.toPlayerMode()
        c.setMediaItems(items, index, position)
        c.prepare() // pausado: só toca quando o usuário apertar play
    }

    // ------------------------------------------------------------------ estado
    private fun refresh(rebuildQueue: Boolean) {
        val c = controller ?: return
        if (rebuildQueue || cachedEntries.size != c.mediaItemCount) {
            cachedEntries = List(c.mediaItemCount) {
                val item = c.getMediaItemAt(it)
                QueueEntry(item.mediaId, toSong(item), isQueued(item.mediaId))
            }
        }
        val index = c.currentMediaItemIndex
        val current = cachedEntries.getOrNull(index)?.song
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0L
        _state.value = PlayerState(
            currentSong = current,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = duration,
            queue = cachedEntries.map { it.song },
            entries = cachedEntries,
            currentIndex = if (current == null) -1 else index,
            shuffle = shuffleOn,
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
        val id = songIdOf(item.mediaId)
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

    /** Músicas completas (com URI) para reconstruir itens; busca no banco o que não estiver em cache. */
    private suspend fun songsFor(ids: Set<Long>): Map<Long, Song> {
        val missing = ids.filter { it > 0 && !songCache.containsKey(it) }
        if (missing.isNotEmpty()) songDao.getByIds(missing).forEach { songCache[it.id] = it.toDomain() }
        return ids.mapNotNull { id -> songCache[id]?.let { id to it } }.toMap()
    }

    // ------------------------------------------------------------------ reprodução (contexto)
    /** Toca uma lista (playlist, álbum…) como NOVO contexto. A fila do usuário é substituída. */
    fun play(songs: List<Song>, startIndex: Int = 0) = startPlayback(songs, startIndex, shuffleOn)

    fun playShuffled(songs: List<Song>) {
        val playable = songs.filter { it.isAvailable }
        if (playable.isEmpty()) return play(songs)
        startPlayback(playable, Random.nextInt(playable.size), true)
    }

    private fun startPlayback(songs: List<Song>, startIndex: Int, shuffle: Boolean) {
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
        val items = playable.map { it.toMediaItem() }
        originalOrder = items.map { it.mediaId }
        shuffleOn = shuffle
        if (shuffle) {
            val rest = items.filterIndexed { i, _ -> i != index }.shuffled()
            c.setMediaItems(listOf(items[index]) + rest, 0, 0L)
        } else {
            c.setMediaItems(items, index, 0L)
        }
        c.prepare()
        c.play()
        refresh(rebuildQueue = true)
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

    /** Embaralha só o que ainda vai tocar do contexto; a fila do usuário continua primeiro. Desligar restaura a ordem original. */
    fun toggleShuffle() {
        val c = controller ?: return
        shuffleOn = !shuffleOn
        scope.launch {
            if (shuffleOn) originalOrder = List(c.mediaItemCount) { mediaIdAt(c, it) }.filter { !isQueued(it) }
            reorderUpcomingContext(c)
            queueDirty = true
            refresh(rebuildQueue = true)
            schedulePersist()
        }
    }

    private suspend fun reorderUpcomingContext(c: MediaController) {
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur < 0 || cur >= count - 1) return
        val keys = (cur + 1 until count).map { mediaIdAt(c, it) }
        val songs = songsFor(keys.map { songIdOf(it) }.toSet())
        val queued = keys.filter { isQueued(it) }
        val context = keys.filter { !isQueued(it) }
        val order = originalOrder.withIndex().associate { it.value to it.index }
        val newContext = if (shuffleOn) context.shuffled() else context.sortedBy { order[it] ?: Int.MAX_VALUE }
        val ordered = queued + newContext
        val items = ordered.mapNotNull { key ->
            songs[songIdOf(key)]?.let { s ->
                if (isQueued(key)) s.toMediaItem().buildUpon().setMediaId(key).build() else s.toMediaItem()
            }
        }
        if (items.size != ordered.size) return // faltou algum arquivo: não mexe
        if (c.mediaItemCount != count || c.currentMediaItemIndex != cur) return // a lista mudou enquanto esperávamos
        c.replaceMediaItems(cur + 1, count, items)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------------------------ fila do usuário
    /** Primeira posição depois da música atual e das músicas que o usuário já enfileirou. */
    private fun queuedInsertIndex(c: MediaController): Int {
        var i = c.currentMediaItemIndex + 1
        while (i < c.mediaItemCount && isQueued(mediaIdAt(c, i))) i++
        return i
    }

    /** Adiciona ao FIM da fila do usuário. Funciona mesmo que a música já esteja na playlist em reprodução. */
    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        val playable = songs.filter { it.isAvailable }
        if (playable.isEmpty()) {
            _events.tryEmit("Nenhuma música disponível para adicionar.")
            return
        }
        if (c.mediaItemCount == 0) return play(playable)
        playable.forEach { songCache[it.id] = it }
        c.addMediaItems(queuedInsertIndex(c), playable.map { it.toQueuedItem() })
        _events.tryEmit(if (playable.size == 1) "Adicionada à fila." else "${playable.size} músicas adicionadas à fila.")
    }

    /** "Tocar a seguir": entra na FRENTE da fila do usuário, logo depois da música atual. */
    fun playNext(song: Song) {
        val c = controller ?: return
        if (!song.isAvailable) return
        if (c.mediaItemCount == 0) return play(listOf(song))
        songCache[song.id] = song
        c.addMediaItem(c.currentMediaItemIndex + 1, song.toQueuedItem())
        _events.tryEmit("Tocará a seguir.")
    }

    /** Remove da fila do usuário uma música que o usuário havia enfileirado (não mexe no contexto). */
    fun removeSongFromQueue(songId: Long) {
        val c = controller ?: return
        val index = (c.currentMediaItemIndex + 1 until c.mediaItemCount).firstOrNull {
            val key = mediaIdAt(c, it)
            isQueued(key) && songIdOf(key) == songId
        }
        if (index == null) {
            _events.tryEmit("Esta música não está na sua fila.")
            return
        }
        c.removeMediaItem(index)
        _events.tryEmit("Removida da fila.")
    }

    /** Remove qualquer entrada (da fila ou do contexto) pela posição — usado na aba "Fila". */
    fun removeFromQueue(index: Int) { controller?.removeMediaItem(index) }
    fun moveQueueItem(from: Int, to: Int) { controller?.moveMediaItem(from, to) }

    fun skipToQueueItem(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0L)
        c.play()
    }

    /** Limpa só as músicas adicionadas pelo usuário; a playlist em reprodução continua. */
    fun clearQueue() {
        val c = controller ?: return
        var removed = 0
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (i != c.currentMediaItemIndex && isQueued(mediaIdAt(c, i))) {
                c.removeMediaItem(i)
                removed++
            }
        }
        _events.tryEmit(if (removed == 0) "Nenhuma música adicionada por você na fila." else "Fila limpa ($removed).")
    }

    /** Músicas da fila do usuário já tocadas saem da lista (como no Spotify). */
    private fun removePlayedQueued() {
        val c = controller ?: return
        for (i in c.currentMediaItemIndex - 1 downTo 0) {
            if (isQueued(mediaIdAt(c, i))) c.removeMediaItem(i)
        }
    }

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
            val keys = List(c.mediaItemCount) { mediaIdAt(c, it) }
            val ids = keys.map { songIdOf(it) }.filter { it > 0 }
            if (ids.size == keys.size) {
                db.withTransaction {
                    queueDao.clear()
                    queueDao.insertAll(ids.mapIndexed { i, id -> QueueItemEntity(i, id) })
                }
                settings.setMany(
                    mapOf(
                        PrefKeys.QUEUED_POSITIONS to keys.withIndex().filter { isQueued(it.value) }.joinToString(",") { it.index.toString() },
                        PrefKeys.ORIGINAL_ORDER to originalOrder.joinToString(","),
                    ),
                )
                queueDirty = false
            }
        }
        settings.setMany(
            mapOf(
                PrefKeys.PLAYER_INDEX_SONG to (_state.value.currentSong?.id ?: -1L),
                PrefKeys.PLAYER_INDEX to c.currentMediaItemIndex,
                PrefKeys.PLAYER_POSITION to c.currentPosition.coerceAtLeast(0L),
                PrefKeys.PLAYER_SHUFFLE to shuffleOn,
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
