package io.github.guellenmade.rootlessvm.vm

import io.github.guellenmade.rootlessvm.data.VmSettings
import java.io.File

class ProotCommandBuilder(private val paths: ContainerPaths) {

    fun supportedAbi(): String? = when (BuildAbi.current()) {
        "arm64-v8a" -> "arm64-v8a"
        "armeabi-v7a" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        else -> null
    }

    fun build(settings: VmSettings, entryArgs: List<String>): List<String> {
        val rootfs = paths.rootfsDir
        val runtime = paths.runtimeDir
        paths.ensure()
        return listOf(
            paths.prootBin.absolutePath,
            "--rootfs=${rootfs.absolutePath}",
            "-0",
            "-w", "/",
            "-L", File(runtime, "etc/resolv.conf").absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${paths.containerTmp.absolutePath}:/tmp",
            "-b", "${paths.containerData.absolutePath}:/data",
            "-b", "${paths.containerSdcard.absolutePath}:/sdcard",
            "-b", "${paths.runtimeDir.absolutePath}:/vm",
        ) + listOf(
            "/system/bin/sh", "/vm/entry.sh",
        ) + entryArgs
    }

    fun execCommand(command: List<String>): List<String> =
        listOf(
            paths.prootBin.absolutePath,
            "--rootfs=${paths.rootfsDir.absolutePath}",
            "-0",
            "-w", "/",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${paths.containerTmp.absolutePath}:/tmp",
            "-b", "${paths.containerData.absolutePath}:/data",
            "-b", "${paths.containerSdcard.absolutePath}:/sdcard",
            "-b", "${paths.runtimeDir.absolutePath}:/vm",
        ) + command
}

object BuildAbi {
    /**
     * Primary device ABI from Android's own ABI list (Build.SUPPORTED_ABIS),
     * not the JVM's os.arch (which can diverge on emulators/some devices).
     */
    fun current(): String {
        val supported = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: return "unknown"
        return when {
            supported.startsWith("arm64") -> "arm64-v8a"
            supported.startsWith("armeabi") || supported.startsWith("arm") -> "armeabi-v7a"
            supported.startsWith("x86_64") -> "x86_64"
            supported.startsWith("x86") -> "x86"
            else -> "unknown"
        }
    }

    fun isSupported(abi: String): Boolean =
        abi == "arm64-v8a" || abi == "armeabi-v7a" || abi == "x86_64"
}
