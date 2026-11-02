package com.liquidplayer.presentation.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val colorCache = LruCache<String, Int>(64)

/** Extrai a cor dominante da capa (Palette) fora da Main Thread, com cache. */
@Composable
fun rememberDominantColor(path: String?, fallback: Color): Color {
    var color by remember(path) { mutableStateOf(path?.let { colorCache.get(it) }?.let { Color(it) } ?: fallback) }
    LaunchedEffect(path, fallback) {
        if (path == null) { color = fallback; return@LaunchedEffect }
        colorCache.get(path)?.let { color = Color(it); return@LaunchedEffect }
        val argb = withContext(Dispatchers.IO) {
            try {
                val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return@withContext null
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val c = p.getVibrantColor(p.getMutedColor(p.getDominantColor(0)))
                bmp.recycle()
                if (c == 0) null else c
            } catch (e: Exception) { null }
        }
        if (argb != null) { colorCache.put(path, argb); color = Color(argb) } else color = fallback
    }
    return color
}
