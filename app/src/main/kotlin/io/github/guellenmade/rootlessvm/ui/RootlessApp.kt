package io.github.guellenmade.rootlessvm.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.guellenmade.rootlessvm.data.ContainerApp
import io.github.guellenmade.rootlessvm.data.VmSettings
import io.github.guellenmade.rootlessvm.root.SuEntry
import io.github.guellenmade.rootlessvm.vm.FailState
import io.github.guellenmade.rootlessvm.vm.VmState
import io.github.guellenmade.rootlessvm.xposed.XposedModule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootlessApp(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPrepareVm: () -> Unit,
    onInstallApk: () -> Unit,
    onPinShortcut: (ContainerApp) -> Unit,
    onDismissFail: () -> Unit,
    onSuDecision: (Int, String, Boolean, Boolean) -> Unit,
    onModuleToggle: (String, Boolean) -> Unit,
    onSaveSettings: (VmSettings) -> Unit,
    onSnapshot: (String) -> Unit,
    displayFrame: io.github.guellenmade.rootlessvm.vm.DisplaySession.Frame?,
    onTap: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    state.failDialog?.let { fail ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        LaunchedEffect(fail) {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(fail.userMessage))
        }
        AlertDialog(
            onDismissRequest = onDismissFail,
            title = { Text("Operation aborted") },
            text = { Text(fail.userMessage + "\n\n(This message was copied to the clipboard.)") },
            confirmButton = { TextButton(onClick = onDismissFail) { Text("OK") } },
        )
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("RootlessVM") }) },
    ) { padding ->
        var tab by remember { mutableIntStateOf(0) }
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Home", "Apps", "Root", "Xposed", "Settings").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> HomeTab(state, onStart, onStop, onPrepareVm, onInstallApk, onSnapshot, displayFrame, onTap, onBack)
                1 -> AppsTab(state, onInstallApk, onPinShortcut)
                2 -> RootTab(state, onSuDecision)
                3 -> XposedTab(state, onModuleToggle)
                4 -> SettingsTab(state, onSaveSettings)
            }
        }
    }
}

@Composable
fun HomeTab(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPrepareVm: () -> Unit,
    onInstallApk: () -> Unit,
    onSnapshot: (String) -> Unit,
    displayFrame: io.github.guellenmade.rootlessvm.vm.DisplaySession.Frame?,
    onTap: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        if (state.vmState is VmState.NotInstalled || state.prepareStep != null) {
            Text(
                "First launch: download everything — proot runtime, the Android rootfs " +
                    "(checksum-verified), and the Xposed (Vector) framework artifacts for this device. " +
                    "Everything lands in app-private storage; the host is never modified.",
            )
            Spacer(Modifier.height(8.dp))
            val busy = state.prepareStep != null
            Button(onClick = onPrepareVm, enabled = !busy) {
                Text(if (busy) "Downloading…" else "Download everything")
            }
            state.prepareStep?.let { step ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
            state.installProgress?.let { p ->
                if (p is io.github.guellenmade.rootlessvm.data.RootfsInstaller.Progress.Downloading) {
                    Text(
                        "${p.bytes / 1048576} / ${p.total / 1048576} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }
            return@Column
        }
        Text(
            when (state.vmState) {
                VmState.NotInstalled -> "VM not installed — download the rootfs in Settings first."
                VmState.Stopped -> "VM stopped."
                VmState.Starting -> "VM starting…"
                VmState.Running -> "VM running. Root + Xposed active inside the container only."
                is VmState.Stopping -> "VM stopping…"
                is VmState.Failed -> "VM failed: ${(state.vmState as VmState.Failed).fail.reason}"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = onStart, enabled = state.vmState is VmState.Stopped) { Text("Start VM") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onStop, enabled = state.vmState is VmState.Running) { Text("Stop VM") }
        }
        Spacer(Modifier.height(16.dp))
        state.gpu?.let { gpu ->
            Text(
                "GPU: ${gpu.vendor} (${gpu.rendererString}) — informative only. " +
                    "The proot container renders with llvmpipe software rendering; " +
                    "the host GPU is not used by the container.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        if (state.vmState is VmState.Running) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Container display (software-rendered, streamed): %.1f fps".format(displayFrame?.fps ?: 0.0),
                style = MaterialTheme.typography.labelMedium,
            )
            displayFrame?.bitmap?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Container display",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                onTap(
                                    (offset.x / size.width * bmp.width).toInt(),
                                    (offset.y / size.height * bmp.height).toInt(),
                                )
                            }
                        },
                )
            } ?: Text(
                displayFrame?.lastError?.let { "Display stream error: $it" } ?: "Waiting for container display…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Row {
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onInstallApk) { Text("Install APK into container") }
        Spacer(Modifier.height(8.dp))
        var snapName by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = snapName,
                onValueChange = { snapName = it },
                label = { Text("Snapshot name") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (snapName.isNotBlank()) onSnapshot(snapName) }) { Text("Create") }
        }
    }
}

