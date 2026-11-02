package com.liquidplayer.utils

import com.liquidplayer.domain.model.LyricLine
import com.liquidplayer.domain.model.Lyrics
import com.liquidplayer.domain.model.LyricsSource

/** Interpreta texto de letra: formato LRC (com tempos) ou texto simples. */
object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[A-Za-z]{2,}:.*]$""")
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String, source: LyricsSource): Lyrics? {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            val matches = timestamp.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val content = wordTag.replace(line.substring(matches.last().range.last + 1), "").trim()
                for (m in matches) {
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    timed += LyricLine((min * 60_000 + sec * 1000 + ms + offset).coerceAtLeast(0), content)
                }
            } else if (line.isNotEmpty() && !metaTag.matches(line)) {
                plain += line
            } else if (line.isEmpty()) {
                plain += ""
            }
        }
        if (timed.size >= 2) {
            return Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = source)
        }
        val cleaned = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (cleaned.isEmpty()) return null
        return Lyrics(cleaned.map { LyricLine(0, it) }, synced = false, source = source)
    }
}
