package io.github.guellenmade.rootlessvm.xposed

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class XposedModule(
    val packageName: String,
    val apkPath: String,
    val appName: String,
    val enabled: Boolean = false,
    val scope: List<String> = emptyList(),
)

@Serializable
data class XposedConfig(
    val modules: List<XposedModule> = emptyList(),
    val verboseLog: Boolean = false,
)

class XposedModuleStore(private val paths: ContainerPaths) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configFile: File get() = File(paths.xposedConfigDir, "modules_config.json")
    private val logFile: File get() = File(paths.xposedConfigDir, "all.log")

    fun load(): XposedConfig {
        if (!configFile.exists()) return XposedConfig()
        return runCatching { json.decodeFromString<XposedConfig>(configFile.readText()) }.getOrDefault(XposedConfig())
    }

    fun save(config: XposedConfig) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(json.encodeToString(config))
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        val cfg = load()
        save(cfg.copy(modules = cfg.modules.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }))
    }

    fun setScope(packageName: String, scope: List<String>) {
        val cfg = load()
        save(cfg.copy(modules = cfg.modules.map { if (it.packageName == packageName) it.copy(scope = scope) else it }))
    }

    fun upsert(module: XposedModule) {
        val cfg = load()
        save(cfg.copy(modules = cfg.modules.filterNot { it.packageName == module.packageName } + module))
    }

    fun logs(): List<String> =
        if (logFile.exists()) logFile.readLines().takeLast(500).reversed() else emptyList()

    fun clearLogs() {
        logFile.writeText("")
    }
}
