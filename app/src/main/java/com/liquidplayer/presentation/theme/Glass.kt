package com.liquidplayer.presentation.theme

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Estado do Haze (desfoque real do conteúdo que está ATRÁS do elemento).
 * Só é fornecido às camadas flutuantes (barra inferior e mini player). Em outros lugares
 * o vidro usa translucidez + gradientes + bordas, sem blur, para manter fluidez e legibilidade.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Efeito Liquid Glass: desfoque de fundo (Android 12+, com fallback automático), camada translúcida,
 * brilho especular no topo e borda de vidro em gradiente.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    strength: Float = 1f,
    tint: Color = Color.Unspecified,
): Modifier {
    val g = LocalGlassStyle.current
    val haze = LocalHazeState.current
    val blur = haze != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dark = g.dark
    val baseColor = if (dark) Color(0xFF151927) else Color.White
    val scrimColor = if (tint.isSpecified()) tint.copy(alpha = 1f).let { t -> lerpColor(baseColor, t, 0.25f) } else baseColor
    val opacity = (if (blur) lerp(0.60f, 0.16f, g.intensity) else lerp(0.92f, 0.38f, g.intensity)) * strength
    val scrim = scrimColor.copy(alpha = opacity.coerceIn(0.05f, 0.96f))
    val sheenTop = if (dark) Color.White.copy(alpha = lerp(0.14f, 0.07f, g.intensity)) else Color.White.copy(alpha = 0.55f)
    val border = Brush.linearGradient(
        if (dark) listOf(Color.White.copy(0.42f), Color.White.copy(0.05f), Color.White.copy(0.22f))
        else listOf(Color.White.copy(0.95f), Color.Black.copy(0.04f), Color.White.copy(0.7f)),
    )
    var m = this.clip(shape)
    if (haze != null) {
        m = m.hazeEffect(state = haze) {
            backgroundColor = scrimColor.copy(alpha = 1f)
            blurRadius = lerp(14f, 32f, g.intensity).dp
            tints = listOf(HazeTint(scrim))
            fallbackTint = HazeTint(scrimColor.copy(alpha = lerp(0.9f, 0.75f, g.intensity)))
            noiseFactor = 0.04f
        }
    }
    return m
        .then(if (blur) Modifier else Modifier.background(scrim))
        .drawBehind {
            drawRect(Brush.verticalGradient(0f to sheenTop, 0.45f to Color.Transparent))
        }
        .border(BorderStroke(1.dp, border), shape)
}

private fun Color.isSpecified() = this != Color.Unspecified
private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strength: Float = 1f,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, strength, tint), content = content)
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    selected: Boolean = false,
    filled: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg = when {
        filled -> Modifier.clip(CircleShape).background(accent)
        selected -> Modifier.glass(CircleShape, tint = accent)
        else -> Modifier.glass(CircleShape, strength = 0.8f)
    }
    Box(
        modifier.size(size).then(bg).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription, Modifier.size(iconSize),
            tint = when {
                filled -> MaterialTheme.colorScheme.onPrimary
                selected -> accent
                else -> tint
            },
        )
    }
}

@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .then(if (selected) Modifier.clip(shape).background(accent.copy(alpha = 0.9f)) else Modifier.glass(shape, 0.8f))
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon, null, Modifier.size(16.dp).padding(end = 0.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        }
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Diálogo de vidro. Em Android 12+ a própria janela desfoca o app atrás (blur do compositor). */
@Composable
fun GlassDialog(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0.35f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.also { it.blurBehindRadius = 28 }
                }
            }
        }
        CompositionLocalProvider(LocalHazeState provides null) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth().widthIn(max = 460.dp)
                    .glass(RoundedCornerShape((28 * LocalGlassStyle.current.cornerScale).dp), strength = 1.9f)
                    .padding(20.dp),
                content = content,
            )
        }
    }
}
