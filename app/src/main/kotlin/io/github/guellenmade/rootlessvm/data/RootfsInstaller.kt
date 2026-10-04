package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import io.github.guellenmade.rootlessvm.vm.FailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class RootfsInstaller(
    private val paths: ContainerPaths,
    private val manifests: RootfsManifestStore,
) {
    sealed class Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress()
        data class Verifying(val calculated: String) : Progress()
        data class Unpacking(val entries: Int) : Progress()
        data object Done : Progress()
    }

    fun requiredStorageBytes(): Long = 4L * 1024 * 1024 * 1024

    fun checkStorage(): FailState? {
        val usable = paths.base.usableSpace
        return if (usable < requiredStorageBytes()) {
            FailState.InsufficientStorage(requiredStorageBytes(), usable)
        } else {
            null
        }
    }

    suspend fun install(manifest: RootfsManifest, onProgress: (Progress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                paths.ensure()
                checkStorage()?.let { error(it.reason) }

                val archive = File(paths.downloads, "rootfs.tar.xz").apply { delete() }
                download(manifest, archive, onProgress)
                onProgress(Progress.Verifying("…"))
                verifyChecksum(archive, manifest.sha256)
                onProgress(Progress.Unpacking(0))
                unpack(archive, paths.rootfsDir)
                archive.delete()
                manifests.saveManifest(manifest)
                onProgress(Progress.Done)
            }.onFailure { deletePartial() }
        }

    private fun download(manifest: RootfsManifest, target: File, onProgress: (Progress) -> Unit) {
        val url = java.net.URI(manifest.url).toURL()
        url.openStream().use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var total = 0L
                while (input.read(buf).also { read = it } > 0) {
                    out.write(buf, 0, read)
                    total += read
                    onProgress(Progress.Downloading(total, manifest.sizeBytes))
                }
            }
        }
    }

    private fun verifyChecksum(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } > 0) {
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException(
                FailState.ChecksumMismatch(expected, actual).reason,
            )
        }
    }

    private fun unpack(archive: File, dest: File) {
        dest.mkdirs()
        ProcessBuilder(
            "tar", "-xJf", archive.absolutePath, "-C", dest.absolutePath,
        ).apply {
            redirectErrorStream(true)
        }.start().waitFor().let { rc ->
            if (rc != 0) error("unpack-failure:$rc")
        }
    }

    fun deletePartial() {
        File(paths.downloads, "rootfs.tar.xz").delete()
    }
}
