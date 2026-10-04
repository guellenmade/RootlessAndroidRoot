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
        ) + command
}

object BuildAbi {
    fun current(): String = System.getProperty("os.arch")?.let { arch ->
        when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64-v8a"
            arch.contains("arm") -> "armeabi-v7a"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            else -> "unknown"
        }
    } ?: "unknown"
}
