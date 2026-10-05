package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

/**
 * Source of rootfs manifests (ADR-008). First release: a pinned JSON catalog
 * shipped as an asset, fetchable from the project releases. The catalog maps
 * host ABIs to rootfs images with SHA-256 checksums; the installer refuses
 * anything that does not match.
 */
class RootfsCatalog(private val paths: io.github.guellenmade.rootlessvm.vm.ContainerPaths) {
    private val json = Json { ignoreUnknownKeys = true }

    fun catalogFile(): File = File(paths.base, "rootfs-catalog.json")

    fun bundledCatalogText(assets: android.content.res.AssetManager): String =
        assets.open("rootfs/catalog.json").bufferedReader().readText()

    fun parse(text: String): Catalog = json.decodeFromString(text)

    fun selectFor(abi: String, catalog: Catalog): RootfsManifest? =
        catalog.images.firstOrNull { it.apiLevel == catalog.primaryApi && abi in it.abis }?.let { img ->
            RootfsManifest(
                id = img.id,
                androidRelease = img.androidRelease,
                apiLevel = img.apiLevel,
                url = img.url,
                sha256 = img.sha256,
                sizeBytes = img.sizeBytes,
            )
        }
}

@kotlinx.serialization.Serializable
data class CatalogImage(
    val id: String,
    val androidRelease: String,
    val apiLevel: Int,
    val abis: List<String>,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

@kotlinx.serialization.Serializable
data class CatalogVectorArtifact(
    val component: String,
    val commit: String,
    val apiLevel: Int,
    val url: String,
    val sha256: String,
)

@kotlinx.serialization.Serializable
data class Catalog(
    val primaryApi: Int,
    val images: List<CatalogImage>,
    val vector: List<CatalogVectorArtifact> = emptyList(),
)
