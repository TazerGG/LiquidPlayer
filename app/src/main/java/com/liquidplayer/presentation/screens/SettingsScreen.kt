package com.liquidplayer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquidplayer.data.repository.PrefKeys
import com.liquidplayer.domain.model.CornerStyle
import com.liquidplayer.domain.model.ThemeMode
import com.liquidplayer.presentation.components.*
import com.liquidplayer.presentation.theme.AccentPalette
import com.liquidplayer.presentation.theme.GlassChip
import com.liquidplayer.presentation.theme.glass
import com.liquidplayer.presentation.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(onOpenFolders: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val unavailable by vm.unavailable.collectAsStateWithLifecycle()
    val show = LocalShowMessage.current
    LaunchedEffect(Unit) { vm.message.collect { show(it) } }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalContentBottomPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Configurações", Modifier.offset(x = (-16).dp)) }

        item {
            Card("Aparência") {
                Label("Tema")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Sistema", s.themeMode == ThemeMode.SYSTEM, { vm.set(PrefKeys.THEME, ThemeMode.SYSTEM.name) }, icon = Icons.Rounded.Smartphone)
                    GlassChip("Claro", s.themeMode == ThemeMode.LIGHT, { vm.set(PrefKeys.THEME, ThemeMode.LIGHT.name) }, icon = Icons.Rounded.LightMode)
                    GlassChip("Escuro", s.themeMode == ThemeMode.DARK, { vm.set(PrefKeys.THEME, ThemeMode.DARK.name) }, icon = Icons.Rounded.DarkMode)
                }
                Label("Cor de destaque")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentPalette.forEach { c ->
                        val selected = s.accentColor == c
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { vm.set(PrefKeys.ACCENT, c) },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.Black.copy(alpha = 0.7f)) }
                    }
                }
                Label("Intensidade do vidro (transparência e desfoque)")
                Slider(s.glassIntensity, { vm.set(PrefKeys.GLASS, it) }, valueRange = 0f..1f)
                Label("Arredondamento")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Compacto", s.cornerStyle == CornerStyle.COMPACT, { vm.set(PrefKeys.CORNERS, CornerStyle.COMPACT.name) })
                    GlassChip("Padrão", s.cornerStyle == CornerStyle.DEFAULT, { vm.set(PrefKeys.CORNERS, CornerStyle.DEFAULT.name) })
                    GlassChip("Redondo", s.cornerStyle == CornerStyle.ROUND, { vm.set(PrefKeys.CORNERS, CornerStyle.ROUND.name) })
                }
                SwitchRow("Fundo dinâmico com a capa", "Cores extraídas do álbum na tela de reprodução", s.dynamicBackground) { vm.set(PrefKeys.DYNAMIC_BG, it) }
                SwitchRow("Preto puro (AMOLED)", "Economiza bateria em telas OLED no tema escuro", s.amoled) { vm.set(PrefKeys.AMOLED, it) }
            }
        }
        item {
            Card("Reprodução") {
                SwitchRow("Retomar de onde parou", "Restaura fila, música e posição ao abrir o app", s.restoreQueue) { vm.set(PrefKeys.RESTORE_QUEUE, it) }
                SwitchRow("Pausar ao desconectar fones", "Evita tocar no alto-falante por acidente", s.pauseOnUnplug) { vm.set(PrefKeys.PAUSE_UNPLUG, it) }
                SwitchRow("Pular arquivos com erro", "Se uma faixa não puder ser tocada, vai para a próxima", s.skipBrokenTracks) { vm.set(PrefKeys.SKIP_BROKEN, it) }
            }
        }
        item {
            Card("Biblioteca") {
                SettingsAction(Icons.Rounded.Folder, "Gerenciar diretórios", "Adicionar, atualizar, remover e criar playlists de pastas", onOpenFolders)
                SettingsAction(Icons.Rounded.CleaningServices, "Remover músicas indisponíveis ($unavailable)", "Limpa entradas de arquivos movidos ou apagados") { vm.removeUnavailable() }
                SettingsAction(Icons.Rounded.History, "Limpar histórico de reprodução", "Zera \"Tocadas recentemente\"") { vm.clearHistory() }
            }
        }
        item {
            Text("Liquid Player 1.0 • Seus arquivos nunca são copiados nem alterados.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), 0.95f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingsAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
