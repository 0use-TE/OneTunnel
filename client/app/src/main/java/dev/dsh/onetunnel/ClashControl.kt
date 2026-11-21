// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.content.*
import android.os.*
import androidx.compose.runtime.*
import org.json.JSONObject

/** Separate UI channel; tests are limited to four requests and never block VPN control. */
class ClashControl(private val context: Context, private val profileChanged: (() -> Unit) -> Unit) : ServiceConnection {
    var connected by mutableStateOf(false); private set
    var state by mutableStateOf<ClashState?>(null); private set
    var error by mutableStateOf(""); private set
    var selecting by mutableStateOf(false); private set
    var testing by mutableStateOf<Set<String>>(emptySet()); private set
    var testingBatch by mutableStateOf(false); private set
    var cancellingTests by mutableStateOf(false); private set
    var delays by mutableStateOf<Map<String, ClashDelay>>(emptyMap()); private set
    private var bound = false
    private var server: Messenger? = null
    private var sequence = 0
    private val handler = Handler(Looper.getMainLooper())
    private data class Request(val action: String, val group: String, val target: String, val generation: String,
        val probe: ClashProbeSession.Probe? = null)
    private val pending = mutableMapOf<Int, Request>()
    private val probes = ClashProbeSession()
    private var viewGroup = ""
    private var releaseAfterSelection = false
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val request = pending.remove(message.arg1) ?: return@Handler true
        if (request.action == "delay") {
            val current = probes.finish(requireNotNull(request.probe))
            syncTests()
            if (!current) return@Handler true
        }
        runCatching {
            val result = JSONObject(message.data.getString("result").orEmpty())
            if (request.action == "cancel") {
                probes.cancellationFinished(); syncTests()
                if (result.has("error")) error = result.optString("error")
                return@runCatching
            }
            if (result.has("error")) {
                if (request.action == "select") selecting = false
                if (request.action == "delay") {
                    if (request.generation == state?.generation && request.group == viewGroup)
                        delays = delays + (request.target to failedProbe(request, "control"))
                } else error = result.optString("error")
                return@runCatching
            }
            when (request.action) {
                "state" -> {
                    if (request.group != viewGroup) return@runCatching
                    val groups = result.getJSONArray("groups")
                    val nodes = result.getJSONArray("nodes")
                    val generation = result.getString("generation")
                    if (state?.generation != generation) { cancelTests(); delays = emptyMap() }
                    state = ClashState(generation, (0 until groups.length()).map { i -> groups.getJSONObject(i).let {
                        ClashGroup(it.getString("name"), it.getString("type"), it.optString("now"), it.optInt("count"))
                    } }, result.optString("group"), (0 until nodes.length()).map { i -> nodes.getJSONObject(i).let {
                        ClashNode(it.getString("name"), it.getString("type"), if (it.isNull("delay")) null else it.getInt("delay"),
                            it.optInt("timeoutMs", 5_000).coerceIn(1, 30_000), it.optString("probeHost"))
                    } })
                    error = ""
                }
                "select" -> profileChanged {
                    selecting = false
                    if (releaseAfterSelection) unbind(force = true) else refresh(viewGroup)
                }
                "delay" -> if (result.optString("generation") == state?.generation && request.group == viewGroup) {
                    delays = delays + (request.target to ClashDelay(result.optString("status"), result.optInt("delay"),
                        result.optString("reason"), result.optString("host"), result.optInt("timeoutMs")))
                }
            }
        }.onFailure {
            if (request.action == "select") selecting = false
            if (request.action == "cancel") { probes.cancellationFinished(); syncTests() }
            if (request.action == "delay") delays = delays + (request.target to failedProbe(request, "control"))
            error = "代理状态读取失败，请重试。"
        }
        pump()
        if (releaseAfterSelection && !selecting) unbind(force = true)
        true
    })
    fun bind() {
        releaseAfterSelection = false
        if (!bound) bound = runCatching { context.bindService(Intent(context, TunnelService::class.java).setAction(TunnelService.CONTROL), this, 0) }.getOrDefault(false)
    }
    fun unbind(force: Boolean = false) {
        if (selecting && !force) { releaseAfterSelection = true; cancelTests(); return }
        releaseAfterSelection = false
        cancelTests(); handler.removeCallbacksAndMessages(null)
        if (bound) runCatching { context.unbindService(this) }
        bound = false; connected = false; server = null; pending.clear(); selecting = false; state = null; delays = emptyMap()
        probes.reset(); syncTests()
    }
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { server = Messenger(binder); connected = true; refresh(viewGroup) }
    override fun onServiceDisconnected(name: ComponentName?) {
        val reconcile = selecting
        unbind(force = true)
        if (reconcile) { selecting = true; profileChanged { selecting = false } }
    }
    override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name) }
    override fun onNullBinding(name: ComponentName?) { onServiceDisconnected(name) }
    fun refresh(group: String) {
        if (group != viewGroup) { cancelTests(); delays = emptyMap() }
        viewGroup = group
        if (pending.values.none { it.action == "state" && it.group == group }) send(Request("state", group, "", ""))
    }
    fun select(group: String, target: String) {
        if (selecting) return
        val generation = state?.generation ?: return
        selecting = true; error = ""
        if (!send(Request("select", group, target, generation))) selecting = false
    }
    fun test(group: String, names: List<String>) {
        val generation = state?.generation ?: return
        if (!connected || selecting || group != viewGroup ||
            !probes.start(generation, group, names, state?.nodes.orEmpty().associateBy { it.name })) return
        error = ""
        pump()
    }
    fun cancelTests() {
        val cancelled = probes.cancel()
        delays = delays + cancelled.associateWith { ClashDelay("cancelled") }
        // Retire old requests without applying results; wait for their slots to
        // drain before restarting so cancelled native work cannot occupy them.
        syncTests()
        if (probes.awaitingCancellation && pending.values.none { it.action == "cancel" }) {
            val generation = state?.generation
            if (generation == null || !send(Request("cancel", "", "", generation))) {
                probes.cancellationFinished(); syncTests()
            }
        }
    }
    private fun syncTests() {
        testing = probes.testing; testingBatch = probes.busy; cancellingTests = probes.cancelling
    }
    private fun failedProbe(request: Request, reason: String, status: String = "failed") =
        ClashDelay(status, reason = reason, host = request.probe?.host.orEmpty(), timeoutMs = request.probe?.timeoutMs ?: 0)
    private fun pump() {
        while (true) {
            val probe = probes.next() ?: break
            syncTests()
            if (probe.generation != state?.generation ||
                !send(Request("delay", probe.group, probe.target, probe.generation, probe))) {
                probes.finish(probe)
                delays = delays + (probe.target to ClashDelay("failed", reason = "control", host = probe.host, timeoutMs = probe.timeoutMs))
                syncTests()
            }
        }
    }
    private fun send(request: Request): Boolean {
        val remote = server ?: return false
        val id = ++sequence
        pending[id] = request
        return runCatching {
            remote.send(Message.obtain(null, TunnelService.CONTROL_REQUEST).apply {
                arg1 = id; replyTo = replies
                data = Bundle().apply {
                    putString("action", "clash-${request.action}"); putString("name", request.group)
                    putString("target", request.target); putString("generation", request.generation)
                }
            })
            handler.postDelayed({
                if (pending.remove(id) != null) {
                    if (request.action == "delay") {
                        val current = probes.finish(requireNotNull(request.probe))
                        syncTests()
                        if (current && request.generation == state?.generation && request.group == viewGroup)
                            delays = delays + (request.target to failedProbe(request, "ipc", "timeout"))
                        pump()
                    }
                    else if (request.action == "cancel") {
                        probes.cancellationFinished(); syncTests()
                        error = "停止测速请求超时，请重试。"
                    }
                    else {
                        selecting = false; error = "代理请求超时，请重试。"
                        if (releaseAfterSelection) unbind(force = true)
                    }
                }
            }, request.probe?.responseTimeoutMs ?: 12_000L)
            true
        }.getOrElse { pending.remove(id); error = "无法连接代理服务。"; false }
    }
}
