// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class LanProxy(val enabled: Boolean = false, val port: Int = 7890,
    val user: String = "onetunnel", val password: String = "", val httpEnabled: Boolean = enabled,
    val authRequired: Boolean = true)
data class ProfileView(val exits: List<String>, val match: String, val domestic: Boolean,
    val rules: List<String>, val lan: LanProxy)
data class RoutingRule(val type: String, val target: String, val exit: String, val noResolve: Boolean = false)

/** All edits use the same encrypted profile as the VPN service. No parallel settings copy. */
object ProfileEditor {
    private val domesticRules = listOf("GEOSITE,cn,DIRECT", "GEOIP,CN,DIRECT,no-resolve")
    val ruleTypes = listOf("DOMAIN-SUFFIX", "DOMAIN", "DOMAIN-KEYWORD", "IP-CIDR", "IP-CIDR6", "GEOSITE", "GEOIP")
    fun editableRule(rule: String): RoutingRule? {
        val parts = rule.split(',')
        if (parts.size == 2 && parts[0] == "MATCH") return RoutingRule("MATCH", "", parts[1])
        if (parts.size !in 3..4 || parts[0] !in ruleTypes) return null
        if (parts.size == 4 && (parts[3] != "no-resolve" || parts[0] !in listOf("IP-CIDR", "IP-CIDR6", "GEOIP"))) return null
        return RoutingRule(parts[0], parts[1], parts[2], parts.size == 4)
    }
    const val WG_OUTBOUND_ONLY = "outbound"
    const val WG_ROUTES = "routes"
    const val WG_MATCH = "match"
    fun importWireGuard(text: String, wg: WireGuardProfile, name: String, mode: String): String {
        val label = name.trim()
        require(label.isNotBlank() && label.length <= 64 && label.none { it == ',' || it.isISOControl() }) { "名称需为 1–64 个字符，不能包含逗号或控制字符。" }
        require(mode in listOf(WG_OUTBOUND_ONLY, WG_ROUTES, WG_MATCH)) { "请选择导入方式。" }
        val base = if (text.isBlank()) defaultProfile() else text
        require(label !in view(base).exits) { "出口名称已存在，请换一个名称。" }
        require(mode != WG_ROUTES || !wg.hasDefaultRoute) { "AllowedIPs 含默认路由，请选择设为 MATCH 或仅添加出口。" }
        val root = root(base)
        val nodes = (root["proxies"] as? List<*>)?.toMutableList() ?: mutableListOf()
        nodes.add(wg.outbound + ("name" to label))
        root["proxies"] = nodes
        var result = dump(root)
        if (mode == WG_ROUTES) wg.allowedIPs.forEach { prefix ->
            result = addRule(result, if (':' in prefix) "IP-CIDR6" else "IP-CIDR", prefix, label)
        }
        if (mode == WG_MATCH) result = routing(result, view(result).domestic, label)
        require(result.toByteArray().size <= ConfigStore.MAX_BYTES) { "导入后配置超过 1 MiB。" }
        return result
    }
    internal fun defaultProfile(): String = """
        mode: rule
        ipv6: true
        dns:
          enable: true
          ipv6: true
          enhanced-mode: redir-host
          default-nameserver: [223.5.5.5, 1.1.1.1]
          nameserver: [https://dns.alidns.com/dns-query]
        proxies: []
        rules:
          - GEOSITE,cn,DIRECT
          - GEOIP,CN,DIRECT,no-resolve
          - MATCH,REJECT
    """.trimIndent()
    private fun yaml() = Yaml(SafeConstructor(LoaderOptions().apply {
        codePointLimit = ConfigStore.MAX_BYTES
        maxAliasesForCollections = 20
        nestingDepthLimit = 50
        isAllowDuplicateKeys = false
    }))
    internal fun root(text: String): MutableMap<String, Any?> {
        require(text.toByteArray().size <= ConfigStore.MAX_BYTES) { "配置不能超过 1 MiB。" }
        val map = try { yaml().load<Any>(text) as? Map<*, *> }
            catch (_: Exception) { throw IllegalArgumentException("YAML 格式错误，请检查高级配置。") }
        require(map != null) { "请先导入配置。" }
        return map.entries.associateTo(linkedMapOf()) { it.key.toString() to it.value }
    }
    internal fun dump(root: Map<String, Any?>): String = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    }).dump(root)
    fun view(text: String): ProfileView {
        val root = root(text)
        val exits = listOf("proxy-groups", "proxies").flatMap { key ->
            (root[key] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.orEmpty()
        }.plus(listOf("DIRECT", "REJECT", "GLOBAL", "REJECT-DROP", "COMPATIBLE", "PASS", "PASS-RULE")).distinct()
        val rules = (root["rules"] as? List<*>)?.map { it.toString() }.orEmpty()
        val settings = root["x-onetunnel"] as? Map<*, *>
        return ProfileView(exits, rules.lastOrNull()?.takeIf { it.startsWith("MATCH,") }
            ?.substringAfter("MATCH,") ?: "DIRECT", domesticRules.all { it in rules }, rules,
            LanProxy(settings?.get("lan-enabled") == true,
                (settings?.get("lan-port") as? Number)?.toInt() ?: 7890,
                settings?.get("lan-user") as? String ?: "onetunnel",
                settings?.get("lan-password") as? String ?: "",
                settings?.get("http-enabled") as? Boolean ?: (settings?.get("lan-enabled") == true),
                settings?.get("lan-auth-enabled") as? Boolean ?: true))
    }
    fun routing(text: String, domestic: Boolean, match: String): String {
        val view = view(text)
        require(match in view.exits && ',' !in match && '\n' !in match) { "请选择有效出口。" }
        val root = root(text)
        val rules = view.rules.filterNot { it.startsWith("MATCH,") || it in domesticRules }.toMutableList()
        if (domestic) rules.addAll(domesticRules)
        rules.add("MATCH,$match")
        root["rules"] = rules
        return dump(root)
    }
    private fun ruleText(text: String, type: String, target: String, exit: String, noResolve: Boolean): String {
        val value = target.trim()
        require(type in ruleTypes) { "不支持的规则类型。" }
        require(value.isNotBlank() && value.none { it.isWhitespace() || it == ',' || it == '#' }) { "目标不能为空，且不能包含空白、逗号或 #。" }
        if (type == "IP-CIDR") {
            val parts = value.split('/')
            val octets = parts.first().split('.')
            require(parts.size == 2 && parts[1].toIntOrNull() in 0..32 && octets.size == 4 &&
                octets.all { it.toIntOrNull() in 0..255 }) { "请输入 IPv4 网段，例如 10.77.0.0/24。" }
        } else if (type == "IP-CIDR6") {
            val parts = value.split('/')
            require(parts.size == 2 && parts[1].toIntOrNull() in 0..128 && ':' in parts[0] &&
                parts[0].all { it in "0123456789abcdefABCDEF:" } && runCatching {
                    java.net.InetAddress.getByName(parts[0]) is java.net.Inet6Address
                }.getOrDefault(false)) { "请输入 IPv6 网段，例如 fd7a:115c:a1e0::/48。" }
        }
        val view = view(text)
        require(exit in view.exits && exit.none { it == ',' || it == '\n' }) { "请选择有效出口。" }
        require(!noResolve || type in listOf("IP-CIDR", "IP-CIDR6", "GEOIP")) { "此规则类型不支持 no-resolve。" }
        return "$type,$value,$exit" + if (noResolve) ",no-resolve" else ""
    }
    fun addRule(text: String, type: String, target: String, exit: String): String {
        val rule = ruleText(text, type, target, exit, type.startsWith("IP-CIDR"))
        val root = root(text)
        val rules = view(text).rules.toMutableList()
        val before = rules.indexOfFirst { it in domesticRules || it.startsWith("MATCH,") }
        rules.add(if (before < 0) rules.size else before, rule)
        root["rules"] = rules
        return dump(root)
    }
    fun replaceRule(text: String, index: Int, expected: String, next: RoutingRule): String {
        val root = root(text)
        val view = view(text)
        val rules = view.rules.toMutableList()
        require(index in rules.indices && rules[index] == expected) { "规则已变化，请重新打开编辑。" }
        require(editableRule(expected) != null) { "此规则请使用高级规则编辑。" }
        if (expected.startsWith("MATCH,")) {
            require(next.type == "MATCH" && next.target.isEmpty() && !next.noResolve && next.exit in view.exits && next.exit.none { it == ',' || it == '\n' }) { "请选择有效的其他流量出口。" }
            rules[index] = "MATCH,${next.exit}"
        } else {
            rules[index] = ruleText(text, next.type, next.target, next.exit, next.noResolve)
        }
        root["rules"] = rules
        return dump(root)
    }
    fun reorderRules(text: String, expected: List<String>, order: List<Int>): String {
        val root = root(text)
        val rules = view(text).rules
        require(rules == expected) { "规则已变化，请重新打开排序。" }
        require(order.size == rules.size && order.toSet() == rules.indices.toSet()) { "规则排序无效。" }
        require(rules.lastOrNull()?.startsWith("MATCH,") == true && order.last() == rules.lastIndex) { "MATCH 必须保留在最后。" }
        root["rules"] = order.map { rules[it] }
        return dump(root)
    }
    fun removeRule(text: String, index: Int): String {
        val root = root(text)
        val rules = view(text).rules.toMutableList()
        require(index in rules.indices && !rules[index].startsWith("MATCH,")) { "MATCH 请通过出口选择器修改。" }
        rules.removeAt(index)
        root["rules"] = rules
        return dump(root)
    }
    internal fun validateLan(lan: LanProxy) {
        require(lan.port in 1024..65535) { "端口范围为 1024–65535。" }
        if (lan.httpEnabled && lan.enabled && lan.authRequired) {
            require(lan.user.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "用户名限 1–32 位字母、数字、下划线或短横线。" }
            require(lan.password.length in 12..128 && lan.password.all { it in '!'..'~' && it != ':' }) {
                "密码需为 12–128 位可见 ASCII 字符，不能包含冒号。"
            }
        }
    }
    fun lan(text: String, lan: LanProxy): String {
        validateLan(lan)
        val root = root(text)
        val app = (root["x-onetunnel"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
        app.putAll(mapOf("http-enabled" to lan.httpEnabled, "lan-enabled" to lan.enabled, "lan-port" to lan.port, "lan-auth-enabled" to lan.authRequired,
            "lan-user" to lan.user, "lan-password" to lan.password))
        root["x-onetunnel"] = app
        return dump(root)
    }
}
