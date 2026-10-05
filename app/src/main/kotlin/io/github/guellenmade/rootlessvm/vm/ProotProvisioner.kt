package io.github.guellenmade.rootlessvm.vm

import android.content.Context
import io.github.guellenmade.rootlessvm.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Provisions the proot binary for this device ABI. Order (ADR-009):
 * 1. bundled in APK assets (bin/proot/<abi>/proot) — used by CI release builds
 *    that pack the built binaries;
 * 2. downloaded from the project's GitHub releases (sha256-verified);
 * 3. otherwise: clean fail state (UnsupportedAbi) — no half-working states.
 */
class ProotProvisioner(private val paths: ContainerPaths) {
    fun provision(context: Context): Result<File> {
        val abi = BuildAbi.current()
        if (abi == "unknown") {
            return Result.failure(IllegalStateException(FailState.UnsupportedAbi(abi).reason))
        }
        val target = File(paths.runtimeDir, "bin/proot")
        if (target.exists() && target.length() > 0 && target.canExecute()) {
            return Result.success(target)
        }
        target.parentFile?.mkdirs()
        paths.ensure()

        val bundled = runCatching {
            context.assets.open("bin/proot/$abi/proot").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            target.setExecutable(true, false)
            target
        }
        if (bundled.isSuccess && target.exists() && target.length() > 0) {
            return Result.success(target)
        }

        val remote = RELEASE_URL.format(abi)
        return downloadVerified(remote, target, "$remote.sha256")
            .recoverCatching { e ->
                throw IllegalStateException(
                    FailState.RuntimeArtifactUnavailable(e.message ?: "download failed", "proot ($abi)").reason,
                )
            }
    }

    private fun downloadVerified(url: String, target: File, shaUrl: String): Result<File> =
        runCatching {
            val expected = URI(shaUrl).toURL().readText().trim().split(" ")[0]
            val tmp = File(target.parentFile, "proot.download")
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } > 0) {
                    digest.update(buf, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, true)) {
                tmp.delete()
                throw IllegalStateException(
                    FailState.ChecksumMismatch(expected, actual).reason,
                )
            }
            tmp.renameTo(target)
            target.setExecutable(true, false)
            target
        }

    companion object {
        const val RELEASE_URL =
            "https://github.com/guellenmade/RootlessAndroidRoot/releases/download/proot-%s/proot"
    }
}
