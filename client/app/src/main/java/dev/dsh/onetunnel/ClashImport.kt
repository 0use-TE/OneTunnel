// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import java.security.MessageDigest

// No generated toString: both downloaded YAML and subscription URLs contain credentials.
class SubscriptionSource(val name: String, val url: String, val updatedAt: Long)
class ClashPreview(val yaml: String, val nodes: Int, val groups: Int, val rules: Int,
    val warnings: List<String>, val source: SubscriptionSource? = null, val refresh: Boolean = false)

object ClashImport {
    fun subscription(text: String): SubscriptionSource? = runCatching {
        val settings = ProfileEditor.root(text)["x-onetunnel"] as? Map<*, *>
        val source = settings?.get("subscription") as? Map<*, *> ?: return null
        SubscriptionSource(source["name"] as? String ?: "订阅", source["url"] as? String ?: return null,
            (source["updated-at"] as? Number)?.toLong() ?: 0L)
    }.getOrNull()
    fun prepare(text: String, source: SubscriptionSource? = null, refresh: Boolean = false): ClashPreview {
        val root = ProfileEditor.root(text.removePrefix("\uFEFF"))
        val warnings = mutableListOf<String>()
        val nodes = (root["proxies"] as? List<*>)?.map {
            require(it is Map<*, *>) { "节点格式错误，请使用 Clash / Mihomo YAML 订阅。" }
            it.entries.associateTo(linkedMapOf<String, Any?>()) { entry -> entry.key.toString() to entry.value }
        }.orEmpty()
        val providers = root["proxy-providers"] as? Map<*, *>
        require(nodes.isNotEmpty() || !providers.isNullOrEmpty()) {
            "没有找到代理节点或 proxy-providers。请使用完整 Clash / Mihomo YAML；不支持 Verge 合并片段、JS 脚本或 Base64 节点列表。"
        }
        require(root["script"] == null) { "配置包含脚本，请从电脑导出脚本处理后的完整 YAML。" }
        root.remove("x-onetunnel") // Remote documents cannot enable LAN sharing or inject app state.
        val desktop = listOf("port", "socks-port", "mixed-port", "redir-port", "tproxy-port", "allow-lan", "bind-address",
            "secret", "tun", "listeners", "tunnels", "tuic-server", "iptables", "ntp", "interface-name", "routing-mark", "profile")
        val ignored = root.keys.filter { it in desktop || it.startsWith("external-") || it.startsWith("cfw-") }
        ignored.forEach { root.remove(it) }
        if (ignored.isNotEmpty()) warnings.add("桌面端口、TUN、控制接口及桌面偏好由安卓接管，不会导入启用。")
        root["mode"] = "rule"; root["find-process-mode"] = "off"; root["geo-auto-update"] = false
        val dns: MutableMap<String, Any?> = (root["dns"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
            ?: linkedMapOf("nameserver" to listOf("https://dns.alidns.com/dns-query"), "default-nameserver" to listOf("223.5.5.5", "1.1.1.1"), "enhanced-mode" to "redir-host")
        if (dns["enable"] != true) warnings.add("已启用 TUN 所需 DNS；缺少 DNS 时补充默认解析器。")
        dns["enable"] = true; dns.remove("listen"); root["dns"] = dns
        listOf("proxy-providers", "rule-providers").forEach { key ->
            (root[key] as? Map<*, *>)?.let { definitions ->
                root[key] = definitions.entries.associate { (name, value) ->
                    require(value is Map<*, *>) { "Provider 格式无效。" }
                    val item = value.entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
                    require(item["type"] != "file") { "配置引用电脑本地 Provider 文件，请导出包含节点 / 规则的完整配置或改成 HTTP Provider。" }
                    if (item["type"] == "http") {
                        val hash = MessageDigest.getInstance("SHA-256").digest(name.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                        val format = if (key == "proxy-providers") "yaml" else item["format"]?.toString()?.takeIf { it in listOf("yaml", "text", "mrs") } ?: "yaml"
                        item["path"] = "providers/${if (key == "proxy-providers") "proxy" else "rule"}-$hash.$format"
                    }
                    name.toString() to item
                }
            }
        }
        nodes.forEach { node -> if (node["type"] == "tailscale") node["state-dir"] = tailscaleDirectory(node["name"].toString()) }
        root["proxies"] = nodes
        val groups = (root["proxy-groups"] as? List<*>)?.toMutableList() ?: mutableListOf()
        if (groups.isEmpty()) {
            val nodeNames = nodes.mapNotNull { it["name"] as? String }
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) "代理" else "代理-$it" }.first { it !in nodeNames }
            groups.add(linkedMapOf<String, Any>("name" to name, "type" to "select").apply {
                if (nodeNames.isNotEmpty()) put("proxies", nodeNames)
                if (!providers.isNullOrEmpty()) put("use", providers.keys.map { it.toString() })
            })
        }
        root["proxy-groups"] = groups
        val allRules = (root["rules"] as? List<*>)?.map {
            require(it is String) { "规则必须是文本。" }; it.trim().replaceFirst(Regex("^FINAL,"), "MATCH,")
        }.orEmpty()
        val processCount = allRules.count { it.startsWith("PROCESS-", ignoreCase = true) }
        if (processCount > 0) warnings.add("将移除 $processCount 条桌面进程规则；安卓按 App 分流尚未接入。")
        val rules = allRules.filterNot { it.startsWith("PROCESS-", ignoreCase = true) }.toMutableList()
        if (rules.isEmpty()) rules.addAll(listOf("GEOSITE,cn,DIRECT", "GEOIP,CN,DIRECT,no-resolve"))
        if (rules.none { it.startsWith("MATCH,") }) {
            val fallback = (groups.firstOrNull() as? Map<*, *>)?.get("name") as? String ?: "REJECT"
            rules.add("MATCH,$fallback")
            warnings.add("已补充末尾 MATCH 出口。")
        }
        require(rules.count { it.startsWith("MATCH,") } == 1 && rules.last().startsWith("MATCH,")) { "请保留唯一、位于最后的 MATCH 规则。" }
        root["rules"] = rules
        val normalized = ProfileEditor.dump(root)
        ProfileCheck.check(normalized)
        return ClashPreview(normalized, nodes.size, groups.size, rules.size, warnings, source, refresh)
    }
    fun apply(current: String, incoming: ClashPreview, keepRules: Boolean): String {
        val remote = ProfileEditor.root(incoming.yaml)
        val existing = if (current.isBlank()) linkedMapOf() else ProfileEditor.root(current)
        val app = (existing["x-onetunnel"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
        val oldManaged = ((app["subscription"] as? Map<*, *>)?.get("node-names") as? List<*>)?.toSet().orEmpty()
        if (incoming.source != null && (app["subscription"] as? Map<*, *>)?.get("url") == incoming.source.url)
            ClashProfiles.preserveSelections(current, remote)
        ClashProfiles.restoreSelections(remote, app, incoming.source?.url)
        val local = (existing["proxies"] as? List<*>)?.filter { value ->
            val item = value as? Map<*, *>
            item?.get("type") in listOf("wireguard", "tailscale") && item?.get("name") !in oldManaged
        }.orEmpty()
        val localNames = local.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.toSet()
        val remoteNodes = (remote["proxies"] as? List<*>).orEmpty()
        val remoteNames = remoteNodes.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
        val remoteGroupNames = (remote["proxy-groups"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.orEmpty()
        require(localNames.none { it in remoteNames || it in remoteGroupNames }) { "订阅与本机 WG / Tailscale 名称冲突，请先修改连接名称。" }
        remote["proxies"] = remoteNodes + local
        val currentRules = (existing["rules"] as? List<*>)?.map { it.toString() }.orEmpty()
        if (keepRules && currentRules.isNotEmpty()) remote["rules"] = currentRules
        else {
            val localRules = currentRules.filter { !it.startsWith("MATCH,") && target(it) in localNames }
            remote["rules"] = localRules + (remote["rules"] as? List<*>).orEmpty().filterNot { it in localRules }
        }
        val oldPolicy = (existing["dns"] as? Map<*, *>)?.get("nameserver-policy") as? Map<*, *>
        if (oldPolicy != null) {
            val dns = (remote["dns"] as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
            val policy = (dns["nameserver-policy"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
            oldPolicy.forEach { (domain, value) ->
                val values = if (value is List<*>) value.map { it.toString() } else listOf(value.toString())
                if (keepRules || values.any { server -> localNames.any { server == "tailscale://$it" || server.endsWith("#$it") } }) policy[domain.toString()] = value
            }
            if (policy.isNotEmpty()) dns["nameserver-policy"] = policy
            remote["dns"] = dns
        }
        incoming.source?.let { source ->
            val same = (app["subscription"] as? Map<*, *>)?.get("url") == source.url
            if (!same) app["subscription-enabled"] = true
            app["subscription"] = mapOf("name" to source.name, "url" to source.url,
                "updated-at" to source.updatedAt, "node-names" to remoteNames)
        } ?: run { app.remove("subscription"); app["subscription-enabled"] = true }
        remote["x-onetunnel"] = app
        val result = ProfileEditor.dump(remote)
        val exits = ProfileEditor.view(result).exits.toSet() + setOf("GLOBAL", "PASS", "COMPATIBLE", "REJECT-DROP")
        val missing = (remote["rules"] as List<*>).count { val rule = it.toString(); !rule.startsWith("SUB-RULE,") && target(rule) !in exits }
        require(missing == 0) { "有 $missing 条规则指向不存在的出口，请改用订阅规则，或在高级配置中调整规则。" }
        ProfileCheck.check(result)
        return result
    }
    internal fun target(rule: String): String = rule.split(',').let { parts -> if (parts.last() == "no-resolve") parts.getOrElse(parts.size - 2) { "" } else parts.last() }.trim()
    internal fun tailscaleDirectory(name: String): String = "tailscale-" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
