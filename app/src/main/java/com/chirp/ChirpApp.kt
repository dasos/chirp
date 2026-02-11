package com.chirp

import android.app.Application
import com.chirp.data.AppContainer

class ChirpApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.wearSync.start()
    }
}
