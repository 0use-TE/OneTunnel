// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

object ConnectionRemoval {
    fun node(text: String, name: String): String {
        ConnectionEditing.node(text, name) // Reject stale editor requests.
        val root = ProfileEditor.root(text)
        root["proxies"] = (root["proxies"] as List<*>).filterNot { (it as? Map<*, *>)?.get("name") == name }.map { value ->
            val item = (value as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
            if (item["dialer-proxy"] == name) item["dialer-proxy"] = "REJECT"
            item
        }
        root["proxy-groups"] = (root["proxy-groups"] as? List<*>).orEmpty().map { value ->
            val group = (value as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
            (group["proxies"] as? List<*>)?.let { members -> group["proxies"] = members.map { if (it == name) "REJECT" else it }.distinct() }
            group
        }
        fun rules(value: Any?): Any? = when (value) {
            is List<*> -> value.map { rules(it) }
            is Map<*, *> -> value.entries.associate { it.key.toString() to rules(it.value) }
            is String -> {
                val suffix = if (value.endsWith(",no-resolve")) ",no-resolve" else ""
                val plain = value.removeSuffix(suffix)
                if (plain.endsWith(",$name")) plain.removeSuffix(name) + "REJECT" + suffix else value
            }
            else -> value
        }
        root["rules"] = rules(root["rules"])
        root["sub-rules"]?.let { root["sub-rules"] = rules(it) }
        fun dns(value: Any?): Any? = when (value) {
            is String -> if (value == "tailscale://$name" || value.endsWith("#$name")) "rcode://refused" else value
            is List<*> -> value.map { dns(it) }
            is Map<*, *> -> value.entries.associate { it.key.toString() to dns(it.value) }
            else -> value
        }
        root["dns"] = dns(root["dns"])
        val app = settings(root)
        app["disabled-outbounds"] = (app["disabled-outbounds"] as? List<*>).orEmpty().filterNot { it == name }
        (app["subscription"] as? Map<*, *>)?.let { source ->
            app["subscription"] = source.entries.associate { it.key.toString() to if (it.key == "node-names") (it.value as? List<*>).orEmpty().filterNot { node -> node == name } else it.value }
        }
        root["x-onetunnel"] = app
        return ProfileEditor.dump(root).also { ProfileCheck.check(it); ProfileCheck.check(ConnectionPolicy.runtime(it)) }
    }

    fun subscription(text: String): String {
        val old = ProfileEditor.root(text)
        val app = settings(old)
        val owned = ((app["subscription"] as? Map<*, *>)?.get("node-names") as? List<*>).orEmpty().toSet()
        val local = (old["proxies"] as? List<*>).orEmpty().filter { value ->
            val node = value as? Map<*, *>
            node?.get("type") in listOf("wireguard", "tailscale") && node?.get("name") !in owned
        }
        val names = local.map { (it as Map<*, *>)["name"] }.toSet()
        val root = ProfileEditor.root(ProfileEditor.defaultProfile())
        root["proxies"] = local.map { value ->
            (value as Map<*, *>).entries.associate { it.key.toString() to if (it.key == "dialer-proxy" && it.value !in names && it.value != "DIRECT") "REJECT" else it.value }
        }
        val view = ProfileEditor.view(text)
        val rules = view.rules.filter { !it.startsWith("MATCH,") && it.substringBefore(',') in setOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "IP-CIDR", "IP-CIDR6") && ClashImport.target(it) in names }
        root["rules"] = rules + (if (view.domestic) listOf("GEOSITE,cn,DIRECT", "GEOIP,CN,DIRECT,no-resolve") else emptyList()) + "MATCH,${view.match.takeIf { it in names } ?: "REJECT"}"
        val oldPolicy = (old["dns"] as? Map<*, *>)?.get("nameserver-policy") as? Map<*, *>
        val policy = oldPolicy?.entries?.filter { (_, value) ->
            val servers = if (value is List<*>) value else listOf(value)
            servers.isNotEmpty() && servers.all { server -> names.any { server == "tailscale://$it" } }
        }?.associate { it.key.toString() to it.value }.orEmpty()
        root["dns"] = (root["dns"] as Map<*, *>).entries.associate { it.key.toString() to it.value } + ("nameserver-policy" to policy)
        app.remove("subscription"); app.remove("subscription-enabled")
        app["disabled-outbounds"] = (app["disabled-outbounds"] as? List<*>).orEmpty().filter { it in names }
        root["x-onetunnel"] = app
        return ProfileEditor.dump(root).also { ProfileCheck.check(it); ProfileCheck.check(ConnectionPolicy.runtime(it)) }
    }

    private fun settings(root: Map<String, Any?>) = (root["x-onetunnel"] as? Map<*, *>)?.entries
        ?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
}
