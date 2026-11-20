// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

data class TunnelPortForward(val port: Int, val localPort: Int = port, val enabled: Boolean = true)

object TunnelPortForwards {
    fun parse(port: String, localPort: String, enabled: Boolean = true): TunnelPortForward {
        val result = TunnelPortForward(
            requireNotNull(port.trim().toIntOrNull()) { "请填写入站端口。" },
            requireNotNull(localPort.trim().toIntOrNull()) { "请填写本机端口。" }, enabled)
        validate(listOf(result))
        return result
    }

    /** Update only the selected node's listeners; leave identity, routes and enabled state intact. */
    fun update(text: String, name: String, originalPort: Int?, replacement: TunnelPortForward?): String {
        val node = ConnectionEditing.node(text, name).toMutableMap()
        require(node["type"] in listOf("wireguard", "tailscale")) { "此连接不支持端口转发。" }
        val ports = read(node).toMutableList()
        if (originalPort == null) {
            ports.add(requireNotNull(replacement) { "请填写转发端口。" })
        } else {
            val index = ports.indexOfFirst { it.port == originalPort }
            require(index >= 0) { "此端口已被修改，请重新打开。" }
            if (replacement == null) ports.removeAt(index) else ports[index] = replacement
        }
        write(node, ports)
        return ConnectionEditing.replace(text, name, node)
    }

    fun setEnabled(text: String, name: String, port: Int, enabled: Boolean): String {
        val original = read(ConnectionEditing.node(text, name)).firstOrNull { it.port == port }
        requireNotNull(original) { "此端口已被修改，请重新打开。" }
        return update(text, name, port, original.copy(enabled = enabled))
    }

    /** Only enabled mappings reach the core, which has no per-port enabled field. */
    fun runtime(node: Map<*, *>): Map<*, *> {
        if (node["type"] !in listOf("wireguard", "tailscale") || "listen-ports" !in node) return node
        val result = node.entries.associateTo(linkedMapOf<String, Any?>()) { it.key.toString() to it.value }
        write(result, read(node).filter { it.enabled })
        return result
    }

    fun read(node: Map<*, *>): List<TunnelPortForward> {
        val value = node["listen-ports"] ?: return emptyList()
        require(value is List<*>) { "入站端口必须为列表。" }
        fun port(value: Any?): Int {
            require(value is Int || value is Long) { "端口需为 1–65535 的整数。" }
            val number = (value as Number).toLong()
            require(number in 1..65535) { "端口范围为 1–65535。" }
            return number.toInt()
        }
        return value.map { item ->
            require(item is Map<*, *>) { "每个入站端口需要端口配置。" }
            require("enabled" !in item || item["enabled"] is Boolean) { "端口开关需为布尔值。" }
            val source = port(item["port"])
            TunnelPortForward(source, if (item.containsKey("local-port")) port(item["local-port"]) else source, item["enabled"] != false)
        }.also(::validate)
    }
    fun validate(ports: List<TunnelPortForward>) {
        require(ports.size <= 16) { "最多添加 16 个转发端口。" }
        require(ports.all { it.port in 1..65535 && it.localPort in 1..65535 }) { "端口范围为 1–65535。" }
        require(ports.map { it.port }.distinct().size == ports.size) { "入站端口不能重复。" }
    }
    fun write(node: MutableMap<String, Any?>, ports: List<TunnelPortForward>) {
        validate(ports)
        if (ports.isEmpty()) node.remove("listen-ports") else node["listen-ports"] = ports.map {
            linkedMapOf<String, Any>("port" to it.port, "local-port" to it.localPort).apply {
                if (!it.enabled) put("enabled", false)
            }
        }
    }
}
