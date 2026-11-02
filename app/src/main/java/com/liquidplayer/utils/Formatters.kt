package com.liquidplayer.utils

import java.text.DateFormat
import java.util.Date

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTotalDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "$h h $m min"
        else -> "$m min"
    }
}

fun formatDate(epochMs: Long): String =
    if (epochMs <= 0) "nunca" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

fun pluralSongs(n: Int) = if (n == 1) "1 música" else "$n músicas"
