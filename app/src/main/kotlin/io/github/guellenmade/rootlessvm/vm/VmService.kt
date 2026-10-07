package io.github.guellenmade.rootlessvm.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.guellenmade.rootlessvm.di.ServiceLocator
import io.github.guellenmade.rootlessvm.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locator get() = ServiceLocator.get()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting VM…"))
                scope.launch {
                    val fail = locator.vmController.startVmBlocking {
                        updateNotification("VM running — root and Xposed active inside the container")
                        locator.suPolicy.startRequestWatcher()
                    }
                    if (fail != null) {
                        updateNotification("VM failed: ${fail.userMessage}")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                locator.vmController.stopVm()
                locator.suPolicy.stopRequestWatcher()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // stopVm() blocks on proc.waitFor(); run off the main thread to avoid ANR.
        scope.launch { locator.vmController.stopVm() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RootlessVM", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RootlessVM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "vm_status"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "io.github.guellenmade.rootlessvm.action.START_VM"
        const val ACTION_STOP = "io.github.guellenmade.rootlessvm.action.STOP_VM"

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, VmService::class.java).setAction(ACTION_START))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, VmService::class.java).setAction(ACTION_STOP))
        }
    }
}
