// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

data class ClashGroup(val name: String, val type: String, val now: String, val count: Int) {
    val manual get() = type.lowercase() in listOf("select", "selector")
    val label get() = when (type.lowercase()) {
        "select", "selector" -> "手动选择"
        "url-test", "urltest" -> "自动选择"
        "fallback" -> "故障转移"
        "load-balance", "loadbalance" -> "负载均衡"
        "relay" -> "中继"
        else -> type
    }
}
data class ClashNode(val name: String, val type: String, val delay: Int? = null,
    val timeoutMs: Int = 5_000, val probeHost: String = "")
data class ClashState(val generation: String, val groups: List<ClashGroup>, val group: String, val nodes: List<ClashNode>)
data class ClashDelay(val status: String, val millis: Int = 0, val reason: String = "",
    val host: String = "", val timeoutMs: Int = 0) {
    val label get() = when (status) {
        "ok" -> "$millis ms"
        "cancelled" -> "已停止"
        "timeout" -> if (reason == "ipc") "请求超时" else "探测超时"
        else -> when (reason) {
            "dns" -> "DNS 失败"
            "tls" -> "TLS 失败"
            "status" -> "响应不符"
            "control" -> "请求失败"
            else -> "探测失败"
        }
    }
}

object ClashProfiles {
    fun selectionLabel(group: ClashGroup, groups: List<ClashGroup>): String {
        val path = mutableListOf<String>()
        val seen = mutableSetOf(group.name)
        var next = group.now
        while (next.isNotBlank() && seen.add(next)) {
            path.add(next)
            next = groups.firstOrNull { it.name == next }?.now.orEmpty()
        }
        return path.joinToString(" → ").ifBlank { "按策略分配" }
    }
    private fun groups(text: String) = (ProfileEditor.root(text)["proxy-groups"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
    fun configuredGroups(text: String): List<ClashGroup> = groups(text).mapNotNull { node ->
        val name = node["name"] as? String ?: return@mapNotNull null
        val members = (node["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        ClashGroup(name, node["type"].toString(), node["default-selected"] as? String ?: members.firstOrNull().orEmpty(), members.size)
    }
    fun nodes(text: String, group: String): List<ClashNode> {
        val root = ProfileEditor.root(text)
        val types = listOf("proxies", "proxy-groups").flatMap { key -> (root[key] as? List<*>).orEmpty() }
            .mapNotNull { it as? Map<*, *> }.associate { it["name"].toString() to it["type"].toString() }
        val selected = groups(text).firstOrNull { it["name"] == group } ?: return emptyList()
        return (selected["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty().distinct().map { ClashNode(it, types[it] ?: it) }
    }
    fun select(text: String, group: String, target: String, runtimeValidated: Boolean = false): String {
        val root = ProfileEditor.root(text)
        val source = groups(text).firstOrNull { it["name"] == group } ?: throw IllegalArgumentException("代理组已变化。")
        require(source["type"] == "select") { "此代理组由内核自动选择。" }
        require(target.isNotBlank() && target.length <= 512 && target.none { it.isISOControl() }) { "节点名称无效。" }
        require(runtimeValidated || target in (source["proxies"] as? List<*>).orEmpty()) { "节点不属于此代理组。" }
        root["proxy-groups"] = groups(text).map { if (it["name"] == group) it + ("default-selected" to target) else it }
        val app = (root["x-onetunnel"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
        val sourceUrl = (app["subscription"] as? Map<*, *>)?.get("url") as? String
        val key = sourceUrl?.let { SubscriptionLibrary.id(it) } ?: "file"
        val history = (app["clash-selections"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
        val choices = (history[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        history[key] = choices + (group to target)
        app["clash-selections"] = history; root["x-onetunnel"] = app
        return ProfileEditor.dump(root).also { ProfileCheck.check(it) }
    }
    fun ruleOptions(text: String): Map<String, String> = linkedMapOf<String, String>().apply {
        configuredGroups(text).forEach { put(it.name, "Clash · ${it.name}") }
        ConnectionPolicy.entries(text).forEach { put(it.name, "${if (it.type == "wireguard") "WireGuard" else "Tailscale"} · ${it.name}") }
        put("DIRECT", "直连"); put("REJECT", "阻止")
    }
    fun preserveSelections(current: String, incoming: MutableMap<String, Any?>) {
        val old = groups(current).associateBy { it["name"] }
        incoming["proxy-groups"] = (incoming["proxy-groups"] as? List<*>).orEmpty().map { value ->
            val group = value as? Map<*, *> ?: return@map value
            val selected = old[group["name"]]?.get("default-selected") as? String ?: return@map value
            if (group["type"] == "select" && (selected in (group["proxies"] as? List<*>).orEmpty() ||
                    !(group["use"] as? List<*>).isNullOrEmpty() || group["include-all"] == true || group["include-all-proxies"] == true))
                group + ("default-selected" to selected) else value
        }
    }
    fun restoreSelections(incoming: MutableMap<String, Any?>, app: Map<String, Any?>, url: String?) {
        val key = url?.let { SubscriptionLibrary.id(it) } ?: "file"
        val selected = ((app["clash-selections"] as? Map<*, *>)?.get(key) as? Map<*, *>) ?: return
        val remembered = (incoming["proxy-groups"] as? List<*>).orEmpty().map { value ->
            val group = value as? Map<*, *> ?: return@map value
            val name = selected[group["name"]] as? String ?: return@map value
            if (group["type"] == "select" && (name in (group["proxies"] as? List<*>).orEmpty() ||
                    !(group["use"] as? List<*>).isNullOrEmpty() || group["include-all"] == true || group["include-all-proxies"] == true))
                group + ("default-selected" to name) else value
        }
        incoming["proxy-groups"] = remembered
    }
}