@Composable
fun AppsTab(
    state: UiState,
    onInstallApk: () -> Unit,
    onPinShortcut: (ContainerApp) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            Button(onClick = onInstallApk) { Text("Install APK") }
        }
        Spacer(Modifier.height(12.dp))
        if (state.apps.isEmpty()) {
            Text("No container apps yet. Install APKs while the VM is running.")
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f)) {
                items(state.apps, key = { it.packageName }) { app ->
                    Column(
                        Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            onClick = { onPinShortcut(app) },
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.size(72.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge)
                            }
                        }
                        Text(app.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun RootTab(state: UiState, onSuDecision: (Int, String, Boolean, Boolean) -> Unit) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Superuser (inside the container only)", style = MaterialTheme.typography.titleMedium)
        if (state.suEntries.isEmpty()) {
            Text("No su requests yet.")
        } else {
            state.suEntries.forEach { e: SuEntry ->
                SuRow(e, onSuDecision)
                Divider()
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("su log", style = MaterialTheme.typography.titleMedium)
        state.suLogs.take(50).forEach { l ->
            Text("${l.packageName}: ${l.command} -> ${if (l.granted) "granted" else "denied"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SuRow(e: SuEntry, onSuDecision: (Int, String, Boolean, Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(e.packageName)
            Text("uid ${e.uid} — ${if (e.granted) "granted" else "denied"}${if (e.allowAlways) " (always)" else ""}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onSuDecision(e.uid, e.packageName, true, e.allowAlways) }) { Text("Allow") }
        TextButton(onClick = { onSuDecision(e.uid, e.packageName, false, e.allowAlways) }) { Text("Deny") }
    }
}

@Composable
fun XposedTab(state: UiState, onModuleToggle: (String, Boolean) -> Unit) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Xposed (Vector) inside the container", style = MaterialTheme.typography.titleMedium)
        Text(
            "Legacy Xposed API and libxposed API modules targeting the container's Android version.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (state.modules.isEmpty()) {
            Text("No modules registered yet. Install modules inside the container and enable them in the Vector manager inside the VM.")
        } else {
            state.modules.forEach { m: XposedModule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.appName)
                        Text(m.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = m.enabled, onCheckedChange = { onModuleToggle(m.packageName, it) })
                }
                Divider()
            }
        }
    }
}

@Composable
fun SettingsTab(state: UiState, onSaveSettings: (VmSettings) -> Unit) {
    var ram by remember(state.settings.ramMb) { mutableIntStateOf(state.settings.ramMb) }
    var cpus by remember(state.settings.cpuCount) { mutableIntStateOf(state.settings.cpuCount) }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("VM limits", style = MaterialTheme.typography.titleMedium)
        Text("RAM: $ram MB")
        Slider(value = ram.toFloat(), onValueChange = { ram = it.toInt() }, valueRange = 1024f..8192f, steps = 13)
        Text("CPU cores: $cpus")
        Slider(value = cpus.toFloat(), onValueChange = { cpus = it.toInt() }, valueRange = 1f..8f, steps = 6)
        Button(onClick = {
            onSaveSettings(VmSettings(ramMb = ram, cpuCount = cpus))
        }) { Text("Save") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Renderer: ${state.settings.renderer} (software). AVF/pKVM fast path: detected but roadmap — this build does not use it.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        state.installProgress?.let { p ->
            Spacer(Modifier.height(16.dp))
            Text(
                when (p) {
                    is io.github.guellenmade.rootlessvm.data.RootfsInstaller.Progress.Downloading ->
                        "Downloading rootfs… ${(p.bytes / 1048576)}/${p.total / 1048576} MB"
                    is io.github.guellenmade.rootlessvm.data.RootfsInstaller.Progress.Verifying -> "Verifying checksum…"
                    is io.github.guellenmade.rootlessvm.data.RootfsInstaller.Progress.Unpacking -> "Unpacking…"
                    io.github.guellenmade.rootlessvm.data.RootfsInstaller.Progress.Done -> "Rootfs ready."
                },
            )
        }
    }
}
