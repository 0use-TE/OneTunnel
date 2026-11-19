// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class TailPeer(val name: String, val online: Boolean, val ips: List<String>)
class TailSnapshot(val name: String, val state: String, val authUrl: String, val ips: List<String>, val peers: List<TailPeer>)

/** Same-UID Messenger channel. It never opens an HTTP controller or stores auth URLs. */
class CoreControl(private val context: Context) : ServiceConnection {
    var connected by mutableStateOf(false); private set
    var snapshots by mutableStateOf<Map<String, TailSnapshot>>(emptyMap()); private set
    var error by mutableStateOf(""); private set
    var browserRequest by mutableStateOf<Pair<String, String>?>(null); private set
    var reloading by mutableStateOf(false); private set
    var reloadQueued by mutableStateOf(false); private set
    val updating: Boolean get() = reloadQueued || reloading
    var reloadFailed by mutableStateOf(false); private set
    private var bound = false
    private var server: Messenger? = null
    private var sequence = 0
    private var reloadGeneration = 0
    private val pending = mutableMapOf<Int, Pair<String, String>>()
    private val loginFlow = TailLoginFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != TunnelService.CONTROL_RESPONSE) return@Handler false
        val request = pending.remove(message.arg1) ?: return@Handler true
        if (request.second == "reload") reloading = false
        runCatching {
            val result = JSONObject(message.data.getString("result").orEmpty())
            if (result.has("error")) { error = result.optString("error"); if (request.second == "reload") reloadFailed = true; if (request.second == "login") loginFlow.cancel(request.first); return@runCatching }
            if (result.optBoolean("reloaded")) { error = ""; snapshots = emptyMap(); return@runCatching }
            fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
            val name = result.getString("name")
            val state = result.optString("state")
            val peers = result.optJSONArray("peers") ?: JSONArray()
            val snapshot = TailSnapshot(name, state, result.optString("authUrl"), strings(result.optJSONArray("ips")),
                (0 until peers.length()).map { i -> val peer = peers.getJSONObject(i); TailPeer(peer.optString("name"), peer.optBoolean("online"), strings(peer.optJSONArray("ips"))) })
            snapshots = snapshots + (name to snapshot)
            error = ""
            if (loginFlow.receive(name, state, snapshot.authUrl)) browserRequest = name to snapshot.authUrl
        }.onFailure { error = "无法读取核心状态。"; if (request.second == "reload") reloadFailed = true; if (request.second == "login") loginFlow.cancel(request.first) }
        true
    })
    fun consumeBrowserRequest() { browserRequest = null }
    fun reloadSavedProfile() {
        if (updating) return
        reloadQueued = true; reloadFailed = false; error = ""
        val generation = ++reloadGeneration
        bind()
        dispatchQueuedReload()
        handler.postDelayed({
            if (reloadQueued && generation == reloadGeneration) {
                reloadQueued = false; reloadFailed = true
                error = "无法连接核心服务，配置已保存。"
            }
        }, 10_000)
    }
    private fun dispatchQueuedReload() {
        if (!reloadQueued || !connected) return
        reloadQueued = false
        if (!request("", "reload")) { reloadFailed = true; error = "无法应用配置，配置已保存。" }
    }
    fun bind() {
        if (bound) return
        bound = runCatching { context.bindService(Intent(context, TunnelService::class.java).setAction(TunnelService.CONTROL), this, 0) }.getOrDefault(false)
    }
    fun unbind() {
        if (!reloading) handler.removeCallbacksAndMessages(null)
        if (bound) runCatching { context.unbindService(this) }
        bound = false; connected = false; server = null
        if (reloading) pending.entries.removeAll { it.value.second != "reload" } else pending.clear()
        browserRequest = null
        snapshots = snapshots.mapValues { (_, old) -> TailSnapshot(old.name, old.state, "", old.ips, old.peers) }
    }
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { server = Messenger(binder); connected = true; dispatchQueuedReload() }
    override fun onServiceDisconnected(name: ComponentName?) {
        if (updating) { reloading = false; reloadQueued = false; reloadFailed = true }
        connected = false; server = null; pending.clear(); browserRequest = null
        loginFlow.clear()
    }
    override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name); unbind() }
    override fun onNullBinding(name: ComponentName?) { onServiceDisconnected(name); unbind() }
    fun request(name: String, action: String): Boolean {
        val remote = server ?: return false
        if (updating) return false
        if (pending.values.any { it.first == name }) return false
        val id = ++sequence
        pending[id] = name to action
        if (action == "login") loginFlow.begin(name)
        if (action == "reload") { reloading = true; reloadFailed = false }
        return runCatching {
            remote.send(Message.obtain(null, TunnelService.CONTROL_REQUEST).apply {
                arg1 = id; replyTo = replies; data = Bundle().apply { putString("name", name); putString("action", action) }
            })
            handler.postDelayed({ if (pending.remove(id) != null) {
                error = "核心请求超时，请重试。"
                if (action == "login") loginFlow.cancel(name)
                if (action == "reload") { reloading = false; reloadFailed = true }
            } }, if (action == "reload") 120_000 else 35_000)
            true
        }.getOrElse { pending.remove(id); if (action == "login") loginFlow.cancel(name); if (action == "reload") { reloading = false; reloadFailed = true }; error = "无法连接核心服务。"; false }
    }
}
