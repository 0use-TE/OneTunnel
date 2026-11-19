// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

object ConnectionEditing {
    fun subscriptionMeta(text: String, source: SubscriptionSource): String {
        val root = ProfileEditor.root(text)
        val app = (root["x-onetunnel"] as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
        val old = (app["subscription"] as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
        old["name"] = source.name; old["url"] = source.url; old["updated-at"] = source.updatedAt
        app["subscription"] = old; root["x-onetunnel"] = app
        return ProfileEditor.dump(root)
    }
    fun node(text: String, name: String): Map<String, Any?> =
        (ProfileEditor.root(text)["proxies"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .first { it["name"] == name }.entries.associate { it.key.toString() to it.value }

    fun replace(text: String, oldName: String, replacement: Map<String, Any?>): String {
        val name = replacement["name"] as? String ?: error("连接名称不能为空。")
        require(name.isNotBlank() && name.length <= 64 && name.none { it == ',' || it.isISOControl() }) { "连接名称无效。" }
        require(name == oldName || name !in ProfileEditor.view(text).exits) { "连接名称已存在。" }
        val root = ProfileEditor.root(text)
        require(node(text, oldName)["type"] == replacement["type"]) { "不能更改连接类型。" }
        root["proxies"] = (root["proxies"] as List<*>).map { if ((it as? Map<*, *>)?.get("name") == oldName) replacement else it }
        if (name != oldName) {
            fun reference(value: Any?): Any? = when (value) {
                is String -> when {
                    value == "tailscale://$oldName" -> "tailscale://$name"
                    value.endsWith("#$oldName") -> value.removeSuffix(oldName) + name
                    else -> value
                }
                is List<*> -> value.map { reference(it) }
                is Map<*, *> -> value.entries.associate { it.key.toString() to when (it.key) {
                    "dialer-proxy" -> if (it.value == oldName) name else it.value
                    "disabled-outbounds", "node-names" -> (it.value as? List<*>)?.map { item -> if (item == oldName) name else item } ?: it.value
                    else -> reference(it.value)
                } }
                else -> value
            }
            val renamed = reference(root) as Map<*, *>
            renamed.forEach { (key, value) -> root[key.toString()] = value }
            root["proxy-groups"] = (root["proxy-groups"] as? List<*>).orEmpty().map { value ->
                val group = (value as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
                (group["proxies"] as? List<*>)?.let { members -> group["proxies"] = members.map { if (it == oldName) name else it } }
                group
            }
            fun rules(value: Any?): Any? = when(value) {
                is List<*> -> value.map { rules(it) }
                is Map<*, *> -> value.entries.associate { it.key.toString() to rules(it.value) }
                is String -> {
                    val suffix = if (value.endsWith(",no-resolve")) ",no-resolve" else ""
                    val plain = value.removeSuffix(suffix)
                    if (plain.endsWith(",$oldName")) plain.removeSuffix(oldName) + name + suffix else value
                }
                else -> value
            }
            root["rules"] = rules(root["rules"])
            root["sub-rules"]?.let { root["sub-rules"] = rules(it) }
        }
        return ProfileEditor.dump(root)
    }

    fun wireGuard(text: String, oldName: String, name: String, conf: String, mode: String): String {
        val parsed = WireGuardImport.parse(conf)
        require(mode in listOf(ProfileEditor.WG_ROUTES, ProfileEditor.WG_MATCH, ProfileEditor.WG_OUTBOUND_ONLY))
        require(mode != ProfileEditor.WG_ROUTES || !parsed.hasDefaultRoute) { "默认网段请选择“其他流量”或“仅添加出口”。" }
        val old = node(text, oldName)
        val merged = old.toMutableMap()
        listOf("ip", "ipv6", "dns", "remote-dns-resolve", "mtu", "server", "port", "public-key", "pre-shared-key").forEach { merged.remove(it) }
        merged.putAll(parsed.outbound); merged["name"] = name.trim()
        if (old["dns"] == parsed.outbound["dns"] && old["remote-dns-resolve"] is Boolean)
            merged["remote-dns-resolve"] = old["remote-dns-resolve"]
        val peers = (old["peers"] as? List<*>).orEmpty()
        val newPeers = parsed.outbound["peers"] as List<*>
        merged["peers"] = newPeers.mapIndexed { index, value ->
            val original = peers.mapNotNull { it as? Map<*, *> }.firstOrNull { it["public-key"] == (value as Map<*, *>)["public-key"] }
                ?: if (peers.size == newPeers.size) peers.getOrNull(index) as? Map<*, *> else null
            val peer = original?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
            peer.remove("pre-shared-key"); peer.putAll((value as Map<*, *>).entries.associate { it.key.toString() to it.value }); peer
        }
        var result = replace(text, oldName, merged)
        val root = ProfileEditor.root(result)
        root["rules"] = ProfileEditor.view(result).rules.filterNot { it.startsWith("IP-CIDR") && ClashImport.target(it) == name.trim() }
        result = ProfileEditor.dump(root)
        if (mode == ProfileEditor.WG_ROUTES) parsed.allowedIPs.forEach { result = ProfileEditor.addRule(result, if (':' in it) "IP-CIDR6" else "IP-CIDR", it, name.trim()) }
        if (mode == ProfileEditor.WG_MATCH) result = ProfileEditor.routing(result, ProfileEditor.view(result).domestic, name.trim())
        else if (ProfileEditor.view(result).match == name.trim()) result = ProfileEditor.routing(result, ProfileEditor.view(result).domestic, "REJECT")
        ProfileCheck.check(result)
        return result
    }

    fun tailscale(text: String, oldName: String, name: String, hostname: String, control: String, acceptRoutes: Boolean,
        forwards: List<TailscalePortForward>? = null): String {
        TailscaleProfiles.add("", name, hostname, control) // Reuse setup validation without touching existing state.
        val old = node(text, oldName)
        val edited = old.toMutableMap()
        if (forwards != null) TailscalePortForwards.write(edited, forwards)
        edited["name"] = name.trim(); edited["hostname"] = hostname; edited["accept-routes"] = acceptRoutes
        if (control.isBlank()) edited.remove("control-url") else edited["control-url"] = control.trim()
        if (old["control-url"].toString().let { if (it == "null") "" else it } != control.trim()) {
            edited.remove("auth-key")
            edited["state-dir"] = ClashImport.tailscaleDirectory(name.trim() + "@" + control.trim())
        }
        return replace(text, oldName, edited)
    }

    fun matchOptions(text: String): Map<String, String> {
        val root = ProfileEditor.root(text)
        val view = ProfileEditor.view(text)
        val groups = (root["proxy-groups"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
        val normal = (root["proxies"] as? List<*>).orEmpty().mapNotNull { value ->
            val item = value as? Map<*, *> ?: return@mapNotNull null
            if (item["type"] !in listOf("wireguard", "tailscale")) item["name"] as? String else null
        }
        return linkedMapOf<String, String>().apply {
            val clash = view.match.takeIf { it in groups || it in normal } ?: groups.firstOrNull() ?: normal.firstOrNull()
            if (clash != null) put(clash, "Clash")
            ConnectionPolicy.entries(text).forEach { put(it.name, "${if (it.type == "wireguard") "WireGuard" else "Tailscale"} · ${it.name}") }
            put("DIRECT", "直连"); put("REJECT", "阻止")
        }
    }
}
