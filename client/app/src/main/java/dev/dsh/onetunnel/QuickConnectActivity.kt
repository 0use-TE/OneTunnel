// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Direct PendingIntent activity: permission dialogs never run through a receiver trampoline. */
class QuickConnectActivity : ComponentActivity() {
    private var awaitingPermission = false
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingPermission = false
        if (it.resultCode == RESULT_OK) connect() else finish()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { awaitingPermission = false; prepareVpn() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingPermission = savedInstanceState?.getBoolean("awaitingPermission") == true
        if (awaitingPermission) return // ActivityResultRegistry restores an outstanding permission dialog.
        when (intent.action) {
            DISCONNECT -> lifecycleScope.launch {
                lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                    runCatching { TunnelActions.stop(this@QuickConnectActivity) }.onFailure { showError("停止失败，请打开 OneTunnel") }
                    finish()
                }
            }
            CONNECT -> lifecycleScope.launch {
                val state = StatusStore(this@QuickConnectActivity).read()
                if (state.active) {
                    if (state.state != "running") startActivity(Intent(this@QuickConnectActivity, MainActivity::class.java))
                    finish(); return@launch
                }
                val valid = withContext(Dispatchers.IO) { runCatching { ProfileCheck.check(ConfigStore(this@QuickConnectActivity).load()) }.isSuccess }
                if (!valid) {
                    showError("请先添加连接")
                    startActivity(Intent(this@QuickConnectActivity, MainActivity::class.java)); finish(); return@launch
                }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    awaitingPermission = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else prepareVpn()
            }
            else -> { startActivity(Intent(this, MainActivity::class.java)); finish() }
        }
    }
    private fun prepareVpn() {
        runCatching {
            val permission = VpnService.prepare(this)
            if (permission == null) connect() else { awaitingPermission = true; vpnPermission.launch(permission) }
        }.onFailure { showError("无法申请 VPN 权限"); finish() }
    }
    private fun connect() {
        lifecycleScope.launch {
            lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                runCatching { startForegroundService(Intent(this@QuickConnectActivity, TunnelService::class.java).setAction(TunnelService.START)) }
                    .onFailure { showError("无法启动，请打开 OneTunnel 连接") }
                finish()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingPermission", awaitingPermission)
        super.onSaveInstanceState(outState)
    }
    private fun showError(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    companion object {
        const val CONNECT = "dev.dsh.onetunnel.QUICK_CONNECT"
        const val DISCONNECT = "dev.dsh.onetunnel.QUICK_DISCONNECT"
    }
}

object TunnelActions {
    fun stop(context: Context) {
        context.startService(Intent(context, TunnelService::class.java).setAction(TunnelService.STOP))
    }
}
