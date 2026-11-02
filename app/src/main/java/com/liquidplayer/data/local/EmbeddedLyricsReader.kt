package com.liquidplayer.data.local

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.min

/**
 * Lê letras embutidas sem bibliotecas externas:
 *  - MP3: frame USLT do ID3v2 (2.2, 2.3 e 2.4)
 *  - FLAC / OGG / OPUS: comentário Vorbis LYRICS / UNSYNCEDLYRICS
 *  - M4A/MP4: átomo ©lyr (quando o "moov" está no início do arquivo)
 */
object EmbeddedLyricsReader {
    private const val MAX_ID3 = 8 * 1024 * 1024
    private const val MAX_VORBIS_SCAN = 1024 * 1024
    private const val MAX_MP4_SCAN = 8 * 1024 * 1024

    fun read(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = input.readUpTo(10)
            when {
                head.size < 10 -> null
                head.startsWithAscii("ID3") -> readId3(input, head)
                head.startsWithAscii("fLaC") || head.startsWithAscii("OggS") ->
                    scanVorbis(head + input.readUpTo(MAX_VORBIS_SCAN))
                String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> scanMp4(head + input.readUpTo(MAX_MP4_SCAN))
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    // ---------- ID3v2 ----------
    private fun readId3(input: InputStream, head: ByteArray): String? {
        val version = head[3].toInt()
        val flags = head[5].toInt()
        val size = syncsafe(head, 6)
        val tag = input.readUpTo(min(size, MAX_ID3))
        var offset = 0
        if (version >= 3 && flags and 0x40 != 0 && tag.size >= 4) {
            offset = if (version == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
        }
        if (version == 2) {
            while (offset + 6 <= tag.size) {
                if (tag[offset] == 0.toByte()) break
                val id = String(tag, offset, 3, Charsets.ISO_8859_1)
                val fsize = (tag[offset + 3].toInt() and 0xFF shl 16) or
                    (tag[offset + 4].toInt() and 0xFF shl 8) or (tag[offset + 5].toInt() and 0xFF)
                if (fsize <= 0 || offset + 6 + fsize > tag.size) break
                if (id == "ULT") return parseUslt(tag.copyOfRange(offset + 6, offset + 6 + fsize))
                offset += 6 + fsize
            }
            return null
        }
        while (offset + 10 <= tag.size) {
            if (tag[offset] == 0.toByte()) break
            val id = String(tag, offset, 4, Charsets.ISO_8859_1)
            val fsize = if (version == 4) syncsafe(tag, offset + 4) else int32(tag, offset + 4)
            if (fsize <= 0 || offset + 10 + fsize > tag.size) break
            if (id == "USLT") return parseUslt(tag.copyOfRange(offset + 10, offset + 10 + fsize))
            offset += 10 + fsize
        }
        return null
    }

    private fun parseUslt(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt()
        var i = 4 // encoding(1) + language(3)
        if (enc == 1 || enc == 2) {
            while (i + 1 < body.size && !(body[i] == 0.toByte() && body[i + 1] == 0.toByte())) i += 2
            i += 2
        } else {
            while (i < body.size && body[i] != 0.toByte()) i++
            i += 1
        }
        if (i >= body.size) return null
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(body, i, body.size - i, charset).trim().ifBlank { null }
    }

    // ---------- Vorbis comments (FLAC / OGG / OPUS) ----------
    private fun scanVorbis(data: ByteArray): String? {
        val lower = ByteArray(data.size) { val b = data[it].toInt(); if (b in 65..90) (b + 32).toByte() else data[it] }
        for (key in listOf("unsyncedlyrics=", "lyrics=")) {
            val needle = key.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val pos = indexOf(lower, needle, from)
                if (pos < 0) break
                from = pos + 1
                if (pos < 4) continue
                val len = (data[pos - 4].toInt() and 0xFF) or (data[pos - 3].toInt() and 0xFF shl 8) or
                    (data[pos - 2].toInt() and 0xFF shl 16) or (data[pos - 1].toInt() and 0xFF shl 24)
                if (len < needle.size || len > 2_000_000 || pos + len > data.size) continue
                val text = String(data, pos + needle.size, len - needle.size, Charsets.UTF_8).trim()
                if (text.isNotEmpty()) return text
            }
        }
        return null
    }

    // ---------- MP4 ----------
    private fun scanMp4(data: ByteArray): String? {
        val needle = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val pos = indexOf(data, needle, 0)
        if (pos < 0 || pos + 24 > data.size) return null
        val boxStart = pos + 4
        val boxSize = int32(data, boxStart)
        if (String(data, boxStart + 4, 4, Charsets.ISO_8859_1) != "data" || boxSize < 16) return null
        val end = min(boxStart + boxSize, data.size)
        return String(data, boxStart + 16, end - boxStart - 16, Charsets.UTF_8).trim().ifBlank { null }
    }

    // ---------- util ----------
    private fun InputStream.readUpTo(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = read(buffer, total, n - total)
            if (r < 0) break
            total += r
        }
        return if (total == n) buffer else buffer.copyOf(total)
    }

    private fun ByteArray.startsWithAscii(s: String) = size >= s.length && s.indices.all { this[it].toInt() == s[it].code }

    private fun syncsafe(b: ByteArray, o: Int) =
        (b[o].toInt() and 0x7F shl 21) or (b[o + 1].toInt() and 0x7F shl 14) or
            (b[o + 2].toInt() and 0x7F shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or
            (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        val last = hay.size - needle.size
        var i = from
        outer@ while (i <= last) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
