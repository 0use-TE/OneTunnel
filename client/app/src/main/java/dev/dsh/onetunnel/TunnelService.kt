// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TunnelService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val launched = AtomicBoolean(false)
    private val terminating = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    @Volatile private var tun: ParcelFileDescriptor? = null
    private val status by lazy { StatusStore(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (!terminating.get()) runCatching { setUnderlyingNetworks(arrayOf(network)) } }
        override fun onLost(network: Network) { if (!terminating.get()) runCatching { setUnderlyingNetworks(null) } }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "统一 VPN 状态", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            terminate("idle", "已停止；原 Clash 不会由本程序自动启动")
            return START_NOT_STICKY
        }
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (!launched.compareAndSet(false, true)) return START_NOT_STICKY
        try {
            val notification = notification("正在配置核心，尚未验证出站连通性")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            status.write("starting", "正在启动；这会替换其他 VPN，所有连通性均待手动验证")
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
            terminate("error", "无法启动前台 VPN 服务，请检查系统权限与后台限制")
            return START_NOT_STICKY
        }
        worker.execute {
            try {
                val profile = ConfigStore(this).load()
                ProfileCheck.check(profile)
                if (terminating.get()) return@execute
                // Both address families are captured; no implicit IPv6 bypass.
                val device = Builder().setSession("一隧 · Mihomo").setMtu(1280)
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0).addRoute("::", 0)
                    .addDnsServer("172.19.0.2")
                    .addDisallowedApplication(packageName)
                    .setBlocking(false)
                    .establish() ?: error("VPN permission unavailable")
                synchronized(this) {
                    if (terminating.get()) { device.close(); return@execute }
                    tun = device
                }
                val home = File(noBackupFilesDir, "core").apply { mkdirs() }
                val error = NativeCore.start(home.absolutePath, profile, device.fd, this)
                if (error.isNotEmpty()) throw IllegalStateException(error)
                if (!terminating.get()) {
                    status.write("running", "TUN 与核心已启动；不是连通性证明，请按清单手动测试")
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("核心运行中 · 出站连通性待你验证"))
                }
            } catch (error: Throwable) {
                // Native errors are deliberately generic; never persist a raw imported profile or Java stack trace.
                val message = when (error) {
                    is UnsatisfiedLinkError -> "ARM64 核心加载失败，请勿继续使用此构建"
                    is IllegalArgumentException -> "配置检查未通过，请回到配置页检查"
                    else -> "核心或 TUN 启动失败：请核对密钥、规则、DNS 与系统权限。未保存详细日志以保护凭据。"
                }
                terminate("error", message)
            }
        }
        return START_NOT_STICKY
    }

    @Keep fun protectSocket(fd: Int): Boolean = !terminating.get() && protect(fd)

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, TunnelService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_status)
            .setContentTitle("一隧 · Mihomo").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    private fun terminate(finalState: String, message: String) {
        if (!terminating.compareAndSet(false, true)) return
        status.write("stopping", "正在停止并关闭所有出站")
        synchronized(this) { runCatching { tun?.close() }; tun = null }
        if (networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        // A stuck provider or a long-running native start cannot keep the VPN alive indefinitely.
        main.postDelayed({ finishTermination(finalState, message) }, 2000)
        worker.execute {
            runCatching { if (launched.get()) NativeCore.stop() }
            main.post { finishTermination(finalState, message) }
        }
    }
    private fun finishTermination(finalState: String, message: String) {
        if (!finished.compareAndSet(false, true)) return
        status.write(finalState, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        worker.shutdownNow()
        // This service is in :vpn. Killing it cannot kill the Compose UI, Clash, or Termux,
        // and guarantees that a partially initialized Go runtime cannot keep sockets alive.
        Process.killProcess(Process.myPid())
    }
    override fun onRevoke() { terminate("idle", "VPN 授权已撤回或被其他 VPN 替换，不会自动重连") }
    override fun onDestroy() {
        if (!finished.get()) terminate("idle", "服务已结束，不会自动重连")
        super.onDestroy()
    }
    companion object {
        const val START = "dev.dsh.onetunnel.START"
        const val STOP = "dev.dsh.onetunnel.STOP"
        private const val CHANNEL = "onetunnel-vpn"
        private const val NOTIFICATION = 3101
    }
}
