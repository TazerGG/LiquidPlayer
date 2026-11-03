package com.liquidplayer.data.repository

import com.liquidplayer.data.database.PreferenceDao
import com.liquidplayer.data.database.PreferenceEntity
import com.liquidplayer.domain.model.AppSettings
import com.liquidplayer.domain.model.CornerStyle
import com.liquidplayer.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

object PrefKeys {
    const val THEME = "ui.theme"
    const val ACCENT = "ui.accent"
    const val GLASS = "ui.glass"
    const val DYNAMIC_BG = "ui.dynamic_bg"
    const val AMOLED = "ui.amoled"
    const val CORNERS = "ui.corners"
    const val RESTORE_QUEUE = "play.restore_queue"
    const val PAUSE_UNPLUG = "play.pause_unplug"
    const val SKIP_BROKEN = "play.skip_broken"
    const val UPDATE_AUTO = "update.auto"
    const val UPDATE_LAST_CHECK = "update.last_check"
    const val UPDATE_SKIPPED = "update.skipped_version"

    // estado do player (persistido para restaurar após reiniciar)
    const val PLAYER_INDEX_SONG = "state.current_song_id"
    const val PLAYER_POSITION = "state.position_ms"
    const val PLAYER_SHUFFLE = "state.shuffle"
    const val PLAYER_REPEAT = "state.repeat"
    const val PLAYER_INDEX = "state.current_index"
    const val QUEUED_POSITIONS = "state.queued_positions"
    const val ORIGINAL_ORDER = "state.original_order"
}

/** Preferências salvas em uma tabela chave/valor do Room (persistem após reiniciar o app). */
@Singleton
class SettingsRepository @Inject constructor(private val dao: PreferenceDao) {

    val settings: Flow<AppSettings> = dao.observeAll().map { rows ->
        val m = rows.associate { it.prefKey to it.prefValue }
        val d = AppSettings()
        AppSettings(
            themeMode = m[PrefKeys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            accentColor = m[PrefKeys.ACCENT]?.toIntOrNull() ?: d.accentColor,
            glassIntensity = m[PrefKeys.GLASS]?.toFloatOrNull() ?: d.glassIntensity,
            dynamicBackground = m[PrefKeys.DYNAMIC_BG]?.toBooleanStrictOrNull() ?: d.dynamicBackground,
            amoled = m[PrefKeys.AMOLED]?.toBooleanStrictOrNull() ?: d.amoled,
            cornerStyle = m[PrefKeys.CORNERS]?.let { runCatching { CornerStyle.valueOf(it) }.getOrNull() } ?: d.cornerStyle,
            restoreQueue = m[PrefKeys.RESTORE_QUEUE]?.toBooleanStrictOrNull() ?: d.restoreQueue,
            pauseOnUnplug = m[PrefKeys.PAUSE_UNPLUG]?.toBooleanStrictOrNull() ?: d.pauseOnUnplug,
            skipBrokenTracks = m[PrefKeys.SKIP_BROKEN]?.toBooleanStrictOrNull() ?: d.skipBrokenTracks,
            autoCheckUpdates = m[PrefKeys.UPDATE_AUTO]?.toBooleanStrictOrNull() ?: d.autoCheckUpdates,
        )
    }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()
    suspend fun set(key: String, value: Any) = dao.put(PreferenceEntity(key, value.toString()))
    suspend fun setMany(values: Map<String, Any>) =
        dao.putAll(values.map { (k, v) -> PreferenceEntity(k, v.toString()) })
    suspend fun get(key: String): String? = dao.get(key)
}
