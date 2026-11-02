package com.liquidplayer.data.repository

import android.content.Context
import android.net.Uri
import com.liquidplayer.data.local.EmbeddedLyricsReader
import com.liquidplayer.domain.model.Lyrics
import com.liquidplayer.domain.model.LyricsSource
import com.liquidplayer.domain.model.Song
import com.liquidplayer.utils.LrcParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ordem de busca (tudo offline): .lrc/.txt ao lado da música → cache de letras baixadas → letra embutida.
 * A busca online (LRCLIB, API aberta e gratuita) só ocorre quando o usuário pede.
 */
@Singleton
class LyricsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val cacheDir = File(context.filesDir, "lyrics").apply { mkdirs() }

    suspend fun load(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val found = ArrayList<Lyrics>()
        song.lyricsUri?.let { readExternal(Uri.parse(it)) }?.let { found += it }
        if (found.none { it.synced }) cached(song.id)?.let { found += it }
        if (found.none { it.synced }) {
            EmbeddedLyricsReader.read(context, Uri.parse(song.uri))
                ?.let { LrcParser.parse(it, LyricsSource.EMBEDDED) }?.let { found += it }
        }
        found.firstOrNull { it.synced } ?: found.firstOrNull()
    }

    /** Busca na internet (LRCLIB) e guarda em cache local. Lança exceção se estiver offline. */
    suspend fun fetchOnline(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val seconds = song.durationMs / 1000
        val get = "https://lrclib.net/api/get?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}" +
            "&album_name=${enc(song.album)}&duration=$seconds"
        var json: JSONObject? = httpGet(get)?.let { JSONObject(it) }
        if (json == null) {
            val search = "https://lrclib.net/api/search?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}"
            val arr = httpGet(search)?.let { JSONArray(it) }
            if (arr != null && arr.length() > 0) json = arr.getJSONObject(0)
        }
        val result = json ?: return@withContext null
        val body = result.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: result.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: return@withContext null
        File(cacheDir, "${song.id}.lrc").writeText(body)
        LrcParser.parse(body, LyricsSource.ONLINE)
    }

    private fun cached(songId: Long): Lyrics? {
        val file = File(cacheDir, "$songId.lrc")
        return if (file.exists()) LrcParser.parse(file.readText(), LyricsSource.ONLINE) else null
    }

    private fun readExternal(uri: Uri): Lyrics? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes?.let { LrcParser.parse(decode(it), LyricsSource.EXTERNAL_LRC) }
            ?.let { if (it.synced) it else it.copy(source = LyricsSource.EXTERNAL_TXT) }
    } catch (e: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, charset("windows-1252"))
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "LiquidPlayer/1.0 (Android local music player)")
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }
}
