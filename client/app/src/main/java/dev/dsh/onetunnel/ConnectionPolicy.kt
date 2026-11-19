// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

data class ConnectionEntry(val name: String, val type: String, val enabled: Boolean)

/** Persist original credentials and rules; derive blocked placeholders only for the running core. */
object ConnectionPolicy {
    private fun settings(text: String) = ProfileEditor.root(text)["x-onetunnel"] as? Map<*, *>
    fun subscriptionEnabled(text: String): Boolean = runCatching { settings(text)?.get("subscription-enabled") != false }.getOrDefault(true)
    fun entries(text: String): List<ConnectionEntry> = runCatching {
        val disabled = (settings(text)?.get("disabled-outbounds") as? List<*>).orEmpty().toSet()
        (ProfileEditor.root(text)["proxies"] as? List<*>).orEmpty().mapNotNull {
            val node = it as? Map<*, *> ?: return@mapNotNull null
            val name = node["name"] as? String ?: return@mapNotNull null
            val type = node["type"] as? String ?: return@mapNotNull null
            if (type !in listOf("wireguard", "tailscale")) return@mapNotNull null
            ConnectionEntry(name, type, name !in disabled)
        }
    }.getOrDefault(emptyList())
    fun setEnabled(text: String, name: String, enabled: Boolean): String {
        require(entries(text).any { it.name == name }) { "连接不存在。" }
        return edit(text) { app ->
            val names = (app["disabled-outbounds"] as? List<*>).orEmpty().map { it.toString() }.toMutableSet()
            if (enabled) names.remove(name) else names.add(name)
            app["disabled-outbounds"] = names.toList()
        }
    }
    fun setSubscription(text: String, enabled: Boolean) = edit(text) { it["subscription-enabled"] = enabled }
    private fun edit(text: String, block: (MutableMap<String, Any?>) -> Unit): String {
        val root = ProfileEditor.root(text)
        val app = (root["x-onetunnel"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
        block(app); root["x-onetunnel"] = app
        return ProfileEditor.dump(root)
    }
    fun runtime(text: String): String {
        val root = ProfileEditor.root(text)
        val blocked = entries(text).filterNot { it.enabled }.map { it.name }.toMutableSet()
        val nodes = (root["proxies"] as? List<*>).orEmpty()
        val groups = (root["proxy-groups"] as? List<*>).orEmpty()
        if (!subscriptionEnabled(text)) {
            val managed = ((settings(text)?.get("subscription") as? Map<*, *>)?.get("node-names") as? List<*>).orEmpty().toSet()
            nodes.forEach { value -> (value as? Map<*, *>)?.let {
                if (it["type"] !in listOf("wireguard", "tailscale") || it["name"] in managed) blocked.add(it["name"].toString())
            } }
            groups.forEach { (it as? Map<*, *>)?.get("name")?.let { name -> blocked.add(name.toString()) } }
            root.remove("proxy-providers")
        }
        root["proxies"] = nodes.filterNot { (it as? Map<*, *>)?.get("name") in blocked }
        root["proxy-groups"] = groups.filterNot { (it as? Map<*, *>)?.get("name") in blocked } +
            blocked.map { mapOf("name" to it, "type" to "select", "proxies" to listOf("REJECT")) }
        val dns = (root["dns"] as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
        val policy = dns["nameserver-policy"] as? Map<*, *>
        if (policy != null) dns["nameserver-policy"] = policy.entries.associate { (domain, value) ->
            fun resolve(server: Any?): Any? = if (blocked.any { server == "tailscale://$it" || server.toString().endsWith("#$it") }) "rcode://refused" else server
            domain.toString() to if (value is List<*>) value.map { resolve(it) } else resolve(value)
        }
        root["dns"] = dns
        return ProfileEditor.dump(root)
    }
}
