package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ConnectionRemovalTest {
    private val profile = """
        dns:
          enable: true
          nameserver: [https://dns.alidns.com/dns-query]
          nameserver-policy: {'+.ts.net': 'tailscale://TAIL'}
        proxies:
          - {name: WG, type: wireguard, private-key: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=', ip: 10.9.0.2, peers: [{server: wg.example.com, port: 51820, public-key: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=', allowed-ips: [10.9.0.0/24]}]}
          - {name: TAIL, type: tailscale, hostname: device, state-dir: stable-identity}
          - {name: remote, type: vmess}
          - {name: remote-tail, type: tailscale, state-dir: remote-identity}
        proxy-groups: [{name: Proxy, type: select, proxies: [remote, WG]}]
        rules:
          - IP-CIDR,10.9.0.0/24,WG,no-resolve
          - DOMAIN-SUFFIX,ts.net,TAIL
          - RULE-SET,remote-rules,Proxy
          - GEOSITE,cn,DIRECT
          - GEOIP,CN,DIRECT,no-resolve
          - MATCH,Proxy
        sub-rules: {test: ['DOMAIN,example.com,WG']}
        rule-providers: {remote-rules: {type: http, behavior: domain, url: 'https://example.com/rules'}}
        x-onetunnel:
          lan-enabled: true
          lan-password: preserved-password
          disabled-outbounds: [WG, remote]
          subscription: {name: test, url: 'https://example.com/sub', node-names: [remote, remote-tail]}
    """.trimIndent()
    @Test fun deletingWireGuardRetargetsRulesAndGroupReferencesToReject() {
        val result = ConnectionRemoval.node(profile, "WG")
        assertFalse(ConnectionPolicy.entries(result).any { it.name == "WG" })
        assertTrue(ProfileEditor.view(result).rules.contains("IP-CIDR,10.9.0.0/24,REJECT,no-resolve"))
        assertTrue(result.contains("DOMAIN,example.com,REJECT"))
        val root = ProfileEditor.root(result)
        assertEquals(listOf("remote", "REJECT"), ((root["proxy-groups"] as List<*>)[0] as Map<*, *>)["proxies"])
        assertEquals(listOf("remote"), (root["x-onetunnel"] as Map<*, *>)["disabled-outbounds"])
        assertEquals("stable-identity", ConnectionEditing.node(result, "TAIL")["state-dir"])
    }
    @Test fun deletingTailscaleBlocksMagicDnsAndMatchWithoutDirectFallback() {
        val result = ConnectionRemoval.node(ProfileEditor.routing(profile, true, "TAIL"), "TAIL")
        assertEquals("REJECT", ProfileEditor.view(result).match)
        assertTrue(result.contains("rcode://refused"))
        assertFalse(result.contains("tailscale://TAIL"))
    }
    @Test fun removingSubscriptionKeepsOnlyLocalConnectionsAndTheirIdentityRulesAndLanSettings() {
        val result = ConnectionRemoval.subscription(profile)
        assertNull(ClashImport.subscription(result))
        assertEquals(setOf("WG", "TAIL"), ConnectionPolicy.entries(result).map { it.name }.toSet())
        assertEquals("stable-identity", ConnectionEditing.node(result, "TAIL")["state-dir"])
        assertFalse(ConnectionPolicy.entries(result).first { it.name == "WG" }.enabled)
        assertEquals("REJECT", ProfileEditor.view(result).match)
        assertTrue(ProfileEditor.view(result).domestic)
        assertTrue(ProfileEditor.view(result).lan.enabled)
        assertEquals("preserved-password", ProfileEditor.view(result).lan.password)
        assertTrue(result.contains("tailscale://TAIL"))
        assertFalse(result.contains("example.com/sub"))
        assertFalse(result.contains("rule-providers"))
        assertFalse(result.contains("RULE-SET"))
    }
    @Test fun removingSubscriptionPreservesLocalMatchAndDomesticPreference() {
        val result = ConnectionRemoval.subscription(ProfileEditor.routing(profile, false, "TAIL"))
        assertEquals("TAIL", ProfileEditor.view(result).match)
        assertFalse(ProfileEditor.view(result).domestic)
    }
    @Test fun staleDeletionDoesNotModifyAnotherConnection() {
        assertThrows(NoSuchElementException::class.java) { ConnectionRemoval.node(profile, "missing") }
    }
    @Test fun deletingLastConnectionLeavesValidBlockedProfile() {
        val only = TailscaleProfiles.add("", "TAIL", "device", "")
        val result = ConnectionRemoval.node(only, "TAIL")
        assertTrue(ConnectionPolicy.entries(result).isEmpty())
        assertEquals("REJECT", ProfileEditor.view(result).match)
        ProfileCheck.check(ConnectionPolicy.runtime(result))
    }
    @Test fun removingSubscriptionWithNoLocalConnectionsLeavesNoRemoteCredentials() {
        val remoteOnly = ConnectionRemoval.node(ConnectionRemoval.node(profile, "WG"), "TAIL")
        val result = ConnectionRemoval.subscription(remoteOnly)
        assertTrue(ConnectionPolicy.entries(result).isEmpty())
        assertFalse(result.contains("remote-identity"))
        assertEquals("REJECT", ProfileEditor.view(result).match)
    }
}
