package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ProfileEditorTest {
    private val original = """
        dns:
          enable: true
        proxies:
          - {name: WG, type: wireguard, private-key: test-secret}
          - {name: INTERNET, type: socks5}
        proxy-groups:
          - {name: CHOICE, type: select, proxies: [INTERNET, DIRECT]}
        rules:
          - IP-CIDR,10.77.0.0/24,WG,no-resolve
          - MATCH,INTERNET
    """.trimIndent()

    @Test fun domesticPolicyPreservesCustomRulesAndMatchIsLast() {
        val edited = ProfileEditor.routing(original, true, "CHOICE")
        val view = ProfileEditor.view(edited)
        assertEquals(listOf("IP-CIDR,10.77.0.0/24,WG,no-resolve", "GEOSITE,cn,DIRECT",
            "GEOIP,CN,DIRECT,no-resolve", "MATCH,CHOICE"), view.rules)
        assertTrue(view.domestic)
        assertTrue(edited.contains("test-secret"))
        assertEquals(4, ProfileCheck.check(edited).rules)
        assertEquals(view.rules, ProfileEditor.view(ProfileEditor.routing(edited, true, "CHOICE")).rules)
        assertEquals(listOf("IP-CIDR,10.77.0.0/24,WG,no-resolve", "MATCH,INTERNET"),
            ProfileEditor.view(ProfileEditor.routing(edited, false, "INTERNET")).rules)
    }
    @Test fun lanSettingsSurviveRuleEditsAndCanBeDisabled() {
        val lan = LanProxy(true, 7891, "user", "a-strong-password")
        val edited = ProfileEditor.lan(original, lan)
        assertEquals(lan, ProfileEditor.view(ProfileEditor.routing(edited, true, "INTERNET")).lan)
        assertEquals(2, ProfileCheck.check(edited).nodes)
        assertFalse(ProfileEditor.view(ProfileEditor.lan(edited, lan.copy(enabled = false))).lan.enabled)
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.lan(original, lan.copy(port = 80)) }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.lan(original, lan.copy(password = "short")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.lan(original, lan.copy(user = "user:bad")) }
    }
    @Test fun newRulesPrecedeDomesticAndMatch() {
        val domestic = ProfileEditor.routing(original, true, "INTERNET")
        val edited = ProfileEditor.addRule(domestic, "DOMAIN-SUFFIX", " home.arpa ", "WG")
        assertEquals("DOMAIN-SUFFIX,home.arpa,WG", ProfileEditor.view(edited).rules[1])
        assertEquals(ProfileEditor.view(domestic).rules, ProfileEditor.view(ProfileEditor.removeRule(edited, 1)).rules)
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.addRule(original, "IP-CIDR", "999.2.3.4/24", "WG") }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.addRule(original, "IP-CIDR6", "bad:::/48", "WG") }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.addRule(original, "DOMAIN", "x,MATCH,DIRECT", "WG") }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.addRule(original, "DOMAIN", "host", "missing") }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.removeRule(original, 1) }
    }
    @Test fun invalidOrUnreachablePolicyCannotBeSaved() {
        assertThrows(IllegalArgumentException::class.java) { ProfileCheck.check(original.replace("MATCH,INTERNET", "MATCH,missing")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileCheck.check(original.replace("- IP-CIDR", "- MATCH,DIRECT\n  - IP-CIDR")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.view("dns: {}\ndns: {}") }
    }
    @Test fun localHttpPortCanBeConfiguredWithoutOpeningLanOrChangingRoutes() {
        val proxy = LanProxy(port = 8888, httpEnabled = true)
        val edited = ProfileEditor.lan(original, proxy)
        assertEquals(proxy, ProfileEditor.view(edited).lan)
        assertFalse(ProfileEditor.view(edited).lan.enabled)
        assertEquals(ProfileEditor.view(original).rules, ProfileEditor.view(edited).rules)
        assertTrue(edited.contains("test-secret"))
        ProfileCheck.check(edited)
    }
    @Test fun oldLanSettingsMigrateAndHttpOffPreservesPortAndCredentials() {
        val legacy = original + "\nx-onetunnel: {lan-enabled: true, lan-port: 7891, lan-user: user, lan-password: a-strong-password}\n"
        val before = ProfileEditor.view(legacy).lan
        assertTrue(before.httpEnabled)
        val edited = ProfileEditor.lan(legacy, before.copy(httpEnabled = false))
        val after = ProfileEditor.view(edited).lan
        assertFalse(after.httpEnabled)
        assertTrue(after.enabled)
        assertEquals(7891, after.port)
        assertEquals(before.password, after.password)
        ProfileCheck.check(edited)
    }
    @Test fun disablingSharingKeepsLocalHttpAndInvalidPortsAreNeverSaved() {
        val shared = LanProxy(true, 7890, "user", "a-strong-password")
        val edited = ProfileEditor.lan(original, shared.copy(enabled = false))
        assertTrue(ProfileEditor.view(edited).lan.httpEnabled)
        assertFalse(ProfileEditor.view(edited).lan.enabled)
        for (port in listOf(-1, 0, 1023, 65536)) assertThrows(IllegalArgumentException::class.java) {
            ProfileEditor.lan(original, LanProxy(port = port, httpEnabled = true))
        }
    }
    @Test fun anonymousLanIsExplicitAndSurvivesRuleEdits() {
        val anonymous = LanProxy(enabled = true, port = 8888, httpEnabled = true, authRequired = false)
        val edited = ProfileEditor.lan(original, anonymous)
        assertFalse(ProfileEditor.view(edited).lan.authRequired)
        assertEquals(anonymous, ProfileEditor.view(ProfileEditor.routing(edited, true, "INTERNET")).lan)
        ProfileCheck.check(edited)
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.lan(edited, anonymous.copy(authRequired = true)) }
    }
    @Test fun editingRulesPreservesOrderCredentialsAndSettings() {
        val base = ProfileEditor.lan(ProfileEditor.routing(original, true, "INTERNET"),
            LanProxy(httpEnabled = true, port = 8888))
        val before = ProfileEditor.view(base)
        val expected = before.rules.first()
        val edited = ProfileEditor.replaceRule(base, 0, expected, RoutingRule("IP-CIDR", "10.88.0.0/24", "DIRECT", true))
        val after = ProfileEditor.view(edited)
        assertEquals("IP-CIDR,10.88.0.0/24,DIRECT,no-resolve", after.rules.first())
        assertEquals(before.rules.drop(1), after.rules.drop(1))
        assertEquals(before.lan, after.lan)
        assertEquals(ProfileEditor.root(base)["proxies"], ProfileEditor.root(edited)["proxies"])
        ProfileCheck.check(ConnectionPolicy.runtime(edited))
    }
    @Test fun ruleEditorPreservesDnsResolutionOptionsAndRecognizesUnsupportedRules() {
        for (raw in listOf("IP-CIDR,10.77.0.0/24,WG", "IP-CIDR,10.77.0.0/24,WG,no-resolve", "GEOIP,CN,DIRECT", "GEOIP,CN,DIRECT,no-resolve")) {
            val rule = requireNotNull(ProfileEditor.editableRule(raw))
            val base = ProfileEditor.dump(ProfileEditor.root(original).apply { put("rules", listOf(raw, "MATCH,INTERNET")) })
            val changed = ProfileEditor.replaceRule(base, 0, raw, rule.copy(exit = "REJECT"))
            assertEquals(rule.copy(exit = "REJECT"), ProfileEditor.editableRule(ProfileEditor.view(changed).rules.first()))
        }
        assertNull(ProfileEditor.editableRule("RULE-SET,custom,DIRECT"))
        assertNull(ProfileEditor.editableRule("AND,((DOMAIN,example.com),(DST-PORT,443)),DIRECT"))
        assertNull(ProfileEditor.editableRule("DOMAIN,example.com,DIRECT,unexpected"))
    }
    @Test fun matchEditingKeepsAllOtherRulesAndCannotCreateEarlyMatch() {
        val before = ProfileEditor.view(original).rules
        val edited = ProfileEditor.replaceRule(original, before.lastIndex, before.last(), RoutingRule("MATCH", "", "REJECT"))
        assertEquals(before.dropLast(1) + "MATCH,REJECT", ProfileEditor.view(edited).rules)
        ProfileCheck.check(edited)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileEditor.replaceRule(original, 0, before.first(), RoutingRule("MATCH", "", "DIRECT"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileEditor.replaceRule(original, before.lastIndex, before.last(), RoutingRule("DOMAIN", "example.com", "DIRECT"))
        }
    }
    @Test fun staleEditsAndInvalidReplacementsDoNotOverwriteRules() {
        val expected = ProfileEditor.view(original).rules.first()
        for (next in listOf(RoutingRule("DOMAIN", "bad,domain", "DIRECT"), RoutingRule("IP-CIDR", "999.1.2.3/24", "WG"), RoutingRule("DOMAIN", "example.com", "missing"))) {
            assertThrows(IllegalArgumentException::class.java) { ProfileEditor.replaceRule(original, 0, expected, next) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileEditor.replaceRule(original, 0, "DOMAIN,stale.example,DIRECT", RoutingRule("DOMAIN", "new.example", "DIRECT"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileEditor.replaceRule(original, 99, expected, RoutingRule("DOMAIN", "example.com", "DIRECT"))
        }
    }
    @Test fun reorderingPreservesDuplicateRulesAndOnlyChangesPrecedence() {
        val rule = "DOMAIN-SUFFIX,example.com,DIRECT"
        val base = ProfileEditor.dump(ProfileEditor.root(original).apply {
            put("rules", listOf(rule, "IP-CIDR,10.77.0.0/24,WG,no-resolve", rule, "MATCH,INTERNET"))
        })
        val before = ProfileEditor.view(base).rules
        val order = listOf(1, 2, 0, 3)
        val changed = ProfileEditor.reorderRules(base, before, order)
        assertEquals(order.map { before[it] }, ProfileEditor.view(changed).rules)
        assertEquals(ProfileEditor.root(base)["proxies"], ProfileEditor.root(changed)["proxies"])
        ProfileCheck.check(changed)
        val inverse = order.indices.sortedBy { order[it] }
        assertEquals(before, ProfileEditor.view(ProfileEditor.reorderRules(changed, ProfileEditor.view(changed).rules, inverse)).rules)
    }
    @Test fun sortingCannotLoseDuplicateInjectOrMoveMatchRules() {
        val base = ProfileEditor.routing(original, true, "INTERNET")
        val before = ProfileEditor.view(base).rules
        for (order in listOf(listOf(1, 0, 3, 2), listOf(0, 0, 2, 3), listOf(0, 1, 3), listOf(0, 1, 4, 3))) {
            assertThrows(IllegalArgumentException::class.java) { ProfileEditor.reorderRules(base, before, order) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProfileEditor.reorderRules(base, before.reversed(), before.indices.toList()) }
    }
}
