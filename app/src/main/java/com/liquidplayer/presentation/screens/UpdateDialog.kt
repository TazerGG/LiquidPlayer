package com.liquidplayer.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liquidplayer.data.repository.UpdateState
import com.liquidplayer.presentation.theme.GlassDialog
import java.util.Locale

/** Aviso de nova versão. Tudo é opcional: o usuário pode atualizar, deixar para depois ou ignorar a versão. */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Downloaded -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    } ?: return

    GlassDialog(onLater) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Nova versão disponível", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        val size = if (info.apkSizeBytes > 0) " • " + String.format(Locale.US, "%.1f MB", info.apkSizeBytes / 1_048_576.0) else ""
        Text("${info.versionName} (instalada: $currentVersion)$size", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text("Novidades", fontWeight = FontWeight.SemiBold)
        Box(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
            Text(
                info.notes.ifBlank { "Sem notas de versão." },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        when (state) {
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth())
                Text("Baixando… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text("Cancelar download") }
            }
            is UpdateState.Downloaded -> {
                Text("Download concluído. O Android vai pedir sua confirmação para instalar.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onInstall, Modifier.fillMaxWidth()) { Text("Instalar agora") }
            }
            else -> {
                if (state is UpdateState.Failed) {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                if (info.apkUrl != null) Button(onDownload, Modifier.fillMaxWidth()) { Text(if (state is UpdateState.Failed) "Tentar novamente" else "Baixar e atualizar") }
                else OutlinedButton(onOpenPage, Modifier.fillMaxWidth()) { Text("Abrir página da release (sem APK anexado)") }
            }
        }
        if (state !is UpdateState.Downloading) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onSkip) { Text("Ignorar esta versão") }
                TextButton(onLater) { Text("Agora não") }
            }
        }
    }
}
