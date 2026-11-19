// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Message
import android.os.Messenger
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
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
    @Volatile private var nativeReady = false
    private var authOnly = false
    private var authNode = ""
    private val authPolling = AtomicBoolean(false)
    private class NativeFailure(val code: String) : Exception()
    private val startupTimeout = Runnable {
        if (!nativeReady && !terminating.get()) {
            Log.e("OneTunnel", "startup_timeout")
            terminate("error", "准备或启动超时，请检查规则数据库下载与网络后重试")
        }
    }
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != applicationInfo.uid || message.what != CONTROL_REQUEST || terminating.get()) return@Handler true
        val reply = message.replyTo ?: return@Handler true
        val name = message.data.getString("name").orEmpty()
        val action = message.data.getString("action").orEmpty()
        val requestId = message.arg1
        if (name.length > 512 || action !in listOf("status", "login", "logout", "reload")) return@Handler true
        worker.execute {
            val result = runCatching {
                if (!nativeReady) "{\"error\":\"核心尚未就绪\"}"
                else if (action == "reload") {
                    require(!authOnly)
                    Log.i("OneTunnel", "stage=profile_reload_begin")
                    val profile = ConfigStore(this).load()
                    ProfileCheck.check(profile)
                    val error = NativeCore.reload(ConnectionPolicy.runtime(profile))
                    if (error.isNotEmpty()) {
                        terminate("error", "配置应用失败，连接已停止")
                        "{\"error\":\"配置应用失败，连接已停止\"}"
                    } else {
                        Log.i("OneTunnel", "stage=profile_reload_complete")
                        "{\"reloaded\":true}"
                    }
                } else NativeCore.tailscale(name, action)
            }
                .getOrDefault("{\"error\":\"核心操作失败\"}")
            runCatching {
                val code = JSONObject(result).optString("code")
                if (code.matches(Regex("TS_[A-Z_]{1,240}"))) Log.w("OneTunnel", "tailscale=$code")
            }
            val response = Message.obtain(null, CONTROL_RESPONSE).apply {
                arg1 = requestId; data = Bundle().apply { putString("result", result) }
            }
            runCatching { reply.send(response) }
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == CONTROL) control.binder else super.onBind(intent)
    private val pollAuth = object : Runnable {
        override fun run() {
            if (terminating.get() || !authOnly) return
            if (nativeReady && authPolling.compareAndSet(false, true)) worker.execute {
                try {
                    val result = JSONObject(NativeCore.tailscale(authNode, "status"))
                    if (result.optString("state") == "Running") terminate("authenticated", "Tailscale 认证完成，身份已保存；尚未接管 VPN")
                } catch (_: Throwable) { /* No raw core error or auth URL is persisted. */ }
                finally { authPolling.set(false) }
            }
            main.postDelayed(this, 3000)
        }
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
        if (intent?.action !in listOf(START, AUTH)) { stopSelf(); return START_NOT_STICKY }
        if (!launched.compareAndSet(false, true)) return START_NOT_STICKY
        authOnly = intent?.action == AUTH
        authNode = intent?.getStringExtra("name").orEmpty()
        try {
            val notification = notification("正在连接")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            status.write(if (authOnly) "auth-starting" else "preparing", if (authOnly) "正在准备 Tailscale 登录" else "正在准备规则数据库")
            Log.i("OneTunnel", if (authOnly) "stage=auth_prepare" else "stage=profile_prepare")
            main.postDelayed(startupTimeout, 120_000)
        } catch (_: Exception) {
            terminate("error", "无法启动前台 VPN 服务，请检查系统权限与后台限制")
            return START_NOT_STICKY
        }
        worker.execute {
            try {
                val profile = ConfigStore(this).load()
                ProfileCheck.check(profile)
                if (terminating.get()) return@execute
                if (authOnly) {
                    val home = File(noBackupFilesDir, "core").apply { mkdirs() }
                    val error = NativeCore.prepareTailscale(home.absolutePath, profile, authNode)
                    if (error.isNotEmpty()) throw IllegalStateException(error)
                    nativeReady = true
                    main.removeCallbacks(startupTimeout)
                    status.write("auth", "Tailscale 登录中，继续使用当前网络；未接管 VPN")
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("Tailscale 登录中 · 未接管 VPN"))
                    main.post(pollAuth)
                    main.postDelayed({ if (!terminating.get()) terminate("idle", "Tailscale 登录会话已超时，请重试") }, 10 * 60 * 1000L)
                    return@execute
                }
                val home = File(noBackupFilesDir, "core").apply { mkdirs() }
                val runtimeProfile = ConnectionPolicy.runtime(profile)
                // Prepare downloaded databases before replacing the user's existing VPN.
                val preparation = NativeCore.prepare(home.absolutePath, runtimeProfile)
                if (preparation.isNotEmpty()) throw NativeFailure(preparation)
                if (terminating.get()) return@execute
                status.write("starting", "正在连接")
                Log.i("OneTunnel", "stage=tun_establish")
                main.removeCallbacks(startupTimeout)
                main.postDelayed(startupTimeout, 60_000)
                // Both address families are captured; no implicit IPv6 bypass.
                val device = Builder().setSession("OneTunnel").setMtu(1280)
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0).addRoute("::", 0)
                    .addDnsServer("172.19.0.2")
                    .addDisallowedApplication(packageName)
                    .setBlocking(false)
                    // Protected core sockets follow the system default network;
                    // none are bound with Network.bindSocket. Declaring the VPN
                    // itself as its upstream creates a recursive dependency.
                    .setUnderlyingNetworks(null)
                    .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                    .establish() ?: error("VPN permission unavailable")
                synchronized(this) {
                    if (terminating.get()) { device.close(); return@execute }
                    tun = device
                }
                Log.i("OneTunnel", "stage=core_start")
                val error = NativeCore.start(home.absolutePath, runtimeProfile, device.fd, this)
                if (error.isNotEmpty()) throw NativeFailure(error)
                nativeReady = true
                main.removeCallbacks(startupTimeout)
                if (!terminating.get()) {
                    Log.i("OneTunnel", "stage=running")
                    status.write("running", "TUN 与核心已启动；不是连通性证明，请按清单手动测试")
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("已启动"))
                }
            } catch (error: Throwable) {
                // Native errors are deliberately generic; never persist a raw imported profile or Java stack trace.
                val message = when (error) {
                    is NativeFailure -> when(error.code) {
                        "RULE_DATABASE_UNAVAILABLE" -> "规则数据库下载失败或文件不完整，请检查网络后重试"
                        "ANDROID_PACKAGE_ACCESS" -> "Android 不允许访问系统应用列表，隧道接入失败"
                        "TUN_PERMISSION_DENIED" -> "Android 拒绝隧道接口操作"
                        "TUN_INVALID_FD" -> "系统隧道句柄已失效，请重新连接"
                        "TUN_ATTACH_FAILED" -> "Android 隧道接入失败"
                        "TAILSCALE_INBOUND_FAILED" -> "Tailscale 入站端口启动失败，请检查端口设置后重试"
                        else -> "核心配置准备或启动失败，请检查配置"
                    }
                    is UnsatisfiedLinkError -> "ARM64 核心加载失败，请勿继续使用此构建"
                    is IllegalArgumentException -> "配置检查未通过，请回到配置页检查"
                    else -> "核心或 TUN 启动失败：请核对密钥、规则、DNS 与系统权限。未保存详细日志以保护凭据。"
                }
                Log.e("OneTunnel", "startup_failed category=" + when (error) {
                    is NativeFailure -> if (error.code in setOf("RULE_DATABASE_UNAVAILABLE", "ANDROID_PACKAGE_ACCESS", "TUN_PERMISSION_DENIED", "TUN_INVALID_FD", "TUN_ATTACH_FAILED", "TAILSCALE_INBOUND_FAILED")) error.code else "NATIVE_CONFIG"
                    is UnsatisfiedLinkError -> "LIBRARY_LOAD"
                    is IllegalArgumentException -> "PROFILE_VALIDATION"
                    else -> "SERVICE_OR_TUN"
                })
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
            .setContentTitle("OneTunnel").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    private fun terminate(finalState: String, message: String) {
        if (!terminating.compareAndSet(false, true)) return
        nativeReady = false
        main.removeCallbacks(pollAuth)
        main.removeCallbacks(startupTimeout)
        status.write("stopping", "正在停止并关闭所有出站")
        synchronized(this) { runCatching { tun?.close() }; tun = null }
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
        const val AUTH = "dev.dsh.onetunnel.AUTH"
        const val CONTROL = "dev.dsh.onetunnel.CONTROL"
        const val CONTROL_REQUEST = 100
        const val CONTROL_RESPONSE = 101
        private const val CHANNEL = "onetunnel-vpn"
        private const val NOTIFICATION = 3101
    }
}
