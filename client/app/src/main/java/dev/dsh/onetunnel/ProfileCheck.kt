// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class ProfileSummary(val nodes: Int, val rules: Int, val wireguard: Int, val tailscale: Int)

object ProfileCheck {
    // Syntax/shape checks only. Never fetch providers, resolve domains, or start native code.
    fun check(text: String): ProfileSummary {
        require(text.isNotBlank()) { "请先导入或填写 Mihomo YAML 配置。" }
        require(text.toByteArray().size <= ConfigStore.MAX_BYTES) { "配置不能超过 1 MiB。" }
        require(!text.contains("REPLACE_")) { "示例中的 REPLACE_ 占位符尚未替换，不能启动。" }
        val options = LoaderOptions().apply {
            codePointLimit = ConfigStore.MAX_BYTES
            maxAliasesForCollections = 20
            nestingDepthLimit = 50
            isAllowDuplicateKeys = false
        }
        val root = try { Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *> }
        catch (_: Exception) { throw IllegalArgumentException("YAML 格式错误：请检查缩进、引号、重复字段或不支持的标签；不回显内容以保护密钥。") }
        require(root != null) { "YAML 顶层必须是配置对象。" }
        val dns = root["dns"] as? Map<*, *>
        require(dns?.get("enable") == true) { "第一版要求 dns.enable: true，DNS 由统一 TUN 接管。" }
        val rules = root["rules"] as? List<*>
        require(!rules.isNullOrEmpty() && rules.last().toString().trim().startsWith("MATCH,")) {
            "请提供 rules，并以明确的 MATCH,出口名 作为最后一条规则；程序不会自动添加直连回退。"
        }
        require(rules.all { it is String } && rules.dropLast(1).none { it.toString().trim().startsWith("MATCH,") }) {
            "规则必须是文本，且只允许末尾一条 MATCH。"
        }
        require(rules.none { it.toString().startsWith("PROCESS-", ignoreCase = true) }) {
            "第一版尚未接入按进程/包名查询，请暂时移除 PROCESS-* 规则。"
        }
        val proxies = (root["proxies"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        proxies.filter { it["type"] in listOf("tailscale", "wireguard") }.forEach {
            TunnelPortForwards.read(it); TunnelPortForwards.allEnabled(it)
        }
        val view = ProfileEditor.view(text)
        require(view.match in view.exits) { "MATCH 指向不存在的出口，请在规则页重新选择。" }
        ProfileEditor.lan(text, view.lan) // Validate explicit LAN settings before saving/starting.
        return ProfileSummary(proxies.size, rules.size,
            proxies.count { it["type"] == "wireguard" }, proxies.count { it["type"] == "tailscale" })
    }
}
