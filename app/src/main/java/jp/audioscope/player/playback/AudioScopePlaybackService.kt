package jp.audioscope.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import jp.audioscope.player.AudioScopeApplication
import jp.audioscope.player.audio.dsp.PcmTapAudioProcessor
import jp.audioscope.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Android 17 (API 37) のバックグラウンド音声再生強化（WIU権限付き mediaPlayback FGS 要件）に完全準拠した
 * Jetpack Media3 `MediaSessionService` 実装。
 * - ギャップレス再生 (ExoPlayer Concatenating playlist)
 * - AudioFocus 自動制御 (USAGE_MEDIA / AUDIO_CONTENT_TYPE_MUSIC)
 * - Bluetooth切断 / イヤホン抜去時の自動一時停止 (handleAudioBecomingNoisy = true)
 * - ロック画面・通知パネル・ハードウェアメディアボタン操作対応
 * - 不正・破損音楽ファイル再生時のクラッシュ防止と安全なスキップ・エラー通知
 */
@OptIn(UnstableApi::class)
class AudioScopePlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        val pcmTapProcessor = PcmTapAudioProcessor()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(pcmTapProcessor))
                    .build()
            }
        }.setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // Bluetooth切断・有線抜去時に即座に一時停止
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                AudioPlaybackBus.updateDecoderFormat(format)
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                val encLabel = when (audioTrackConfig.encoding) {
                    C.ENCODING_PCM_16BIT -> "PCM 16-bit"
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "PCM 24-bit"
                    C.ENCODING_PCM_32BIT -> "PCM 32-bit"
                    C.ENCODING_PCM_FLOAT -> "PCM 32-bit Float"
                    else -> "Encoding(${audioTrackConfig.encoding})"
                }
                val chCount = Integer.bitCount(audioTrackConfig.channelConfig).takeIf { it > 0 } ?: 2
                AudioPlaybackBus.updateAudioTrackConfig(
                    jp.audioscope.player.model.ActiveAudioTrackConfig(
                        sampleRateHz = audioTrackConfig.sampleRate,
                        channelCount = chCount,
                        pcmEncodingLabel = encLabel,
                        bufferSizeBytes = audioTrackConfig.bufferSize,
                        isOffload = audioTrackConfig.offload,
                        isTunneling = audioTrackConfig.tunneling
                    )
                )
            }
        })

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    AudioPlaybackBus.clearVisuals()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 不正・破損ファイルでもクラッシュさせずユーザーに理由を通知し、次曲があれば安全に待機/遷移
                val detail = error.errorCodeName
                AudioPlaybackBus.reportPlaybackError("再生エラーを検知しました ($detail)。ファイルが破損しているか非対応形式の可能性があります。")
                if (exoPlayer.hasNextMediaItem()) {
                    exoPlayer.seekToNextMediaItem()
                    exoPlayer.prepare()
                }
            }
        })

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // ハードウェア/Bluetoothイヤホンの再生ボタン操作（WIU付与）から最後の曲を安全に復元
                    val app = application as? AudioScopeApplication
                    val persisted = runBlocking(Dispatchers.IO) {
                        app?.container?.preferencesRepository?.getSnapshot()
                    }
                    val lastUri = persisted?.lastTrackUri
                    return if (!lastUri.isNullOrBlank()) {
                        val item = MediaItem.Builder()
                            .setMediaId(lastUri)
                            .setUri(Uri.parse(lastUri))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(persisted.lastTrackTitle.ifBlank { "AudioScope" })
                                    .setArtist(persisted.lastTrackArtist)
                                    .setAlbumTitle(persisted.lastTrackAlbum)
                                    .build()
                            )
                            .build()
                        Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(item),
                                0,
                                persisted.lastPositionMs
                            )
                        )
                    } else {
                        Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                        )
                    }
                }
            })
            .build()

        player = exoPlayer
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AudioPlaybackBus.clearVisuals()
        AudioPlaybackBus.updateAudioTrackConfig(null)
        AudioPlaybackBus.updateDecoderFormat(null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
