package io.github.guellenmade.rootlessvm.vm

import android.content.Context
import io.github.guellenmade.rootlessvm.data.Catalog
import io.github.guellenmade.rootlessvm.data.CatalogVectorArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads the built Vector artifacts (pinned commit, see AGENT.md §3) for
 * the rootfs's Android version and deploys them into the rootfs, mirroring
 * vector/deploy_into_rootfs.sh:
 *
 *  - libvector_inject.so  -> /system/lib64/libvector_inject.so
 *  - xposed.dex           -> /system/framework/vector-xposed.jar
 *  - vector-manager.apk   -> staged for install into the container
 *  - app_process64 wrapper (from assets) -> /system/bin/app_process64
 *    (original binary kept as app_process64.real — ADR-005)
 *  - su + policy_check.sh (from assets)  -> /system/xbin/
 *  - /data/adb/vector module config home
 *
 * Every download is SHA-256 verified against the catalog; any mismatch or
 * missing artifact is a clean abort (no half-working rootfs).
 */
class VectorProvisioner(private val paths: ContainerPaths) {

    sealed class Step {
        data object ProvisionProot : Step()
        data object DownloadRootfs : Step()
        data object VerifyRootfs : Step()
        data object UnpackRootfs : Step()
        data object DownloadVector : Step()
        data object DeployVector : Step()
        data object Done : Step()
    }

    fun installSuTools(context: Context, rootfs: File = paths.rootfsDir): Result<Unit> =
        runCatching {
            copyAsset(context, "runtime/su", File(rootfs, "system/xbin/su"), executable = true)
            copyAsset(context, "runtime/policy_check.sh", File(rootfs, "system/xbin/policy_check.sh"), executable = true)
        }

    fun patchZygoteEntry(context: Context, rootfs: File = paths.rootfsDir): Result<Unit> =
        runCatching {
            val ap = File(rootfs, "system/bin/app_process64")
            val real = File(rootfs, "system/bin/app_process64.real")
            if (ap.exists() && !real.exists()) {
                ap.copyTo(real, overwrite = true)
                real.setExecutable(true, false)
            }
            copyAsset(context, "runtime/app_process_wrapper.sh", ap, executable = true)
        }

    suspend fun downloadAndDeploy(
        context: Context,
        artifacts: List<CatalogVectorArtifact>,
        onProgress: (Step) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(Step.DownloadVector)
            val rootfs = paths.rootfsDir
            val out = File(paths.downloads, "vector").apply { mkdirs() }
            val files = artifacts.associate { it.component to downloadVerified(it, out) }

            onProgress(Step.DeployVector)
            val inject = files.getValue(COMPONENT_INJECT)
            val dex = files.getValue(COMPONENT_DEX)
            val manager = files[COMPONENT_MANAGER]

            FileInputStream(inject).use { input ->
                FileOutputStream(File(rootfs, "system/lib64/libvector_inject.so")).use { input.copyTo(it) }
            }
            File(rootfs, "system/lib64/libvector_inject.so").setExecutable(true, false)
            FileInputStream(dex).use { input ->
                FileOutputStream(File(rootfs, "system/framework/vector-xposed.jar")).use { input.copyTo(it) }
            }
            manager?.let { apk ->
                FileInputStream(apk).use { input ->
                    FileOutputStream(File(paths.containerData, "staging/vector-manager.apk")).use { input.copyTo(it) }
                }
            }

            patchZygoteEntry(context, rootfs).getOrThrow()
            installSuTools(context, rootfs).getOrThrow()
            File(rootfs, "data/adb/vector").mkdirs()
            File(rootfs, "data/adb/modules").mkdirs()
            File(rootfs, "vector-manifest.json").writeText(
                """{"vectorCommit": "${artifacts.firstOrNull()?.commit ?: "unknown"}", """ +
                    """"targetApi": ${artifacts.firstOrNull()?.apiLevel ?: -1}}""",
            )
            onProgress(Step.Done)
        }
    }

    private fun downloadVerified(artifact: CatalogVectorArtifact, destDir: File): File {
        val target = File(destDir, artifact.component)
        val tmp = File(destDir, "${artifact.component}.download")
        java.net.URI(artifact.url).toURL().openStream().use { input ->
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
        if (!actual.equals(artifact.sha256, true)) {
            tmp.delete()
            throw IllegalStateException(FailState.ChecksumMismatch(artifact.sha256, actual).reason)
        }
        tmp.renameTo(target)
        return target
    }

    private fun copyAsset(context: Context, name: String, dest: File, executable: Boolean) {
        dest.parentFile?.mkdirs()
        context.assets.open(name).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest.setExecutable(executable, false)
        dest.setReadable(true, false)
    }

    companion object {
        const val COMPONENT_INJECT = "libvector_inject.so"
        const val COMPONENT_DEX = "xposed.dex"
        const val COMPONENT_MANAGER = "vector-manager.apk"
    }
}

fun Catalog.vectorArtifacts(): List<CatalogVectorArtifact> = vector ?: emptyList()
