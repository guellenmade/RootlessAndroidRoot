package io.github.guellenmade.rootlessvm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.guellenmade.rootlessvm.di.ServiceLocator
import io.github.guellenmade.rootlessvm.vm.ContainerSession
import io.github.guellenmade.rootlessvm.vm.VmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShortcutLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName == null) {
            finish()
            return
        }
        val locator = ServiceLocator.get()
        if (locator.vmController.state.value !is io.github.guellenmade.rootlessvm.vm.VmState.Running) {
            VmService.start(this)
        }
        val session = ContainerSession(locator.paths, locator.rootfsManifest)
        // lifecycleScope is cancelled when the activity is destroyed, preventing leaks.
        lifecycleScope.launch(Dispatchers.IO) {
            repeat(30) {
                if (locator.vmController.state.value is io.github.guellenmade.rootlessvm.vm.VmState.Running) {
                    session.launchApp(packageName, null)
                    return@launch
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        val main = android.content.Intent(this, MainActivity::class.java)
        startActivity(main)
        finish()
    }

    companion object {
        const val ACTION_OPEN_CONTAINER_APP = "io.github.guellenmade.rootlessvm.action.OPEN_CONTAINER_APP"
        const val EXTRA_PACKAGE = "packageName"
    }
}
