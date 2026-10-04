package io.github.guellenmade.rootlessvm.vm

import io.github.guellenmade.rootlessvm.data.ContainerAppStore
import io.github.guellenmade.rootlessvm.data.RootfsManifestStore
import io.github.guellenmade.rootlessvm.data.SettingsStore
import io.github.guellenmade.rootlessvm.data.VmSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed class VmState {
    data object NotInstalled : VmState()
    data object Stopped : VmState()
    data object Starting : VmState()
    data object Running : VmState()
    data object Stopping : VmState()
    data class Failed(val fail: FailState) : VmState()
}

class VmController(
    private val paths: ContainerPaths,
    private val settingsStore: SettingsStore,
    private val manifests: RootfsManifestStore,
    private val appStore: ContainerAppStore,
) {
    private val _state = MutableStateFlow<VmState>(
        if (manifests.isRootfsInstalled()) VmState.Stopped else VmState.NotInstalled,
    )
    val state: StateFlow<VmState> = _state

    @Volatile var prootProcess: Process? = null
        private set

    fun settings(): VmSettings = settingsStore.load()

    fun checkCompatibility(): FailState? {
        val abi = ProotCommandBuilder(paths).supportedAbi()
            ?: return FailState.UnsupportedAbi(BuildAbi.current())
        val artifacts = manifests.builtArtifacts()
        val manifest = manifests.manifest()
        if (artifacts != null && manifest != null && artifacts.targetApi != manifest.apiLevel) {
            return FailState.RootfsVersionIncompatible(manifest.apiLevel, artifacts.targetApi)
        }
        if (!File(paths.prootBin).exists()) {
            return FailState.ProotBootFailure(-1)
        }
        return null
    }

    fun startVmBlocking(onStarted: () -> Unit): FailState? {
        checkCompatibility()?.let { return it }
        _state.value = VmState.Starting
        return try {
            val cmd = ProotCommandBuilder(paths).build(settings(), emptyList())
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            prootProcess = proc
            Thread.sleep(BOOT_GRACE_MS)
            if (proc.isAlive) {
                _state.value = VmState.Running
                onStarted()
                null
            } else {
                prootProcess = null
                _state.value = VmState.Failed(FailState.ProotBootFailure(proc.exitValue()))
                FailState.ProotBootFailure(proc.exitValue())
            }
        } catch (e: Exception) {
            _state.value = VmState.Failed(FailState.ProotBootFailure(-2))
            FailState.ProotBootFailure(-2)
        }
    }

    fun stopVm() {
        val proc = prootProcess ?: return
        _state.value = VmState.Stopping
        runCatching {
            proc.destroy()
            proc.waitFor()
        }
        prootProcess = null
        _state.value = VmState.Stopped
    }

    fun snapshot(name: String): Result<File> = runCatching {
        val dest = File(paths.snapshots, "$name.tar")
        ProcessBuilder(
            "tar", "-cf", dest.absolutePath, "-C", paths.rootfsDir.absolutePath, ".",
        ).start().waitFor().let { rc ->
            if (rc != 0) error("snapshot-failure:$rc")
        }
        dest
    }

    companion object {
        const val BOOT_GRACE_MS = 4000L
    }
}
