package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    private val profile = """
        dns:
          enable: true
          nameserver-policy: {'+.ts.net': 'tailscale://TAIL'}
        proxies:
          - {name: WG-A, type: wireguard, private-key: secret}
          - {name: WG-B, type: wireguard}
          - {name: TAIL, type: tailscale}
          - {name: remote, type: vmess}
        proxy-groups:
          - {name: proxy, type: select, proxies: [remote]}
        rules:
          - IP-CIDR,10.0.0.0/8,WG-A,no-resolve
          - IP-CIDR,10.1.0.0/16,WG-B,no-resolve
          - MATCH,proxy
        x-onetunnel:
          subscription: {name: sub, url: 'https://example.com/sub', node-names: [remote]}
    """.trimIndent()
    @Test fun multipleConnectionsRemainIndependentAndPersistAcrossReload() {
        val saved = ConnectionPolicy.setEnabled(profile, "WG-A", false)
        val entries = ConnectionPolicy.entries(saved).associateBy { it.name }
        assertFalse(entries.getValue("WG-A").enabled)
        assertTrue(entries.getValue("WG-B").enabled)
        assertTrue(entries.getValue("TAIL").enabled)
        val runtime = ConnectionPolicy.runtime(saved)
        assertTrue(saved.contains("private-key"))
        assertFalse(runtime.contains("private-key"))
        assertEquals(ProfileEditor.view(saved).rules, ProfileEditor.view(runtime).rules)
        val group = (ProfileEditor.root(runtime)["proxy-groups"] as List<*>).map { it as Map<*, *> }.first { it["name"] == "WG-A" }
        assertEquals(listOf("REJECT"), group["proxies"])
        ProfileCheck.check(runtime)
    }
    @Test fun subscriptionOffKeepsLocalConnectionsAndBlocksRemoteTargets() {
        val saved = ConnectionPolicy.setSubscription(profile, false)
        val runtime = ProfileEditor.root(ConnectionPolicy.runtime(saved))
        assertEquals(setOf("WG-A", "WG-B", "TAIL"), (runtime["proxies"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet())
        assertEquals("proxy", ProfileEditor.view(ProfileEditor.dump(runtime)).match)
        assertFalse(ConnectionPolicy.subscriptionEnabled(saved))
    }
    @Test fun disabledTailscaleDnsDoesNotStartItsAdapterOrLeakToPublicResolver() {
        val runtime = ProfileEditor.root(ConnectionPolicy.runtime(ConnectionPolicy.setEnabled(profile, "TAIL", false)))
        assertEquals("rcode://refused", ((runtime["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>)["+.ts.net"])
    }
    @Test fun lanChangesPreserveSubscriptionAndSwitchState() {
        val saved = ConnectionPolicy.setEnabled(profile, "WG-A", false)
        val changed = ProfileEditor.lan(saved, LanProxy())
        assertNotNull(ClashImport.subscription(changed))
        assertFalse(ConnectionPolicy.entries(changed).first { it.name == "WG-A" }.enabled)
    }
    @Test fun detectsNestedRoutesButIgnoresSameConnectionAndDisabledRoutes() {
        assertEquals(1, WireGuardConflicts.find(profile).size)
        assertTrue(WireGuardConflicts.find(ConnectionPolicy.setEnabled(profile, "WG-B", false)).isEmpty())
        assertTrue(WireGuardConflicts.find(profile.replace("10.1.0.0/16", "192.168.0.0/16")).isEmpty())
        assertTrue(WireGuardConflicts.find(profile.replace("WG-B,no-resolve", "WG-A,no-resolve")).isEmpty())
    }
    @Test fun detectsIpv6AndDefaultRouteOverlap() {
        assertEquals(1, WireGuardConflicts.find(profile.replace("IP-CIDR,10.0.0.0/8", "IP-CIDR6,fd00::/8").replace("IP-CIDR,10.1.0.0/16", "IP-CIDR6,fd01::/32")).size)
        assertEquals(1, WireGuardConflicts.find(profile.replace("10.0.0.0/8", "0.0.0.0/0")).size)
    }
    @Test fun addingConnectionsPreservesExistingRuntimePolicyAndAnonymousProxySettings() {
        val base = ProfileEditor.lan(ConnectionPolicy.setEnabled(profile, "WG-A", false),
            LanProxy(enabled = true, port = 8888, httpEnabled = true, authRequired = false))
        val wg = WireGuardImport.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 192.168.200.2/32
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Endpoint = example.com:51820
            AllowedIPs = 192.168.200.0/24
        """.trimIndent())
        val withWg = ProfileEditor.importWireGuard(base, wg, "WG-New", ProfileEditor.WG_ROUTES)
        val added = TailscaleProfiles.add(withWg, "TAIL-New", "new-device", "")
        val runtime = ConnectionPolicy.runtime(added)
        ProfileCheck.check(runtime)
        assertEquals("proxy", ProfileEditor.view(runtime).match)
        assertEquals(ProfileEditor.view(base).lan, ProfileEditor.view(runtime).lan)
        assertEquals(ConnectionEditing.node(base, "TAIL"), ConnectionEditing.node(added, "TAIL"))
        assertFalse(ConnectionPolicy.entries(added).first { it.name == "WG-A" }.enabled)
        assertTrue(ConnectionPolicy.entries(added).filter { it.name in setOf("WG-New", "TAIL-New") }.all { it.enabled })
        assertEquals("tailscale://TAIL", ((ProfileEditor.root(runtime)["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>)["+.ts.net"])
        assertTrue(ProfileEditor.view(runtime).rules.contains("IP-CIDR,192.168.200.0/24,WG-New,no-resolve"))
    }
}
