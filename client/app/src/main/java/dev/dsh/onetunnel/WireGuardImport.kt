// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64

// Deliberately not a data class: toString must never print private keys.
class WireGuardProfile(val outbound: Map<String, Any>, val addresses: List<String>,
    val allowedIPs: List<String>, val dnsServers: List<String>, val warnings: List<String>, val peerCount: Int) {
    val hasDefaultRoute: Boolean get() = allowedIPs.any { it.substringAfter('/') == "0" }
}

/** Offline client-profile conversion. No DNS lookups, scripts, or network access. */
object WireGuardImport {
    const val MAX_BYTES = 64 * 1024
    fun parse(text: String): WireGuardProfile {
        require(text.toByteArray().size <= MAX_BYTES) { "WG 配置不能超过 64 KiB。" }
        val iface = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        var hasInterface = false
        text.removePrefix("\uFEFF").lineSequence().forEach { original ->
            val line = original.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith('[')) {
                when (line.lowercase()) {
                    "[interface]" -> { require(!hasInterface) { "仅支持一个 Interface 区段。" }; hasInterface = true; current = iface }
                    "[peer]" -> { current = linkedMapOf<String, String>().also { peers.add(it) } }
                    else -> throw IllegalArgumentException("不支持的 WG 配置区段。")
                }
            } else {
                val section = current ?: throw IllegalArgumentException("WG 配置需要 Interface / Peer 区段。")
                require('=' in line) { "WG 配置字段格式错误。" }
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').trim()
                val supported = if (section === iface) setOf("privatekey", "address", "dns", "mtu", "listenport", "table", "saveconfig")
                    else setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive")
                require(key in supported) { "配置含不支持的 WG 字段（例如脚本、按 App 设置或扩展协议），未导入。" }
                require(value.isNotEmpty()) { "WG 配置有空字段。" }
                if (key in setOf("address", "dns", "allowedips") && section.containsKey(key)) section[key] = section.getValue(key) + "," + value
                else { require(!section.containsKey(key)) { "WG 配置有重复字段。" }; section[key] = value }
            }
        }
        require(hasInterface && peers.isNotEmpty()) { "需要 Interface 和至少一个 Peer。" }
        val privateKey = key(iface["privatekey"])
        val addresses = csv(iface["address"]).onEach { cidr(it) }
        require(addresses.isNotEmpty()) { "Interface 缺少 Address。" }
        require(addresses.count { ':' !in it } <= 1 && addresses.count { ':' in it } <= 1) {
            "当前核心导入仅支持一个 IPv4 地址和一个 IPv6 地址，请精简 Address 后重试。"
        }
        val dns = csv(iface["dns"])
        val dnsServers = dns.filter { isIP(it) }
        val warnings = mutableListOf<String>()
        if (dns.size != dnsServers.size) warnings.add("DNS 搜索域未导入；内部域名可在高级 DNS 策略中设置。")
        if (iface.keys.any { it in setOf("listenport", "table", "saveconfig") })
            warnings.add("ListenPort、Table、SaveConfig 不接管系统设置；路由由下方导入方式决定。")
        if (dnsServers.isNotEmpty()) warnings.add("DNS 仅供该 WG 出站解析；不会替换全局 DNS。")
        val keepalives = peers.map { peer -> peer["persistentkeepalive"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 0..65535 } ?: throw IllegalArgumentException("PersistentKeepalive 范围为 0–65535。")
        } ?: 0 }.distinct()
        require(keepalives.size == 1) { "当前核心只支持统一的 PersistentKeepalive；多个 Peer 请使用相同值。" }
        val allowed = mutableListOf<String>()
        val convertedPeers = peers.map { peer ->
            val endpoint = endpoint(peer["endpoint"])
            val ips = csv(peer["allowedips"])
            require(ips.isNotEmpty()) { "Peer 缺少 AllowedIPs。" }
            ips.forEach { cidr(it) }
            allowed.addAll(ips)
            linkedMapOf<String, Any>("server" to endpoint.first, "port" to endpoint.second,
                "public-key" to key(peer["publickey"]), "allowed-ips" to ips).apply {
                peer["presharedkey"]?.let { put("pre-shared-key", key(it)) }
            }
        }
        val node = linkedMapOf<String, Any>("type" to "wireguard", "private-key" to privateKey,
            "udp" to true, "peers" to convertedPeers, "persistent-keepalive" to keepalives.single())
        addresses.firstOrNull { ':' !in it }?.let { node["ip"] = it }
        addresses.firstOrNull { ':' in it }?.let { node["ipv6"] = it }
        iface["mtu"]?.let {
            val mtu = it.toIntOrNull()
            require(mtu != null && mtu in 576..9000 && (addresses.none { address -> ':' in address } || mtu >= 1280)) { "MTU 无效，启用 IPv6 时至少为 1280。" }
            node["mtu"] = mtu
        }
        if (dnsServers.isNotEmpty()) { node["dns"] = dnsServers; node["remote-dns-resolve"] = true }
        return WireGuardProfile(node, addresses, allowed.distinct(), dnsServers, warnings, peers.size)
    }
    private fun csv(value: String?): List<String> = value?.split(',')?.map { it.trim() }?.also {
        require(it.all(String::isNotEmpty)) { "WG 列表中有空值。" }
    }.orEmpty()
    private fun key(value: String?): String {
        require(value != null && value.length == 44 && runCatching {
            Base64.getDecoder().decode(value).size == 32
        }.getOrDefault(false)) { "WG 密钥格式无效，需要 Base64 编码的 32 字节密钥。" }
        return value
    }
    private fun isIP(value: String): Boolean = if (':' in value) {
        value.all { it in "0123456789abcdefABCDEF:." } && runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    } else value.split('.').let { parts -> parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 } }
    private fun cidr(value: String) {
        val parts = value.split('/')
        require(parts.size == 2 && isIP(parts[0]) && parts[1].toIntOrNull() in 0..(if (':' in parts[0]) 128 else 32)) { "WG 地址或 AllowedIPs 网段无效。" }
    }
    private fun endpoint(value: String?): Pair<String, Int> {
        require(value != null) { "Peer 缺少 Endpoint；此导入入口用于主动连接的 WG 客户端。" }
        val host: String
        val port: String
        if (value.startsWith('[')) {
            require("]:" in value) { "IPv6 Endpoint 必须使用 [地址]:端口。" }
            host = value.substringAfter('[').substringBefore(']')
            port = value.substringAfter("]:")
            require(':' in host && isIP(host)) { "IPv6 Endpoint 地址无效。" }
        } else {
            require(value.count { it == ':' } == 1) { "Endpoint 必须使用 主机:端口。" }
            host = value.substringBefore(':'); port = value.substringAfter(':')
            require(host.isNotBlank() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) { "Endpoint 主机格式无效。" }
        }
        val number = port.toIntOrNull()
        require(number != null && number in 1..65535) { "Endpoint 端口范围为 1–65535。" }
        return host to number
    }
}
