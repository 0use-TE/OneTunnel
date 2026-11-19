// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

data class TunnelPortForward(val port: Int, val localPort: Int = port)

object TunnelPortForwards {
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
            val source = port(item["port"])
            TunnelPortForward(source, if (item.containsKey("local-port")) port(item["local-port"]) else source)
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
            linkedMapOf("port" to it.port, "local-port" to it.localPort)
        }
    }
}
