package com.liquidplayer.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.liquidplayer.BuildConfig
import com.liquidplayer.di.ApplicationScope
import com.liquidplayer.domain.model.UpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NotConfigured : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Downloaded(val info: UpdateInfo, val path: String) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

private class UpdateException(message: String) : Exception(message)

/**
 * Atualizações opcionais via GitHub Releases (repositório público, sem login).
 * Nada é baixado ou instalado sem o usuário confirmar: o app só AVISA que há versão nova.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val currentVersion: String = BuildConfig.VERSION_NAME
    val isConfigured: Boolean
        get() = BuildConfig.GITHUB_OWNER.isNotBlank() && BuildConfig.GITHUB_OWNER != "SEU_USUARIO"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Diálogo "Nova versão disponível" visível? (pode ser reaberto pelas Configurações). */
    val promptVisible = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var downloadJob: Job? = null

    // ------------------------------------------------------------------ verificação
    /** Verificação automática: no máximo 1x por dia e só se o usuário não desligou. */
    suspend fun autoCheckIfDue() {
        if (!isConfigured) return
        val last = settings.get(PrefKeys.UPDATE_LAST_CHECK)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < DAY_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        if (!isConfigured) {
            _state.value = UpdateState.NotConfigured
            if (manual) _messages.tryEmit("Atualizador não configurado: defina GITHUB_OWNER e GITHUB_REPO no build.gradle.kts.")
            return
        }
        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val info = fetchLatest()
                settings.set(PrefKeys.UPDATE_LAST_CHECK, System.currentTimeMillis())
                if (isNewer(info.versionName, currentVersion)) {
                    _state.value = UpdateState.Available(info)
                    val skipped = settings.get(PrefKeys.UPDATE_SKIPPED)
                    if (manual || skipped != info.versionName) promptVisible.value = true
                } else {
                    _state.value = UpdateState.UpToDate
                    if (manual) _messages.tryEmit("Você já está na versão mais recente ($currentVersion).")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = friendly(e)
                _state.value = UpdateState.Failed(msg)
                if (manual) _messages.tryEmit(msg)
            }
        }
    }

    private suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> throw UpdateException("Nenhuma release publicada neste repositório (ou ele é privado).")
                403, 429 -> throw UpdateException("Limite de consultas do GitHub atingido. Tente de novo mais tarde.")
                else -> throw UpdateException("O GitHub respondeu com erro $code.")
            }
            parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): UpdateInfo {
        val tag = json.getString("tag_name")
        var apk: JSONObject? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true) && (apk == null || name.contains("release", ignoreCase = true))) apk = a
            }
        }
        val digest = apk?.optString("digest").orEmpty().removePrefix("sha256:").lowercase().takeIf { it.length == 64 }
        return UpdateInfo(
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            title = json.optString("name").ifBlank { tag },
            notes = json.optString("body").takeIf { it != "null" }.orEmpty().trim().take(2000),
            pageUrl = json.optString("html_url"),
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") },
            apkSizeBytes = apk?.optLong("size") ?: 0L,
            sha256 = digest,
        )
    }

    // ------------------------------------------------------------------ download
    fun startDownload() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        } ?: return
        if (info.apkUrl == null) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            try {
                val file = download(info) { _state.value = UpdateState.Downloading(info, it) }
                _state.value = UpdateState.Downloaded(info, file.absolutePath)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(friendly(e), info)
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    private suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "LiquidPlayer-${info.versionName}.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true // github.com -> servidor de arquivos do GitHub (https)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) throw UpdateException("Falha ao baixar o APK (erro ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSizeBytes
            val sha = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastReported = 0f
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val p = (read.toFloat() / total).coerceIn(0f, 1f)
                            if (p - lastReported >= 0.01f) { lastReported = p; onProgress(p) }
                        }
                    }
                }
            }
            if (info.apkSizeBytes > 0 && file.length() != info.apkSizeBytes) {
                file.delete(); throw UpdateException("O download veio incompleto. Tente novamente.")
            }
            val hash = sha.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !hash.equals(info.sha256, ignoreCase = true)) {
                file.delete(); throw UpdateException("O arquivo baixado não confere com o publicado no GitHub (checksum). Por segurança, não foi instalado.")
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ instalação
    /** Abre o instalador do Android. Na 1ª vez pede para permitir "instalar apps desta fonte". */
    fun install(activityContext: Context) {
        val st = _state.value as? UpdateState.Downloaded ?: return
        val file = File(st.path)
        if (!file.exists()) {
            _state.value = UpdateState.Available(st.info)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _messages.tryEmit("Permita instalar apps desta fonte para o Liquid Player e toque em \"Instalar\" de novo.")
            activityContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        activityContext.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun openReleasePage(activityContext: Context) {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Downloading -> s.info
            is UpdateState.Downloaded -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        } ?: return
        if (info.pageUrl.isNotBlank()) activityContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)))
    }

    // ------------------------------------------------------------------ escolhas do usuário
    fun dismissPrompt() { promptVisible.value = false }

    fun showPrompt() {
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate) promptVisible.value = true
    }

    /** "Ignorar esta versão": não avisa de novo até sair uma release mais nova. */
    suspend fun skip(version: String) {
        settings.set(PrefKeys.UPDATE_SKIPPED, version)
        promptVisible.value = false
    }

    // ------------------------------------------------------------------ util
    private fun friendly(e: Exception): String = when (e) {
        is UpdateException -> e.message.orEmpty()
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Sem conexão com a internet. Tente novamente mais tarde."
        else -> "Não foi possível verificar/baixar a atualização (${e.javaClass.simpleName})."
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.take(4).toList()
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val USER_AGENT = "LiquidPlayer-Updater (Android)"
    }
}
