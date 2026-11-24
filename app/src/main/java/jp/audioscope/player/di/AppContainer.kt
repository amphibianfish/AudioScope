package jp.audioscope.player.di

import android.content.Context
import jp.audioscope.player.audio.network.NetworkTrafficMonitor
import jp.audioscope.player.audio.output.AudioOutputInspector
import jp.audioscope.player.audio.probe.AudioStreamInspector
import jp.audioscope.player.data.db.AudioScopeDatabase
import jp.audioscope.player.data.library.MediaLibraryRepository
import jp.audioscope.player.data.preferences.PlaybackPreferencesRepository
import jp.audioscope.player.playback.PlayerControllerManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AudioScopeDatabase by lazy {
        AudioScopeDatabase.getInstance(appContext)
    }

    val dao by lazy {
        database.audioScopeDao()
    }

    val preferencesRepository by lazy {
        PlaybackPreferencesRepository(appContext)
    }

    val mediaLibraryRepository by lazy {
        MediaLibraryRepository(appContext)
    }

    val audioStreamInspector by lazy {
        AudioStreamInspector(appContext)
    }

    val audioOutputInspector by lazy {
        AudioOutputInspector(appContext)
    }

    val networkTrafficMonitor by lazy {
        NetworkTrafficMonitor()
    }

    val playerControllerManager by lazy {
        PlayerControllerManager(appContext)
    }
}
