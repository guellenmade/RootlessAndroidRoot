package io.github.guellenmade.rootlessvm.vm

import android.content.Context
import java.io.File

class ContainerPaths(context: Context) {
    val base: File = context.getDir("vm", Context.MODE_PRIVATE)
    val runtimeDir: File = File(base, "runtime")
    val prootBin: File = File(runtimeDir, "bin/proot")
    val rootfsDir: File = File(base, "rootfs")
    val containerTmp: File = File(base, "tmp")
    val containerData: File = File(base, "data")
    val containerSdcard: File = File(base, "sdcard")
    val resolvConf: File = File(runtimeDir, "etc/resolv.conf")
    val downloads: File = File(context.cacheDir, "downloads")
    val snapshots: File = File(base, "snapshots")
    val suPolicyDir: File = File(containerData, "rootless/su")
    val xposedConfigDir: File = File(containerData, "adb/vector")

    fun ensure() {
        listOf(
            runtimeDir, File(runtimeDir, "bin"), File(runtimeDir, "etc"),
            rootfsDir, containerTmp, containerData, containerSdcard,
            downloads, snapshots, suPolicyDir, xposedConfigDir,
        ).forEach { it.mkdirs() }
    }
}
