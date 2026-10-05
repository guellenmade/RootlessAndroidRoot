package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import io.github.guellenmade.rootlessvm.vm.FailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZFileInputStream
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
        XZFileInputStream(archive.inputStream().buffered(1 shl 16)).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                var entry: TarArchiveEntry?
                while (tarIn.nextTarEntry.also { entry = it } != null) {
                    val e = entry ?: break
                    val out = File(dest, e.name)
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        if (e.isLink) {
                            val target = File(dest, e.linkName)
                            out.delete()
                            target.copyTo(out, overwrite = true)
                            if (target.canExecute()) out.setExecutable(true)
                        } else if (e.isSymbolicLink) {
                            out.delete()
                            java.nio.file.Files.createSymbolicLink(
                                out.toPath(),
                                java.nio.file.Path.of(e.linkName),
                            )
                        } else {
                            out.outputStream().use { tarIn.copyTo(it) }
                            if (e.mode and 0x100 != 0) out.setExecutable(true)
                        }
                    }
                }
            }
        }
    }

    fun deletePartial() {
        File(paths.downloads, "rootfs.tar.xz").delete()
    }
}
