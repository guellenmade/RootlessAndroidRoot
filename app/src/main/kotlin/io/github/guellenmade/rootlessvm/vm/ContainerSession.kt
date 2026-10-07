package io.github.guellenmade.rootlessvm.vm

import io.github.guellenmade.rootlessvm.data.RootfsManifestStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ContainerSession(
    private val paths: ContainerPaths,
    private val manifests: RootfsManifestStore,
) {
    private val builder = ProotCommandBuilder(paths)

    suspend fun exec(command: List<String>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder(builder.execCommand(command))
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            if (rc != 0) error("container-exec-failed:$rc:$out")
            out
        }
    }

    suspend fun installApk(apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = File(paths.containerData, "staging/base.apk")
            staged.parentFile.mkdirs()
            apk.copyTo(staged, overwrite = true)
            exec(listOf("/system/bin/pm", "install", "-r", "/data/staging/base.apk")).getOrThrow()
            Unit
        }
    }

    suspend fun listPackages(): Result<String> =
        exec(listOf("/system/bin/pm", "list", "packages", "-3"))

    suspend fun packageInfo(packageName: String): Result<String> =
        exec(listOf("/system/bin/dumpsys", "package", packageName))

    suspend fun launchApp(packageName: String, activity: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val monkey = listOf(
                "/system/bin/monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1",
            )
            exec(monkey).getOrThrow()
            activity?.let {
                exec(listOf("/system/bin/am", "start", "-n", "$packageName/$it")).getOrThrow()
            }
            Unit
        }
    }

    suspend fun screencapRaw(): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            exec(listOf("/system/bin/screencap", "-p", "/tmp/screen.png")).getOrThrow()
            File(paths.containerTmp, "screen.png").readBytes()
        }
    }

    suspend fun injectInput(vararg event: String): Result<Unit> =
        exec(listOf("/system/bin/input", *event)).map { Unit }
}
