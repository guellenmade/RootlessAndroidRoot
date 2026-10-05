package io.github.guellenmade.rootlessvm.di

import android.content.Context
import io.github.guellenmade.rootlessvm.data.ContainerAppStore
import io.github.guellenmade.rootlessvm.data.RootfsCatalog
import io.github.guellenmade.rootlessvm.data.RootfsInstaller
import io.github.guellenmade.rootlessvm.data.RootfsManifestStore
import io.github.guellenmade.rootlessvm.data.SettingsStore
import io.github.guellenmade.rootlessvm.root.SuPolicyStore
import io.github.guellenmade.rootlessvm.shortcut.ShortcutSync
import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import io.github.guellenmade.rootlessvm.vm.GpuDetector
import io.github.guellenmade.rootlessvm.vm.ProotCommandBuilder
import io.github.guellenmade.rootlessvm.vm.ProotProvisioner
import io.github.guellenmade.rootlessvm.vm.VmController
import io.github.guellenmade.rootlessvm.xposed.XposedModuleStore

class ServiceLocator private constructor(context: Context) {
    val appContext: Context = context.applicationContext
    val paths: ContainerPaths = ContainerPaths(appContext)
    val settings: SettingsStore = SettingsStore(paths)
    val rootfsManifest: RootfsManifestStore = RootfsManifestStore(paths)
    val rootfsInstaller: RootfsInstaller = RootfsInstaller(paths, rootfsManifest)
    val containerApps: ContainerAppStore = ContainerAppStore(paths)
    val suPolicy: SuPolicyStore = SuPolicyStore(paths)
    val xposedModules: XposedModuleStore = XposedModuleStore(paths)
    val gpuDetector: GpuDetector = GpuDetector()
    val prootCommandBuilder: ProotCommandBuilder = ProotCommandBuilder(paths)
    val vmController: VmController = VmController(paths, settings, rootfsManifest, containerApps)
    val prootProvisioner: ProotProvisioner = ProotProvisioner(paths)
    val rootfsCatalog: RootfsCatalog = RootfsCatalog(paths)
    val shortcutSync: ShortcutSync = ShortcutSync(paths, containerApps)

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun init(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context.applicationContext).also { instance = it }
            }

        fun get(): ServiceLocator =
            instance ?: error("ServiceLocator not initialized")
    }
}
