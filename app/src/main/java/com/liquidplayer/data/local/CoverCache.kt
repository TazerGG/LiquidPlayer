package com.liquidplayer.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Guarda miniaturas das capas (extraídas dos metadados ou de cover.jpg) no armazenamento interno.
 * Os arquivos de ÁUDIO nunca são copiados — só as imagens reduzidas, deduplicadas por hash.
 */
@Singleton
class CoverCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val playlistDir = File(context.filesDir, "playlist_covers").apply { mkdirs() }

    fun saveArtwork(bytes: ByteArray): String? {
        return try {
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = File(coversDir, "$hash.jpg")
            if (!file.exists()) {
                val bitmap = decodeScaled(bytes, MAX_COVER) ?: return null
                writeJpeg(bitmap, file)
                bitmap.recycle()
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun saveFromUri(uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            saveArtwork(bytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Capa escolhida pelo usuário para uma playlist (photo picker / SAF). */
    fun savePlaylistCover(uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bitmap = decodeScaled(bytes, MAX_PLAYLIST_COVER) ?: return null
            val file = File(playlistDir, "${UUID.randomUUID()}.jpg")
            writeJpeg(bitmap, file)
            bitmap.recycle()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun deletePlaylistCover(path: String?) {
        if (path != null && path.startsWith(playlistDir.absolutePath)) File(path).delete()
    }

    /** Remove capas que nenhuma música usa mais. */
    fun cleanup(inUse: Set<String>) {
        coversDir.listFiles()?.forEach { if (it.absolutePath !in inUse) it.delete() }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    private fun decodeScaled(bytes: ByteArray, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        val scaled = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private companion object {
        const val MAX_COVER = 640
        const val MAX_PLAYLIST_COVER = 800
    }
}
