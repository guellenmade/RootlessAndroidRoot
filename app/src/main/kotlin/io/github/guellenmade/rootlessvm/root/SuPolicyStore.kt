package io.github.guellenmade.rootlessvm.root

import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SuEntry(
    val uid: Int,
    val packageName: String,
    val granted: Boolean,
    val allowAlways: Boolean = false,
    val timestamp: Long = 0,
)

@Serializable
data class SuLogEntry(
    val time: Long,
    val uid: Int,
    val packageName: String,
    val command: String,
    val granted: Boolean,
)

class SuPolicyStore(private val paths: ContainerPaths) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val policyFile: File get() = File(paths.suPolicyDir, "policy.json")
    private val logFile: File get() = File(paths.suPolicyDir, "log.txt")
    private val requestsDir: File get() = File(paths.suPolicyDir, "requests").apply { mkdirs() }

    private var watcherJob: Job? = null

    fun load(): List<SuEntry> {
        if (!policyFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<SuEntry>>(policyFile.readText()) }
            .getOrDefault(emptyList())
    }

    fun save(entries: List<SuEntry>) {
        policyFile.parentFile?.mkdirs()
        policyFile.writeText(json.encodeToString(entries))
    }

    fun setDecision(uid: Int, packageName: String, granted: Boolean, always: Boolean) {
        val entries = load().filterNot { it.uid == uid }
        save(entries + SuEntry(uid, packageName, granted, always, System.currentTimeMillis()))
        resolveOpenRequest(uid, granted)
    }

    fun log(): List<SuLogEntry> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().mapNotNull { line ->
            val m = Regex("uid=(\\d+) caller=([^ ]+) cmd=(.*) -> (granted|denied|prompt)").find(line)
            m?.let {
                SuLogEntry(0, it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3], it.groupValues[4] == "granted")
            }
        }.reversed()
    }

    fun appendLog(entry: SuLogEntry) {
        logFile.parentFile?.mkdirs()
        logFile.appendText("${entry.time} uid=${entry.uid} caller=${entry.packageName} cmd=${entry.command} -> ${if (entry.granted) "granted" else "denied"}\n")
    }

    fun startRequestWatcher(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        stopRequestWatcher()
        watcherJob = scope.launch {
            while (isActive) {
                requestsDir.listFiles()?.forEach { req ->
                    runCatching {
                        val (uid, pkg) = req.readText().trim().split(":", limit = 2).let { Pair(it[0].toInt(), it.getOrElse(1) { "?" }) }
                        val existing = load().firstOrNull { it.uid == uid }
                        if (existing == null) {
                            setDecision(uid, pkg, granted = false, always = false)
                        }
                        req.delete()
                    }
                }
                delay(REQUEST_POLL_MS)
            }
        }
    }

    fun stopRequestWatcher() {
        watcherJob?.cancel()
        watcherJob = null
    }

    private fun resolveOpenRequest(uid: Int, granted: Boolean) {
        File(paths.suPolicyDir, "responses").apply { mkdirs() }
        File(File(paths.suPolicyDir, "responses"), "$uid").writeText(if (granted) "granted" else "denied")
    }

    companion object {
        const val REQUEST_POLL_MS = 1000L
    }
}
