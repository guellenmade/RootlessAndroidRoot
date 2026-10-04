package io.github.guellenmade.rootlessvm

import android.app.Application

class RootlessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
