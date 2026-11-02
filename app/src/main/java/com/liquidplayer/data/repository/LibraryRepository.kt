package com.liquidplayer.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.liquidplayer.data.database.*
import com.liquidplayer.data.local.CoverCache
import com.liquidplayer.data.local.FolderScanner
import com.liquidplayer.data.local.MetadataReader
import com.liquidplayer.data.local.ScannedFile
import com.liquidplayer.data.toDomain
import com.liquidplayer.di.ApplicationScope
import com.liquidplayer.domain.model.MusicFolder
import com.liquidplayer.domain.model.ScanResult
import com.liquidplayer.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val folderName: String, val processed: Int, val total: Int) : ScanState
}

/**
 * Biblioteca musical: pastas autorizadas via SAF + músicas indexadas no Room.
 * Nenhuma varredura automática do armazenamento: só as pastas que o usuário adicionar.
 */
@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val songDao: SongDao,
    private val folderDao: FolderDao,
    private val folderSongDao: FolderSongDao,
    private val scanner: FolderScanner,
    private val metadataReader: MetadataReader,
    private val coverCache: CoverCache,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val scanMutex = Mutex()
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
    private val _scanResults = MutableSharedFlow<ScanResult>(extraBufferCapacity = 16)
    val scanResults: SharedFlow<ScanResult> = _scanResults.asSharedFlow()

    fun observeSongs(): Flow<List<Song>> = songDao.observeAll().map { list -> list.map { it.toDomain() } }
    fun observeFolders(): Flow<List<MusicFolder>> = folderDao.observeAll().map { list -> list.map { it.toDomain() } }
    fun observeUnavailableCount(): Flow<Int> = songDao.observeUnavailableCount()
    suspend fun getSong(id: Long): Song? = songDao.getById(id)?.toDomain()

    // ---------------------------------------------------------------- pastas
    /** @return id da pasta e se ela é nova (false = já estava na biblioteca). */
    suspend fun addFolder(treeUri: Uri): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // alguns provedores não oferecem permissão persistente; a varredura acusará se faltar acesso
        }
        val existing = folderDao.getByUri(treeUri.toString())
        if (existing != null) return@withContext existing.id to false
        val id = folderDao.insert(
            FolderEntity(treeUri = treeUri.toString(), name = displayName(treeUri), addedAt = System.currentTimeMillis()),
        )
        id to true
    }

    suspend fun removeFolder(folderId: Long) = withContext(Dispatchers.IO) {
        val folder = folderDao.getById(folderId) ?: return@withContext
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        folderDao.deleteById(folderId) // cascata remove as músicas da pasta (arquivos originais ficam intactos)
        coverCache.cleanup(songDao.allArtworkPaths().toSet())
    }

    /** Pasta perdeu a permissão (ou foi movida): usuário escolhe de novo; músicas são reconectadas por impressão digital. */
    suspend fun reauthorize(folderId: Long, newUri: Uri) = withContext(Dispatchers.IO) {
        val folder = folderDao.getById(folderId) ?: return@withContext
        try {
            context.contentResolver.takePersistableUriPermission(newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        if (newUri.toString() != folder.treeUri && folderDao.getByUri(newUri.toString()) != null) return@withContext
        markAllUnavailable(folderId)
        folderDao.update(folder.copy(treeUri = newUri.toString(), accessible = true))
    }

    suspend fun removeUnavailable(): Int = withContext(Dispatchers.IO) { songDao.deleteUnavailable() }

    fun hasPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    // ---------------------------------------------------------------- varredura
    fun scheduleScan(folderId: Long, isNew: Boolean = false) {
        appScope.launch {
            val result = scanMutex.withLock { scan(folderId, isNew) }
            _scanState.value = ScanState.Idle
            _scanResults.emit(result)
        }
    }

    fun scheduleScanAll() {
        appScope.launch(Dispatchers.IO) { folderDao.getAll().forEach { scheduleScan(it.id) } }
    }

    private suspend fun scan(folderId: Long, isNew: Boolean): ScanResult = withContext(Dispatchers.IO) {
        val folder = folderDao.getById(folderId)
            ?: return@withContext ScanResult(folderId, "?", isNew, error = "Pasta não encontrada.")
        _scanState.value = ScanState.Scanning(folder.name, 0, 0)
        val treeUri = Uri.parse(folder.treeUri)

        if (!hasPermission(treeUri)) {
            markAllUnavailable(folderId)
            folderDao.update(folder.copy(accessible = false))
            return@withContext ScanResult(
                folderId, folder.name, isNew,
                error = "A permissão de acesso a esta pasta foi perdida. Reautorize a pasta para voltar a usá-la.",
            )
        }
        val walk = try {
            scanner.walk(treeUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markAllUnavailable(folderId)
            folderDao.update(folder.copy(accessible = false))
            return@withContext ScanResult(
                folderId, folder.name, isNew,
                error = "Não foi possível ler a pasta. Ela pode ter sido movida, renomeada ou removida.",
            )
        }

        val existing = songDao.getByFolder(folderId)
        val byUri = existing.associateBy { it.uri }
        val found = HashSet<String>(walk.audio.size * 2).also { set -> walk.audio.forEach { set += it.docUri.toString() } }

        // 1) arquivos que sumiram -> indisponíveis (mantemos para não quebrar playlists/favoritos)
        val missing = existing.filter { it.isAvailable && it.uri !in found }.map { it.id }
        missing.chunked(400).forEach { songDao.setAvailable(it, false) }

        // 2) separa o que precisa ler metadados (novos/alterados)
        val toRead = ArrayList<ScannedFile>()
        for (f in walk.audio) {
            val ex = byUri[f.docUri.toString()]
            when {
                ex == null -> toRead += f
                ex.sizeBytes != f.size || ex.lastModified != f.lastModified -> toRead += f
                else -> {
                    if (!ex.isAvailable) songDao.setAvailable(listOf(ex.id), true)
                    if (ex.lyricsUri != f.lyricsUri) songDao.setLyricsUri(ex.id, f.lyricsUri)
                }
            }
        }

        var added = 0
        var updated = 0
        var relinked = 0
        var duplicates = 0
        val failed = ArrayList<String>()
        val dupTargets = HashMap<String, Long>()      // uri duplicada -> id da música canônica (já no banco)
        val dupPending = HashMap<String, String>()    // uri duplicada -> uri da 1ª ocorrência (ainda a inserir)
        val pendingFingerprints = HashMap<String, String>()
        val folderCovers = HashMap<String, String?>()
        val semaphore = Semaphore(4)
        var processed = 0
        _scanState.value = ScanState.Scanning(folder.name, 0, toRead.size)

        for (chunk in toRead.chunked(40)) {
            ensureActive()
            val metas = coroutineScope {
                chunk.map { f -> async { semaphore.withPermit { f to metadataReader.read(f.docUri, f.name) } } }.awaitAll()
            }
            val newSongs = ArrayList<SongEntity>()
            for ((f, meta) in metas) {
                val uriStr = f.docUri.toString()
                if (meta == null) {
                    failed += if (f.relativePath.isEmpty()) f.name else "${f.relativePath}/${f.name}"
                    continue
                }
                val art = meta.artworkPath ?: f.coverUri?.let { cu ->
                    folderCovers.getOrPut(cu.toString()) { coverCache.saveFromUri(cu) }
                }
                val fp = fingerprint(f.size, meta.durationMs, meta.title, meta.artist)
                val ex = byUri[uriStr]
                if (ex != null) {
                    songDao.update(
                        ex.copy(
                            title = meta.title, artist = meta.artist, album = meta.album, durationMs = meta.durationMs,
                            trackNumber = meta.trackNumber, artworkPath = art, sizeBytes = f.size,
                            lastModified = f.lastModified, lyricsUri = f.lyricsUri, fingerprint = fp, isAvailable = true,
                        ),
                    )
                    updated++
                    continue
                }
                val same = songDao.findByFingerprint(fp)
                if (same != null) {
                    if (!same.isAvailable) { // arquivo movido/renomeado/pasta reautorizada: reconecta
                        songDao.update(
                            same.copy(
                                uri = uriStr, folderId = folderId, fileName = f.name, sizeBytes = f.size,
                                lastModified = f.lastModified, lyricsUri = f.lyricsUri,
                                artworkPath = same.artworkPath ?: art, isAvailable = true,
                            ),
                        )
                        relinked++
                    } else if (same.uri != uriStr) {
                        dupTargets[uriStr] = same.id
                        duplicates++
                    }
                    continue
                }
                val firstUri = pendingFingerprints[fp]
                if (firstUri != null) {
                    dupPending[uriStr] = firstUri
                    duplicates++
                    continue
                }
                pendingFingerprints[fp] = uriStr
                newSongs += SongEntity(
                    uri = uriStr, folderId = folderId, fileName = f.name, title = meta.title, artist = meta.artist,
                    album = meta.album, durationMs = meta.durationMs, mimeType = f.mimeType, sizeBytes = f.size,
                    lastModified = f.lastModified, artworkPath = art, lyricsUri = f.lyricsUri,
                    trackNumber = meta.trackNumber, fingerprint = fp, addedAt = System.currentTimeMillis(),
                )
            }
            if (newSongs.isNotEmpty()) added += songDao.insertAll(newSongs).count { it != -1L }
            processed += chunk.size
            _scanState.value = ScanState.Scanning(folder.name, processed, toRead.size)
        }

        // 3) associação pasta ↔ músicas (inclui duplicatas, para playlists por pasta funcionarem)
        val idByUri = songDao.getByFolder(folderId).associate { it.uri to it.id }
        val members = walk.audio.mapNotNull { f ->
            val u = f.docUri.toString()
            val id = idByUri[u] ?: dupTargets[u] ?: dupPending[u]?.let { idByUri[it] }
            id?.let { FolderSongEntity(folderId, it, f.relativePath) }
        }
        db.withTransaction {
            folderSongDao.clear(folderId)
            folderSongDao.insertAll(members)
        }
        folderDao.getById(folderId)?.let {
            folderDao.update(it.copy(lastScanAt = System.currentTimeMillis(), accessible = true))
        }
        coverCache.cleanup(songDao.allArtworkPaths().toSet())

        ScanResult(
            folderId = folderId, folderName = folder.name, isNewFolder = isNew, totalFiles = walk.audio.size,
            added = added, updated = updated, relinked = relinked, markedUnavailable = missing.size,
            duplicates = duplicates, unsupported = walk.unsupported, failed = failed,
            hasSubfolders = walk.audio.any { it.relativePath.isNotEmpty() },
        )
    }

    private suspend fun markAllUnavailable(folderId: Long) {
        songDao.getByFolder(folderId).map { it.id }.chunked(400).forEach { songDao.setAvailable(it, false) }
    }

    private fun fingerprint(size: Long, durationMs: Long, title: String, artist: String) =
        "$size|${durationMs / 1000}|${normalize(title)}|${normalize(artist)}"

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun displayName(treeUri: Uri): String {
        var name: String? = null
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) name = it.getString(0) }
        } catch (_: Exception) {
        }
        return name?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Pasta"
    }
}
