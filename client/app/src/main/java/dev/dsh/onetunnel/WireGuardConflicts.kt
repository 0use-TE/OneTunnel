// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import java.math.BigInteger
import java.net.InetAddress

object WireGuardConflicts {
    private class Route(val name: String, val cidr: String, val bits: Int, val first: BigInteger, val last: BigInteger)
    /** Check effective IP routing rules, not a peer's cryptographic AllowedIPs alone. */
    fun find(text: String): Set<String> = runCatching {
        val enabled = ConnectionPolicy.entries(text).filter { it.type == "wireguard" && it.enabled }.map { it.name }.toSet()
        val routes = ProfileEditor.view(text).rules.mapNotNull { rule ->
            val p = rule.split(',')
            if (p.size < 3 || p[0] !in listOf("IP-CIDR", "IP-CIDR6") || p[2] !in enabled) return@mapNotNull null
            val parts = p[1].split('/')
            if (parts.size != 2 || !parts[0].all { it in "0123456789abcdefABCDEF:." }) return@mapNotNull null
            val bytes = InetAddress.getByName(parts[0]).address
            val bits = bytes.size * 8
            val prefix = parts[1].toInt()
            require(prefix in 0..bits)
            val shift = bits - prefix
            val first = BigInteger(1, bytes).shiftRight(shift).shiftLeft(shift)
            Route(p[2], p[1], bits, first, first + BigInteger.ONE.shiftLeft(shift) - BigInteger.ONE)
        }
        buildSet {
            for (i in routes.indices) for (j in i + 1 until routes.size) {
                val a = routes[i]; val b = routes[j]
                if (a.name != b.name && a.bits == b.bits && a.first <= b.last && b.first <= a.last)
                    add(listOf("${a.name} · ${a.cidr}", "${b.name} · ${b.cidr}").sorted().joinToString(" ↔ "))
            }
        }
    }.getOrDefault(emptySet())
}
