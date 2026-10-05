package io.github.guellenmade.rootlessvm.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guellenmade.rootlessvm.data.ContainerApp
import io.github.guellenmade.rootlessvm.data.RootfsInstaller
import io.github.guellenmade.rootlessvm.data.RootfsManifest
import io.github.guellenmade.rootlessvm.data.VmSettings
import io.github.guellenmade.rootlessvm.di.ServiceLocator
import io.github.guellenmade.rootlessvm.root.SuEntry
import io.github.guellenmade.rootlessvm.root.SuLogEntry
import io.github.guellenmade.rootlessvm.vm.ContainerSession
import io.github.guellenmade.rootlessvm.vm.FailState
import io.github.guellenmade.rootlessvm.vm.GpuInfo
import io.github.guellenmade.rootlessvm.vm.VmState
import io.github.guellenmade.rootlessvm.xposed.XposedModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val vmState: VmState = VmState.NotInstalled,
    val apps: List<ContainerApp> = emptyList(),
    val suEntries: List<SuEntry> = emptyList(),
    val suLogs: List<SuLogEntry> = emptyList(),
    val modules: List<XposedModule> = emptyList(),
    val gpu: GpuInfo? = null,
    val settings: VmSettings = VmSettings(),
    val failDialog: FailState? = null,
    val installProgress: RootfsInstaller.Progress? = null,
    val fps: Double = 0.0,
)

class MainViewModel(private val locator: ServiceLocator) : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val session = ContainerSession(locator.paths, locator.rootfsManifest)

    init {
        viewModelScope.launch {
            locator.vmController.state.collect { s -> _ui.value = _ui.value.copy(vmState = s) }
        }
        refresh()
    }

    fun refresh() {
        val l = locator
        _ui.value = _ui.value.copy(
            apps = l.containerApps.load(),
            suEntries = l.suPolicy.load(),
            suLogs = l.suPolicy.log(),
            modules = l.xposedModules.load().modules,
            gpu = l.gpuDetector.detect(),
            settings = l.settings.load(),
        )
    }

    fun dismissFail() {
        _ui.value = _ui.value.copy(failDialog = null)
    }

    fun startVm(context: Context) {
        val fail = locator.vmController.checkCompatibility()
        if (fail != null) {
            _ui.value = _ui.value.copy(failDialog = fail)
            return
        }
        if (_ui.value.settings.firewallEnabled) {
            val prepared = android.net.VpnService.prepare(context)
            if (prepared != null) {
                (context as? MainActivity)?.requestVpnPermission(prepared)
                return
            }
        }
        io.github.guellenmade.rootlessvm.vm.VmService.start(context)
    }

    fun stopVm(context: Context) {
        io.github.guellenmade.rootlessvm.vm.VmService.stop(context)
        if (_ui.value.settings.firewallEnabled) {
            context.startService(
                android.content.Intent(context, io.github.guellenmade.rootlessvm.vpn.FirewallVpnService::class.java)
                    .setAction(io.github.guellenmade.rootlessvm.vpn.FirewallVpnService.ACTION_STOP),
            )
        }
    }

    fun downloadRootfs(manifest: RootfsManifest) {
        viewModelScope.launch {
            locator.rootfsInstaller.install(manifest) { p ->
                _ui.value = _ui.value.copy(installProgress = p)
            }.onFailure { e ->
                _ui.value = _ui.value.copy(
                    failDialog = failFromReason(e.message ?: "unknown"),
                    installProgress = null,
                )
            }
            refresh()
        }
    }

    fun installApk(context: Context, apk: File) {
        viewModelScope.launch {
            session.installApk(apk)
                .onSuccess { rescan(context) }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        failDialog = FailState.ProotBootFailure(-3),
                    )
                }
        }
    }

    fun rescan(context: Context) {
        viewModelScope.launch {
            session.listPackages().onSuccess { out ->
                val packages = out.lines().mapNotNull { l ->
                    Regex("package:(.*)=(.*)").find(l)?.groupValues?.get(2)
                }
                packages.forEach { pkg ->
                    val info = session.packageInfo(pkg).getOrDefault("")
                    val label = Regex("label=([^ ]*)").find(info)?.groupValues?.get(1) ?: pkg
                    locator.containerApps.upsert(
                        ContainerApp(
                            packageName = pkg,
                            label = label,
                            versionName = Regex("versionName=([^ ]*)").find(info)?.groupValues?.get(1) ?: "",
                            iconFile = locator.containerApps.iconFileFor(pkg).absolutePath,
                            installedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                _ui.value = _ui.value.copy(apps = locator.containerApps.load())
                locator.shortcutSync.syncAll(context, locator.containerApps.load())
            }
        }
    }

    fun setSuDecision(uid: Int, pkg: String, granted: Boolean, always: Boolean) {
        locator.suPolicy.setDecision(uid, pkg, granted, always)
        refresh()
    }

    fun setModuleEnabled(packageName: String, enabled: Boolean) {
        locator.xposedModules.setEnabled(packageName, enabled)
        refresh()
    }

    fun saveSettings(settings: VmSettings) {
        locator.settings.save(settings)
        refresh()
    }

    fun snapshot(name: String) {
        viewModelScope.launch {
            locator.vmController.snapshot(name)
        }
    }

    private fun failFromReason(reason: String): FailState = when {
        reason.startsWith("unsupported-abi") -> FailState.UnsupportedAbi(reason.substringAfter(':'))
        reason.startsWith("insufficient-storage") -> FailState.InsufficientStorage(0, 0)
        reason.startsWith("checksum-mismatch") -> FailState.ChecksumMismatch("?", "?")
        reason.startsWith("rootfs-version-incompatible") -> FailState.RootfsVersionIncompatible(0, 0)
        else -> FailState.ProotBootFailure(-4)
    }
}
