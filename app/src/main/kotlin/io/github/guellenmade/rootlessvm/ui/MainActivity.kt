package io.github.guellenmade.rootlessvm.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.activity.ComponentActivity
import io.github.guellenmade.rootlessvm.di.ServiceLocator

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels(
        factoryProducer = {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(ServiceLocator.get()) as T
                }
            }
        },
    )

    private val apkPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { u ->
                contentResolver.openInputStream(u)?.use { input ->
                    val tmp = java.io.File(cacheDir, "staged.apk")
                    tmp.outputStream().use { input.copyTo(it) }
                    viewModel.installApk(this, tmp)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.ui.collectAsState()
            val frame by viewModel.display.frame.collectAsState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            RootlessApp(
                state = state,
                onStart = { viewModel.startVm(this) },
                onStop = { viewModel.stopVm(this) },
                onPrepareVm = { viewModel.prepareVm(this) },
                onInstallApk = { apkPicker.launch("application/vnd.android.package-archive") },
                displayFrame = frame,
                onTap = { x, y -> scope.launch { viewModel.display.tap(x, y) } },
                onBack = { scope.launch { viewModel.display.back() } },
                onPinShortcut = { app ->
                    viewModel.rescan(this)
                    io.github.guellenmade.rootlessvm.di.ServiceLocator.get().shortcutSync.run {
                        requestPin(this@MainActivity, app)
                    }
                },
                onDismissFail = { viewModel.dismissFail() },
                onSuDecision = { uid, pkg, g, a -> viewModel.setSuDecision(uid, pkg, g, a) },
                onModuleToggle = { pkg, en -> viewModel.setModuleEnabled(pkg, en) },
                onSaveSettings = { viewModel.saveSettings(it) },
                onSnapshot = { viewModel.snapshot(it) },
            )
        }
    }

}
