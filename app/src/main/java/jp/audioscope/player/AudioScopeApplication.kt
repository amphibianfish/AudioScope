package jp.audioscope.player

import android.app.Application
import jp.audioscope.player.di.AppContainer

class AudioScopeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
