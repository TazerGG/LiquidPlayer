package com.liquidplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.liquidplayer.MainActivity
import com.liquidplayer.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Serviço em primeiro plano (mediaPlayback) que hospeda o ExoPlayer + MediaSession.
 * A MediaSession dá, automaticamente: notificação com controles, controles na tela de bloqueio,
 * botões de fones/Bluetooth e integração com o foco de áudio do Android.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var mediaSession: MediaSession? = null
    private val scope = MainScope()
    private var skipBroken = true
    private var errorStreak = 0

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pausa em ligações/outros apps e retoma quando o foco volta
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errorStreak = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // Arquivo corrompido/removido: pula para a próxima faixa (sem entrar em loop infinito).
                errorStreak++
                if (skipBroken && player.hasNextMediaItem() && errorStreak <= player.mediaItemCount) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(openApp).build()

        scope.launch {
            settingsRepository.settings.collect {
                player.setHandleAudioBecomingNoisy(it.pauseOnUnplug)
                skipBroken = it.skipBrokenTracks
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Se estiver tocando, a música continua em segundo plano; caso contrário encerra o serviço.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
