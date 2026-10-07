package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import io.github.guellenmade.rootlessvm.vm.FailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
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
        // HTTPS enforced inside HttpFetch.open (including redirect re-validation).
        io.github.guellenmade.rootlessvm.net.HttpFetch.open(manifest.url).use { input ->
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
        val destCanonical = dest.canonicalFile
        XZInputStream(archive.inputStream().buffered(1 shl 16)).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                var entry: TarArchiveEntry?
                while (tarIn.nextTarEntry.also { entry = it } != null) {
                    val e = entry ?: break
                    // Zip-slip / tar-slip protection: reject entries that would
                    // escape the destination directory via ../ or absolute paths.
                    val out = File(dest, e.name).canonicalFile
                    require(out.path.startsWith(destCanonical.path + File.separator) || out == destCanonical) {
                        "tar entry escapes destination: ${e.name}"
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        if (e.isLink) {
                            // Hard link: the link target must also be inside dest.
                            val target = File(dest, e.linkName).canonicalFile
                            require(target.path.startsWith(destCanonical.path + File.separator) || target == destCanonical) {
                                "tar hardlink target escapes destination: ${e.linkName}"
                            }
                            out.delete()
                            target.copyTo(out, overwrite = true)
                            if (target.canExecute()) out.setExecutable(true)
                        } else if (e.isSymbolicLink) {
                            // Symlink: reject absolute targets and targets escaping dest.
                            val linkTarget = java.nio.file.Path.of(e.linkName)
                            require(!linkTarget.isAbsolute) { "absolute symlink rejected: ${e.linkName}" }
                            val resolved = out.parentFile.canonicalFile.toPath().resolve(linkTarget).normalize()
                            require(resolved.startsWith(destCanonical.toPath())) {
                                "symlink escapes destination: ${e.linkName}"
                            }
                            out.delete()
                            java.nio.file.Files.createSymbolicLink(out.toPath(), linkTarget)
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
