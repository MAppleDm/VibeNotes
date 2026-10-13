package io.github.mappledm.vibenotes

import android.app.Application

class VibeNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
