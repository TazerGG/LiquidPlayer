package com.liquidplayer.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.liquidplayer.domain.model.AppSettings
import com.liquidplayer.domain.model.ThemeMode

data class GlassStyle(val intensity: Float = 0.6f, val dark: Boolean = true, val cornerScale: Float = 1f)

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
val LocalGlassStyle = staticCompositionLocalOf { GlassStyle() }

@Composable
fun isDarkTheme(settings: AppSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Tema Material 3 + parâmetros do Liquid Glass. Trocar tema/cor/vidro é instantâneo (sem reiniciar). */
@Composable
fun LiquidPlayerTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val accent = Color(settings.accentColor)
    val scheme = remember(dark, accent, settings.amoled) { buildColorScheme(dark, accent, settings.amoled) }
    val shapes = remember(settings.cornerStyle) { buildShapes(settings.cornerStyle.scale) }
    val glass = remember(settings.glassIntensity, dark, settings.cornerStyle) {
        GlassStyle(settings.glassIntensity, dark, settings.cornerStyle.scale)
    }
    CompositionLocalProvider(LocalAppSettings provides settings, LocalGlassStyle provides glass) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = Typography()) {
            // Sem um Surface na raiz, o texto cairia na cor padrão (preto). Aqui o conteúdo sempre segue o tema.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

private fun buildColorScheme(dark: Boolean, accent: Color, amoled: Boolean): ColorScheme {
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF0B0D12) else Color.White
    val tertiary = lerp(accent, Color(0xFF7C5CFF), 0.55f)
    return if (dark) {
        val bg = if (amoled) Color.Black else Color(0xFF0B0D12)
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.28f).compositeOver(bg), onPrimaryContainer = Color.White,
            secondary = accent, tertiary = tertiary,
            background = bg, onBackground = Color(0xFFEDEFF5),
            surface = bg, onSurface = Color(0xFFEDEFF5),
            surfaceVariant = Color(0xFF1C2029), onSurfaceVariant = Color(0xFFB4BAC8),
            outline = Color(0xFF3A4050), outlineVariant = Color(0x33FFFFFF), error = Color(0xFFFF6B6B),
        )
    } else {
        val bg = Color(0xFFF2F4FA)
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.22f).compositeOver(bg), onPrimaryContainer = Color(0xFF12141A),
            secondary = accent, tertiary = tertiary,
            background = bg, onBackground = Color(0xFF12141A),
            surface = bg, onSurface = Color(0xFF12141A),
            surfaceVariant = Color(0xFFE3E7F1), onSurfaceVariant = Color(0xFF4A5164),
            outline = Color(0xFF8B93A7), outlineVariant = Color(0x33000000), error = Color(0xFFD93636),
        )
    }
}

private fun buildShapes(s: Float) = Shapes(
    extraSmall = RoundedCornerShape((4 * s).dp),
    small = RoundedCornerShape((8 * s).dp),
    medium = RoundedCornerShape((14 * s).dp),
    large = RoundedCornerShape((22 * s).dp),
    extraLarge = RoundedCornerShape((32 * s).dp),
)

/** Cores de destaque disponíveis nas configurações. */
val AccentPalette = listOf(
    0xFF1ED760, 0xFF4D8DFF, 0xFF9B6BFF, 0xFFFF5C93, 0xFFFF8A3D, 0xFFFFC83D, 0xFF25D0E8, 0xFFFF4D4D,
).map { it.toInt() }
