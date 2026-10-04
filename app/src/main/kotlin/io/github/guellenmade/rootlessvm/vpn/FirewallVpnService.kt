package io.github.guellenmade.rootlessvm.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Deny-all firewall for the app UID (ADR-007): all traffic of this app
 * (including proot children, which share the UID) is routed into a TUN
 * device whose packets are read and dropped. The container therefore has
 * no network egress while the firewall is on.
 */
class FirewallVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var pumpThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFirewall()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        val conn = Builder()
            .setSession("RootlessVM firewall")
            .addAddress("10.111.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(1400)
            .establish() ?: run {
                stopSelf()
                return START_NOT_STICKY
            }
        tun = conn
        running = true
        pumpThread = Thread {
            val fd = conn.fileDescriptor
            val input = FileInputStream(fd)
            val buf = ByteBuffer.allocateDirect(32767)
            while (running) {
                runCatching {
                    buf.clear()
                    val n = input.read(buf.array() ?: ByteArray(0))
                    if (n > 0) {
                        FileOutputStream(fd).use { }
                    }
                }
            }
        }.apply { start() }
        return START_STICKY
    }

    override fun onRevoke() {
        stopFirewall()
        super.onRevoke()
    }

    private fun stopFirewall() {
        running = false
        pumpThread?.interrupt()
        tun?.close()
        tun = null
    }

    override fun onDestroy() {
        stopFirewall()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.guellenmade.rootlessvm.action.STOP_FIREWALL"
    }
}
