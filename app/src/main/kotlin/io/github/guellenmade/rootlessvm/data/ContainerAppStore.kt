package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ContainerApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val iconFile: String,
    val installedAt: Long,
    val systemApp: Boolean = false,
)

class ContainerAppStore(private val paths: ContainerPaths) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val storeFile: File get() = File(paths.base, "container-apps.json")
    private val iconDir: File get() = File(paths.base, "icons").apply { mkdirs() }

    fun load(): List<ContainerApp> {
        if (!storeFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ContainerApp>>(storeFile.readText())
        }.getOrDefault(emptyList())
    }

    fun save(apps: List<ContainerApp>) {
        storeFile.writeText(json.encodeToString(apps))
    }

    fun upsert(app: ContainerApp) {
        val apps = load().filterNot { it.packageName == app.packageName } + app
        save(apps.sortedBy { it.label.lowercase() })
    }

    fun remove(packageName: String) {
        save(load().filterNot { it.packageName == packageName })
        File(iconDir, "$packageName.png").delete()
    }

    fun iconFileFor(packageName: String): File = File(iconDir, "$packageName.png")

    fun findByPackage(packageName: String): ContainerApp? =
        load().firstOrNull { it.packageName == packageName }
}
