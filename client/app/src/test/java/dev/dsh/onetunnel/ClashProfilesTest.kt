package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ClashProfilesTest {
    @Test fun nestedSelectionShowsTheActualNodeAndStopsOnCycles() {
        val parent = ClashGroup("Proxy", "Selector", "Auto", 1)
        val auto = ClashGroup("Auto", "URLTest", "Tokyo", 3)
        assertEquals("Auto → Tokyo", ClashProfiles.selectionLabel(parent, listOf(parent, auto)))
        assertEquals("Auto", ClashProfiles.selectionLabel(parent, listOf(parent, auto.copy(now = "Proxy"))))
        assertEquals("按策略分配", ClashProfiles.selectionLabel(ClashGroup("Balance", "LoadBalance", "", 2), emptyList()))
    }
    private val source = SubscriptionSource("test", "https://example.invalid/a", 1)
    private val yaml = """
        proxies:
          - {name: one, type: socks5, server: one.invalid, port: 1080, username: dummy, password: dummy-secret}
          - {name: two, type: socks5, server: two.invalid, port: 1080}
        proxy-groups:
          - {name: Proxy, type: select, proxies: [Auto, one, two]}
          - {name: Auto, type: url-test, proxies: [one, two], url: 'https://www.gstatic.com/generate_204', interval: 300}
          - {name: Video, type: select, proxies: [Proxy, two]}
        rules: ["DOMAIN,old.invalid,one", "MATCH,Proxy"]
    """.trimIndent()
    private fun imported(url: SubscriptionSource = source, body: String = yaml) = ClashImport.prepare(body, url)
    private val profile get() = ClashImport.apply("", imported(), false)

    @Test fun groupsAndNodesKeepTheirStructureAndStrategy() {
        val groups = ClashProfiles.configuredGroups(profile)
        assertEquals(listOf("Proxy", "Auto", "Video"), groups.map { it.name })
        assertTrue(groups[0].manual); assertFalse(groups[1].manual)
        assertEquals("自动选择", groups[1].label)
        assertEquals(listOf("Auto", "one", "two"), ClashProfiles.nodes(profile, "Proxy").map { it.name })
    }
    @Test fun switchingPreservesRulesCredentialsAndOtherGroupsAndSurvivesYamlRoundTrip() {
        val before = profile
        val after = ClashProfiles.select(before, "Proxy", "two")
        assertEquals(ProfileEditor.root(before)["proxies"], ProfileEditor.root(after)["proxies"])
        assertEquals(ProfileEditor.root(before)["dns"], ProfileEditor.root(after)["dns"])
        assertEquals(ProfileEditor.view(before).rules, ProfileEditor.view(after).rules)
        assertEquals("two", ClashProfiles.configuredGroups(ProfileEditor.dump(ProfileEditor.root(after)))[0].now)
        assertEquals(ClashProfiles.configuredGroups(before)[2], ClashProfiles.configuredGroups(after)[2])
    }
    @Test fun rejectsAutomaticGroupsUnknownMembersAndMissingGroups() {
        for ((group, node) in listOf("Auto" to "one", "Proxy" to "absent", "missing" to "one"))
            assertThrows(IllegalArgumentException::class.java) { ClashProfiles.select(profile, group, node) }
        assertThrows(IllegalArgumentException::class.java) { ClashProfiles.select(profile, "Proxy", "bad\nname", true) }
    }
    @Test fun ruleChoicesShowGroupsAndLocalTunnelsButHideSubscriptionNodesAndInternalGroups() {
        val root = ProfileEditor.root(profile)
        root["proxies"] = (root["proxies"] as List<*>) + listOf(mapOf("name" to "WG", "type" to "wireguard"), mapOf("name" to "TAIL", "type" to "tailscale"))
        val text = ProfileEditor.dump(root)
        val choices = ClashProfiles.ruleOptions(text)
        assertEquals(setOf("Proxy", "Auto", "Video", "WG", "TAIL", "DIRECT", "REJECT"), choices.keys)
        assertFalse(choices.containsKey("one")); assertFalse(choices.containsKey("GLOBAL"))
        val expected = ProfileEditor.view(text).rules[0]
        val edited = ProfileEditor.replaceRule(text, 0, expected, RoutingRule("DOMAIN", "changed.invalid", "one"))
        assertTrue(ProfileEditor.view(edited).rules.contains("DOMAIN,changed.invalid,one"))
    }
    @Test fun refreshingSameSubscriptionRetainsSelectionAndRemovedNodesFallBack() {
        val selected = ClashProfiles.select(profile, "Proxy", "two")
        val refreshed = ClashImport.apply(selected, imported(), true)
        assertEquals("two", ClashProfiles.configuredGroups(refreshed)[0].now)
        val removed = ClashImport.apply(selected, imported(body = yaml.replace("[Auto, one, two]", "[Auto, one]")), true)
        assertNotEquals("two", ClashProfiles.configuredGroups(removed)[0].now)
    }
    @Test fun switchingSubscriptionsKeepsIndependentSelections() {
        val a = ClashProfiles.select(profile, "Proxy", "two")
        val bSource = SubscriptionSource("other", "https://example.invalid/b", 1)
        val b = ClashImport.apply(a, imported(bSource), false)
        assertEquals("Auto", ClashProfiles.configuredGroups(b)[0].now)
        val selectedB = ClashProfiles.select(b, "Proxy", "one")
        val backA = ClashImport.apply(selectedB, imported(), false)
        assertEquals("two", ClashProfiles.configuredGroups(backA)[0].now)
        assertEquals("one", ClashProfiles.configuredGroups(ClashImport.apply(backA, imported(bSource), false))[0].now)
    }
    @Test fun providerSelectionsNeedRuntimeValidationAndAreRestoredAfterReload() {
        val root = ProfileEditor.root(profile)
        root["proxy-groups"] = listOf(mapOf("name" to "Provider", "type" to "select", "use" to listOf("Remote")))
        root["rules"] = listOf("MATCH,Provider")
        val text = ProfileEditor.dump(root)
        assertThrows(IllegalArgumentException::class.java) { ClashProfiles.select(text, "Provider", "dynamic-node") }
        val selected = ClashProfiles.select(text, "Provider", "dynamic-node", true)
        assertEquals("dynamic-node", ClashProfiles.configuredGroups(selected)[0].now)
    }
}
