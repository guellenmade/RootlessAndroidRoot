package io.github.guellenmade.rootlessvm

import android.app.Application
import io.github.guellenmade.rootlessvm.di.ServiceLocator

class RootlessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
