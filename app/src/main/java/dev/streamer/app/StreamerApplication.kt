package dev.streamer.app

import android.app.Application

class StreamerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createAppContainer(this)
    }
}
