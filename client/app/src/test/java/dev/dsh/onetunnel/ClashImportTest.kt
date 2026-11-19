package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ClashImportTest {
    private val vless = """
        proxies:
          - {name: '韩国 🇰🇷', type: vless, server: example.com, port: 443, uuid: test-secret}
        proxy-groups:
          - {name: '选择 🚀', type: select, proxies: ['韩国 🇰🇷', DIRECT]}
        rules:
          - MATCH,选择 🚀
    """.trimIndent()
    @Test fun acceptsKoreanStyleVlessWithoutDnsAndPreservesEmoji() {
        val preview = ClashImport.prepare(vless)
        assertEquals(1, preview.nodes)
        assertTrue(preview.yaml.contains("🇰🇷"))
        assertEquals("选择 🚀", ProfileEditor.view(preview.yaml).match)
        assertEquals(1, ProfileCheck.check(preview.yaml).nodes)
    }
    @Test fun stripsDesktopProcessRulesAndUntrustedAppSettings() {
        val preview = ClashImport.prepare(vless.replace("- MATCH", "- PROCESS-NAME,desktop.exe,DIRECT\n  - MATCH") + "\nexternal-controller: 0.0.0.0:9090\nmixed-port: 7890\nx-onetunnel:\n  lan-enabled: true\n")
        val root = ProfileEditor.root(preview.yaml)
        assertFalse(root.containsKey("external-controller"))
        assertFalse(root.containsKey("mixed-port"))
        assertFalse(root.containsKey("x-onetunnel"))
        assertFalse(preview.yaml.contains("PROCESS-NAME"))
        assertEquals(1, preview.rules)
    }
    @Test fun preservesLocalWgTailscaleAndLanOnSubscriptionSwitch() {
        val current = """
            dns:
              enable: true
              nameserver-policy: {'+.ts.net': 'tailscale://TAILNET'}
            proxies:
              - {name: WG, type: wireguard}
              - {name: TAILNET, type: tailscale}
            rules:
              - IP-CIDR,10.77.0.0/24,WG,no-resolve
              - DOMAIN-SUFFIX,ts.net,TAILNET
              - MATCH,REJECT
            x-onetunnel:
              lan-enabled: true
              lan-port: 7890
              lan-user: user
              lan-password: a-strong-password
        """.trimIndent()
        val source = SubscriptionSource("韩国", "https://example.com/sub?token=test-secret", 123L)
        val result = ClashImport.apply(current, ClashImport.prepare(vless, source), false)
        assertEquals(3, ProfileCheck.check(result).nodes)
        val view = ProfileEditor.view(result)
        assertTrue(view.rules.first().contains(",WG,"))
        assertTrue(view.lan.enabled)
        assertEquals(source.url, ClashImport.subscription(result)?.url)
        assertTrue(result.contains("tailscale://TAILNET"))
        assertFalse(source.toString().contains("test-secret"))
    }
    @Test fun refreshDoesNotDuplicateSubscriptionOwnedWireguard() {
        val document = vless.replace("type: vless", "type: wireguard")
        val source = SubscriptionSource("test", "https://example.com/sub", 1L)
        val preview = ClashImport.prepare(document, source)
        val first = ClashImport.apply("", preview, false)
        val second = ClashImport.apply(first, preview, true)
        assertEquals(1, ProfileCheck.check(second).nodes)
    }
    @Test fun providerCachesArePortableAndLocalFilesAreRejected() {
        val provider = """
            proxy-providers:
              remote:
                type: http
                url: https://example.com/nodes
                path: 'C:\Users\me\cache.yaml'
        """.trimIndent()
        val preview = ClashImport.prepare(provider)
        assertFalse(preview.yaml.contains("C:"))
        assertTrue(preview.yaml.contains("providers/proxy-"))
        assertEquals(1, preview.groups)
        assertThrows(IllegalArgumentException::class.java) { ClashImport.prepare(provider.replace("type: http", "type: file")) }
        assertThrows(IllegalArgumentException::class.java) { ClashImport.prepare("prepend: []\nappend: []") }
    }
    @Test fun preservingRulesRejectsRemovedTargets() {
        val first = ClashImport.apply("", ClashImport.prepare(vless), false)
        val changed = ClashImport.prepare(vless.replace("选择 🚀", "新组"))
        assertThrows(IllegalArgumentException::class.java) { ClashImport.apply(first, changed, true) }
        assertEquals("新组", ProfileEditor.view(ClashImport.apply(first, changed, false)).match)
    }
    @Test fun subscriptionSwitchPreservesLocalHttpPortAndSharingPreference() {
        val local = ProfileEditor.lan(ProfileEditor.defaultProfile(), LanProxy(port = 8888, httpEnabled = true))
        val result = ClashImport.apply(local, ClashImport.prepare(vless, SubscriptionSource("test", "https://example.com/sub", 1)), false)
        assertEquals(8888, ProfileEditor.view(result).lan.port)
        assertTrue(ProfileEditor.view(result).lan.httpEnabled)
        assertFalse(ProfileEditor.view(result).lan.enabled)
    }
}
