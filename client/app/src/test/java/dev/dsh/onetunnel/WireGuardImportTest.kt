package dev.dsh.onetunnel

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class WireGuardImportTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val presharedKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val profile = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.77.0.2/32, fd00:77::2/128
        DNS = 10.77.0.1, home.arpa
        MTU = 1280
        [Peer]
        PublicKey = $publicKey
        PresharedKey = $presharedKey
        Endpoint = vpn.example.com:51820
        AllowedIPs = 10.77.0.0/24, fd00:77::/64
        PersistentKeepalive = 25
    """.trimIndent()
    @Test fun importsKeysDualStackAndPeerRestrictions() {
        val wg = WireGuardImport.parse("\uFEFF" + profile)
        assertEquals(1, wg.peerCount)
        assertFalse(wg.hasDefaultRoute)
        assertEquals("10.77.0.2/32", wg.outbound["ip"])
        assertEquals("fd00:77::2/128", wg.outbound["ipv6"])
        assertEquals(listOf("10.77.0.1"), wg.dnsServers)
        assertEquals(2, wg.warnings.size)
        val peer = (wg.outbound["peers"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("10.77.0.0/24", "fd00:77::/64"), peer["allowed-ips"])
        assertEquals(presharedKey, peer["pre-shared-key"])
        assertFalse(wg.toString().contains(privateKey))
    }
    @Test fun mergesWithoutReplacingExistingRulesAndRejectsDuplicateName() {
        val wg = WireGuardImport.parse(profile)
        val first = ProfileEditor.importWireGuard("", wg, "WG", ProfileEditor.WG_ROUTES)
        val view = ProfileEditor.view(first)
        assertTrue(view.domestic)
        assertEquals("MATCH,REJECT", view.rules.last())
        assertEquals("IP-CIDR,10.77.0.0/24,WG,no-resolve", view.rules.first())
        assertEquals(1, ProfileCheck.check(first).wireguard)
        val second = ProfileEditor.importWireGuard(first, wg, "WG-2", ProfileEditor.WG_OUTBOUND_ONLY)
        assertEquals(view.rules, ProfileEditor.view(second).rules)
        assertEquals(2, ProfileCheck.check(second).wireguard)
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.importWireGuard(first, wg, "WG", ProfileEditor.WG_ROUTES) }
    }
    @Test fun defaultRoutesRequireExplicitMatchChoice() {
        val wg = WireGuardImport.parse(profile.replace("10.77.0.0/24, fd00:77::/64", "0.0.0.0/0, ::/0"))
        assertTrue(wg.hasDefaultRoute)
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.importWireGuard("", wg, "WG", ProfileEditor.WG_ROUTES) }
        val merged = ProfileEditor.importWireGuard("", wg, "WG", ProfileEditor.WG_MATCH)
        assertEquals("WG", ProfileEditor.view(merged).match)
        assertTrue(ProfileEditor.view(merged).domestic)
    }
    @Test fun rejectsUnsupportedSemanticsAndDoesNotEchoSecrets() {
        listOf(profile.replace(privateKey, "invalid-private-key"), profile.replace("Address =", "PostUp ="),
            profile.replace("Address =", "IncludedApplications ="), profile.replace("51820", "70000"),
            profile.replace("10.77.0.0/24", "300.77.0.0/24"), profile.replace("/32, fd00", "/32, 10.99.0.2/32, fd00"),
            profile.replace("[Peer]", "[Unknown]")).forEach { invalid ->
            val error = assertThrows(IllegalArgumentException::class.java) { WireGuardImport.parse(invalid) }
            assertFalse(error.message.orEmpty().contains(privateKey))
            assertFalse(error.message.orEmpty().contains("invalid-private-key"))
        }
    }
    @Test fun supportsIpv6EndpointAndRepeatedLists() {
        val wg = WireGuardImport.parse(profile.replace("vpn.example.com:51820", "[2001:db8::1]:51820")
            .replace("AllowedIPs = 10.77.0.0/24, fd00:77::/64", "AllowedIPs = 10.77.0.0/24\nAllowedIPs = fd00:77::/64"))
        assertEquals(2, wg.allowedIPs.size)
        assertEquals("2001:db8::1", ((wg.outbound["peers"] as List<*>).single() as Map<*, *>)["server"])
    }
}
