package io.github.guellenmade.rootlessvm.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import io.github.guellenmade.rootlessvm.data.ContainerApp
import io.github.guellenmade.rootlessvm.data.ContainerAppStore
import io.github.guellenmade.rootlessvm.vm.ContainerPaths
import io.github.guellenmade.rootlessvm.vm.ContainerSession
import io.github.guellenmade.rootlessvm.ui.ShortcutLaunchActivity

class ShortcutSync(
    private val paths: ContainerPaths,
    private val appStore: ContainerAppStore,
) {
    fun syncAll(context: Context, apps: List<ContainerApp>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val intent = { pkg: String ->
            Intent(context, ShortcutLaunchActivity::class.java).apply {
                action = ShortcutLaunchActivity.ACTION_OPEN_CONTAINER_APP
                putExtra(ShortcutLaunchActivity.EXTRA_PACKAGE, pkg)
            }
        }
        val infos = apps.map { app ->
            val iconFile = appStore.iconFileFor(app.packageName)
            val icon = if (iconFile.exists()) {
                val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
                Icon.createWithAdaptiveBitmap(
                    AdaptiveIconFactory.circleCrop(bmp ?: continueSyncFallback(app.packageName)),
                )
            } else {
                Icon.createWithResource(context, android.R.drawable.sym_def_app_icon)
            }
            ShortcutInfo.Builder(context, "vm-${app.packageName}")
                .setShortLabel(app.label.take(12))
                .setLongLabel(app.label)
                .setIcon(icon)
                .setIntent(intent(app.packageName))
                .build()
        }
        manager.dynamicShortcuts = infos
    }

    private fun continueSyncFallback(packageName: String): android.graphics.Bitmap {
        val src = BitmapFactory.decodeFile(appStore.iconFileFor(packageName).absolutePath)
        return src ?: Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888)
    }

    fun requestPin(context: Context, app: ContainerApp) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (!manager.isRequestPinShortcutSupported) return
        val info = manager.dynamicShortcuts.firstOrNull { it.id == "vm-${app.packageName}" } ?: return
        manager.requestPinShortcut(info, null)
    }

    fun handleLaunch(context: Context, packageName: String, session: ContainerSession) {
        val intent = Intent(context, ShortcutLaunchActivity::class.java)
        intent.putExtra(ShortcutLaunchActivity.EXTRA_PACKAGE, packageName)
        context.startActivity(intent)
    }
}
