package com.liquidplayer.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ScannedFile(
    val docUri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    /** Caminho relativo à pasta raiz escolhida ("" = diretamente na raiz). Ex.: "Artista X/Álbum". */
    val relativePath: String,
    val mimeType: String,
    val lyricsUri: String?,
    val coverUri: Uri?,
)

data class WalkResult(val audio: List<ScannedFile>, val unsupported: List<String>)

/**
 * Percorre uma árvore do Storage Access Framework consultando o ContentResolver diretamente
 * (bem mais rápido que DocumentFile). Só enxerga o que o usuário autorizou.
 */
@Singleton
class FolderScanner @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun walk(treeUri: Uri): WalkResult {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val audio = ArrayList<ScannedFile>()
        val unsupported = ArrayList<String>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to "")

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (docId, rel) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val isRoot = docId == rootId
            val cursor = try {
                resolver.query(childrenUri, PROJECTION, null, null, null)
            } catch (e: Exception) {
                if (isRoot) throw IOException("Pasta indisponível", e)
                null
            }
            if (cursor == null) {
                if (isRoot) throw IOException("Pasta indisponível")
                continue
            }

            val dirAudio = ArrayList<ScannedFile>()
            val lrc = HashMap<String, Uri>()
            val txt = HashMap<String, Uri>()
            var cover: Uri? = null

            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == Document.MIME_TYPE_DIR) {
                        stack.addLast(id to if (rel.isEmpty()) name else "$rel/$name")
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val base = name.substringBeforeLast('.', name).lowercase()
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    when {
                        ext in AUDIO_EXTENSIONS -> dirAudio += ScannedFile(
                            docUri, name, c.getLong(3), c.getLong(4), rel, mime.ifEmpty { "audio/*" }, null, null,
                        )
                        ext == "lrc" -> lrc[base] = docUri
                        ext == "txt" -> txt[base] = docUri
                        ext in IMAGE_EXTENSIONS && base in COVER_NAMES && cover == null -> cover = docUri
                        mime.startsWith("audio/") || ext in OTHER_AUDIO_EXTENSIONS ->
                            unsupported += if (rel.isEmpty()) name else "$rel/$name"
                    }
                }
            }
            dirAudio.mapTo(audio) { f ->
                val base = f.name.substringBeforeLast('.').lowercase()
                f.copy(lyricsUri = (lrc[base] ?: txt[base])?.toString(), coverUri = cover)
            }
        }
        return WalkResult(audio, unsupported)
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "oga", "opus")
        val OTHER_AUDIO_EXTENSIONS = setOf("wma", "ape", "wv", "mka", "amr", "mid", "midi", "aif", "aiff", "ac3", "dts")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart", "artwork")
    }
}
