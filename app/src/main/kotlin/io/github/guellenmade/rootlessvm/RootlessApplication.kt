package io.github.guellenmade.rootlessvm

import android.app.Application
import io.github.guellenmade.rootlessvm.di.ServiceLocator
import java.io.File

class RootlessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        val libDir = File(applicationInfo.nativeLibraryDir)
        if (libDir.isDirectory) ServiceLocator.paths.useNativeLibDir(libDir)
    }
}
