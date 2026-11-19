package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ConnectionEditingTest {
    private val secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val profile = """
        dns:
          enable: true
          nameserver-policy: {'+.ts.net': 'tailscale://TAIL'}
        proxies:
          - {name: WG, type: wireguard, private-key: '$secret', ip: '10.9.0.2/32', custom-field: keep, peers: [{server: old.example.com, port: 51820, public-key: '$secret', allowed-ips: ['10.9.0.0/24']}]}
          - {name: TAIL, type: tailscale, hostname: old-host, state-dir: stable-identity}
          - {name: server, type: vmess}
        proxy-groups:
          - {name: Proxy, type: select, proxies: [server, WG]}
          - {name: Streaming, type: select, proxies: [Proxy]}
        rules:
          - IP-CIDR,10.9.0.0/24,WG,no-resolve
          - DOMAIN-SUFFIX,ts.net,TAIL
          - MATCH,Proxy
        x-onetunnel:
          disabled-outbounds: [WG]
          subscription: {name: test, url: 'https://example.com/sub', node-names: [server]}
    """.trimIndent()
    @Test fun simpleMatchChoicesHideInternalGroupsAndIndividualProxyNodes() {
        val options = ConnectionEditing.matchOptions(profile)
        assertEquals(setOf("Proxy", "WG", "TAIL", "DIRECT", "REJECT"), options.keys)
        assertEquals("Clash", options["Proxy"])
        assertFalse(options.containsKey("GLOBAL"))
        assertFalse(options.containsKey("Streaming"))
        assertFalse(options.containsKey("server"))
    }
    @Test fun wireGuardEditUpdatesRoutesAndReferencesWithoutLosingUnknownFields() {
        val conf = """
            [Interface]
            PrivateKey = $secret
            Address = 10.8.0.2/32
            [Peer]
            PublicKey = $secret
            Endpoint = new.example.com:12345
            AllowedIPs = 10.8.0.0/24
        """.trimIndent()
        val edited = ConnectionEditing.wireGuard(profile, "WG", "WG-New", conf, ProfileEditor.WG_ROUTES)
        val node = ConnectionEditing.node(edited, "WG-New")
        assertEquals("keep", node["custom-field"])
        assertEquals("10.8.0.2/32", node["ip"])
        assertTrue(ProfileEditor.view(edited).rules.any { it == "IP-CIDR,10.8.0.0/24,WG-New,no-resolve" })
        assertFalse(edited.contains("10.9.0.0/24"))
        assertFalse(ConnectionPolicy.entries(edited).first { it.name == "WG-New" }.enabled)
        assertEquals(listOf("server", "WG-New"), ((ProfileEditor.root(edited)["proxy-groups"] as List<*>)[0] as Map<*, *>)["proxies"])
    }
    @Test fun tailscaleRenameKeepsIdentityAndRenamesDnsAndRules() {
        val edited = ConnectionEditing.tailscale(profile, "TAIL", "TAIL-New", "new-host", "", true)
        assertEquals("stable-identity", ConnectionEditing.node(edited, "TAIL-New")["state-dir"])
        assertTrue(edited.contains("tailscale://TAIL-New"))
        assertTrue(ProfileEditor.view(edited).rules.contains("DOMAIN-SUFFIX,ts.net,TAIL-New"))
    }
    @Test fun differentControlServerUsesDifferentIdentityAndRejectsNameCollision() {
        val edited = ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "new-host", "https://headscale.example.com", true)
        assertNotEquals("stable-identity", ConnectionEditing.node(edited, "TAIL")["state-dir"])
        assertThrows(IllegalArgumentException::class.java) { ConnectionEditing.tailscale(profile, "TAIL", "WG", "new-host", "", true) }
    }
    @Test fun subscriptionRenamePreservesCurrentNodesAndRulesAndDisabledState() {
        val disabled = ConnectionPolicy.setSubscription(profile, false)
        val edited = ConnectionEditing.subscriptionMeta(disabled, SubscriptionSource("renamed", "https://example.com/sub", 12))
        assertEquals("renamed", ClashImport.subscription(edited)?.name)
        assertEquals(ProfileEditor.view(profile).rules, ProfileEditor.view(edited).rules)
        assertEquals(ConnectionEditing.node(profile, "WG"), ConnectionEditing.node(edited, "WG"))
        assertFalse(ConnectionPolicy.subscriptionEnabled(edited))
    }
}
