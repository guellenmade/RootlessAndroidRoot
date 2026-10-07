package io.github.guellenmade.rootlessvm.data

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VmSettings(
    val ramMb: Int = 3072,
    val cpuCount: Int = 4,
    val renderer: String = RENDERER_LLVMPIPE,
) {
    companion object {
        const val RENDERER_LLVMPIPE = "llvmpipe"
    }
}

class SettingsStore(private val paths: ContainerPaths) {
    private val file: File get() = File(paths.base, "settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Volatile
    private var cached: VmSettings = VmSettings()

    val current: VmSettings get() = cached

    fun load(): VmSettings {
        cached = if (file.exists()) {
            runCatching { json.decodeFromString<VmSettings>(file.readText()) }.getOrDefault(VmSettings())
        } else {
            VmSettings()
        }
        return cached
    }

    fun save(settings: VmSettings) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(settings))
        cached = settings
    }
}
