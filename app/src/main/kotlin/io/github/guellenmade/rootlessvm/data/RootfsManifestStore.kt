package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RootfsManifest(
    val id: String,
    val androidRelease: String,
    val apiLevel: Int,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class BuiltArtifacts(
    val vectorCommit: String,
    val targetApi: Int,
)

class RootfsManifestStore(private val paths: ContainerPaths) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun manifest(): RootfsManifest? {
        val f = File(paths.base, "rootfs-manifest.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<RootfsManifest>(f.readText()) }.getOrNull()
    }

    fun saveManifest(m: RootfsManifest) {
        File(paths.base, "rootfs-manifest.json").writeText(json.encodeToString(m))
    }

    fun builtArtifacts(): BuiltArtifacts? {
        val f = File(paths.rootfsDir, "vector-manifest.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<BuiltArtifacts>(f.readText()) }.getOrNull()
    }

    fun isRootfsInstalled(): Boolean =
        File(paths.rootfsDir, "system/bin/app_process64").exists()

    fun isVectorDeployed(): Boolean =
        File(paths.rootfsDir, "system/lib64/libvector_inject.so").exists()
}
