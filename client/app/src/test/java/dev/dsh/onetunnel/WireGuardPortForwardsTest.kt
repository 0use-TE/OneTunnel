package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class WireGuardPortForwardsTest {
    private val conf = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.77.0.2/32, fd00:77::2/128
        DNS = 10.77.0.1
        [Peer]
        PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
        Endpoint = vpn.example.invalid:51820
        AllowedIPs = 10.77.0.0/24, fd00:77::/64
        PersistentKeepalive = 25
    """.trimIndent()
    private val profile = ProfileEditor.importWireGuard("", WireGuardImport.parse(conf), "WG", ProfileEditor.WG_OUTBOUND_ONLY)
    private val ports = listOf(TunnelPortForward(8022), TunnelPortForward(2222, 8022))
    private fun edited() = ConnectionEditing.wireGuard(profile, "WG", "WG", conf, ProfileEditor.WG_OUTBOUND_ONLY, ports)

    @Test fun importsAndExistingEditsKeepInboundClosedByDefault() {
        assertFalse(ConnectionEditing.node(profile, "WG").containsKey("listen-ports"))
        val result = ConnectionEditing.wireGuard(profile, "WG", "WG", conf, ProfileEditor.WG_OUTBOUND_ONLY)
        assertFalse(ConnectionEditing.node(result, "WG").containsKey("listen-ports"))
    }

    @Test fun changingPortsPreservesKeysPeersRoutingDnsAndKeepalive() {
        val result = edited()
        val node = ConnectionEditing.node(result, "WG").toMutableMap()
        assertEquals(ports, TunnelPortForwards.read(node))
        node.remove("listen-ports")
        assertEquals(ConnectionEditing.node(profile, "WG"), node)
        assertEquals(ProfileEditor.view(profile).rules, ProfileEditor.view(result).rules)
        assertEquals(ProfileEditor.root(profile)["dns"], ProfileEditor.root(result)["dns"])
        ProfileCheck.check(result)
    }

    @Test fun unrelatedEditAndRenameKeepPortsAndExplicitRemovalClosesThem() {
        val renamed = ConnectionEditing.wireGuard(edited(), "WG", "WG-new", conf, ProfileEditor.WG_OUTBOUND_ONLY)
        assertEquals(ports, TunnelPortForwards.read(ConnectionEditing.node(renamed, "WG-new")))
        val removed = ConnectionEditing.wireGuard(renamed, "WG-new", "WG-new", conf, ProfileEditor.WG_OUTBOUND_ONLY, emptyList())
        assertFalse(ConnectionEditing.node(removed, "WG-new").containsKey("listen-ports"))
    }

    @Test fun disabledConnectionDropsRuntimeListenersAndReenableRestoresThem() {
        val disabled = ConnectionPolicy.setEnabled(edited(), "WG", false)
        assertEquals(ports, TunnelPortForwards.read(ConnectionEditing.node(disabled, "WG")))
        assertFalse(ConnectionPolicy.runtime(disabled).contains("listen-ports"))
        val enabled = ConnectionPolicy.runtime(ConnectionPolicy.setEnabled(disabled, "WG", true))
        assertEquals(ports, TunnelPortForwards.read(ConnectionEditing.node(enabled, "WG")))
    }

    @Test fun subscriptionReplacementRetainsLocalForwardingAndProfileCheckRejectsBadPorts() {
        val before = edited()
        val imported = ClashImport.prepare("""
            proxies: [{name: remote, type: socks5, server: example.invalid, port: 1080}]
            rules: ["MATCH,remote"]
        """.trimIndent())
        val merged = ClashImport.apply(before, imported, keepRules = true)
        assertEquals(ConnectionEditing.node(before, "WG"), ConnectionEditing.node(merged, "WG"))
        val root = ProfileEditor.root(before)
        val node = ConnectionEditing.node(before, "WG").toMutableMap()
        node["listen-ports"] = listOf(mapOf("port" to 8022), mapOf("port" to 8022))
        root["proxies"] = listOf(node)
        assertThrows(IllegalArgumentException::class.java) { ProfileCheck.check(ProfileEditor.dump(root)) }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionEditing.wireGuard(profile, "WG", "WG", conf, ProfileEditor.WG_OUTBOUND_ONLY, listOf(TunnelPortForward(0)))
        }
    }
}
