// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import java.net.URI

data class TailscaleEntry(val name: String, val hostname: String, val controlUrl: String)

object TailscaleProfiles {
    fun list(text: String): List<TailscaleEntry> = runCatching {
        (ProfileEditor.root(text)["proxies"] as? List<*>)?.mapNotNull { value ->
            val node = value as? Map<*, *> ?: return@mapNotNull null
            if (node["type"] != "tailscale") return@mapNotNull null
            TailscaleEntry(node["name"] as? String ?: return@mapNotNull null,
                node["hostname"] as? String ?: "", node["control-url"] as? String ?: "")
        }.orEmpty()
    }.getOrDefault(emptyList())
    fun add(text: String, name: String, hostname: String, controlUrl: String): String {
        val label = name.trim()
        require(label.isNotBlank() && label.length <= 64 && label.none { it == ',' || it.isISOControl() }) { "连接名称需为 1–64 个字符，不能包含逗号或控制字符。" }
        require(hostname.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"))) { "设备名需为 1–63 位字母、数字或短横线。" }
        val control = controlUrl.trim()
        if (control.isNotBlank()) {
            SubscriptionClient.validate(control)
            require(URI(control).scheme.equals("https", true)) { "Headscale 地址需要 HTTPS。" }
        }
        val base = if (text.isBlank()) ProfileEditor.defaultProfile() else text
        require(label !in ProfileEditor.view(base).exits) { "连接名称已存在。" }
        val root = ProfileEditor.root(base)
        val node = linkedMapOf<String, Any>("name" to label, "type" to "tailscale", "hostname" to hostname,
            "state-dir" to ClashImport.tailscaleDirectory(label), "accept-routes" to true, "udp" to true)
        if (control.isNotBlank()) node["control-url"] = control
        root["proxies"] = (root["proxies"] as? List<*>).orEmpty() + listOf(node)
        val dns = (root["dns"] as Map<*, *>).entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
        val policy = (dns["nameserver-policy"] as? Map<*, *>)?.entries?.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value } ?: linkedMapOf()
        // Do not steal an existing tailnet's MagicDNS policy when adding another identity.
        if (!policy.containsKey("+.ts.net")) policy["+.ts.net"] = "tailscale://$label"
        dns["nameserver-policy"] = policy; root["dns"] = dns
        var result = ProfileEditor.dump(root)
        result = ProfileEditor.addRule(result, "IP-CIDR", "100.64.0.0/10", label)
        result = ProfileEditor.addRule(result, "IP-CIDR6", "fd7a:115c:a1e0::/48", label)
        result = ProfileEditor.addRule(result, "DOMAIN-SUFFIX", "ts.net", label)
        ProfileCheck.check(result)
        return result
    }
    fun loginUrl(value: String, controlUrl: String): String {
        val uri = runCatching { URI(value) }.getOrNull()
        val customHost = runCatching { URI(controlUrl).host }.getOrNull()
        val trusted = if (customHost.isNullOrBlank()) setOf("login.tailscale.com", "controlplane.tailscale.com") else setOf(customHost.lowercase())
        require(value.length in 1..4096 && uri != null && uri.scheme.equals("https", true) && uri.userInfo == null && uri.host?.lowercase() in trusted) {
            "登录地址与配置的 Tailscale / Headscale 服务不符，未打开浏览器。"
        }
        return value
    }
}
