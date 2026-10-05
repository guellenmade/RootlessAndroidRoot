package io.github.guellenmade.rootlessvm.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guellenmade.rootlessvm.data.ContainerApp
import io.github.guellenmade.rootlessvm.data.RootfsInstaller
import io.github.guellenmade.rootlessvm.data.RootfsManifest
import io.github.guellenmade.rootlessvm.data.VmSettings
import io.github.guellenmade.rootlessvm.vm.DisplaySession
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
    val prepareStep: String? = null,
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
    val display = DisplaySession(session, locator.paths)

    init {
        viewModelScope.launch {
            locator.vmController.state.collect { s ->
                _ui.value = _ui.value.copy(vmState = s)
                when (s) {
                    is VmState.Running -> display.start()
                    is VmState.Stopped, is VmState.Failed -> display.stop()
                    else -> Unit
                }
            }
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

    fun prepareVm(context: Context) {
        if (_ui.value.prepareStep != null) return
        viewModelScope.launch {
            val abi = io.github.guellenmade.rootlessvm.vm.BuildAbi.current()

            _ui.value = _ui.value.copy(prepareStep = "Checking device architecture…")
            val catalogText = locator.rootfsCatalog.bundledCatalogText(context.assets)
            val catalog = locator.rootfsCatalog.parse(catalogText)
            val manifest = locator.rootfsCatalog.selectFor(abi, catalog)
            if (manifest == null) {
                _ui.value = _ui.value.copy(
                    prepareStep = null,
                    failDialog = FailState.UnsupportedAbi(abi),
                )
                return@launch
            }

            _ui.value = _ui.value.copy(prepareStep = "Downloading proot runtime…")
            locator.prootProvisioner.provision(context).onFailure {
                _ui.value = _ui.value.copy(
                    prepareStep = null,
                    failDialog = FailState.UnsupportedAbi(abi),
                )
                return@launch
            }

            _ui.value = _ui.value.copy(prepareStep = "Downloading rootfs (checksum-verified)…")
            locator.rootfsInstaller.install(manifest) { p ->
                _ui.value = _ui.value.copy(
                    installProgress = p,
                    prepareStep = when (p) {
                        is RootfsInstaller.Progress.Downloading -> "Downloading rootfs… ${p.bytes / 1048576}/${p.total / 1048576} MB"
                        is RootfsInstaller.Progress.Verifying -> "Verifying rootfs checksum…"
                        is RootfsInstaller.Progress.Unpacking -> "Unpacking rootfs…"
                        RootfsInstaller.Progress.Done -> "Rootfs ready."
                    },
                )
            }.onFailure { e ->
                _ui.value = _ui.value.copy(
                    prepareStep = null,
                    installProgress = null,
                    failDialog = failFromReason(e.message ?: "unknown"),
                )
                return@launch
            }

            _ui.value = _ui.value.copy(prepareStep = "Downloading Xposed (Vector) framework…")
            val vector = catalog.vectorArtifacts()
            val provisioner = io.github.guellenmade.rootlessvm.vm.VectorProvisioner(locator.paths)
            provisioner.downloadAndDeploy(context, vector) { step ->
                _ui.value = _ui.value.copy(
                    prepareStep = when (step) {
                        io.github.guellenmade.rootlessvm.vm.VectorProvisioner.Step.DownloadVector ->
                            "Downloading Vector artifacts (LSPlant/Dobby/XposedBridge)…"
                        io.github.guellenmade.rootlessvm.vm.VectorProvisioner.Step.DeployVector ->
                            "Injecting Vector into the rootfs (zygote patch)…"
                        io.github.guellenmade.rootlessvm.vm.VectorProvisioner.Step.Done ->
                            "Xposed framework ready."
                        else -> _ui.value.prepareStep
                    },
                )
            }.onFailure { e ->
                _ui.value = _ui.value.copy(
                    prepareStep = null,
                    failDialog = failFromReason(e.message ?: "unknown"),
                )
                return@launch
            }

            _ui.value = _ui.value.copy(prepareStep = null, installProgress = RootfsInstaller.Progress.Done)
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
