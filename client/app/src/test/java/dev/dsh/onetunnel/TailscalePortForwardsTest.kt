package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class TailscalePortForwardsTest {
    private val profile = """
        dns: {enable: true, nameserver-policy: {'+.ts.net': 'tailscale://TAIL'}}
        proxies:
          - {name: TAIL, type: tailscale, hostname: device, state-dir: unchanged-identity, auth-key: test-secret, accept-routes: true}
        rules: ["IP-CIDR,100.64.0.0/10,TAIL,no-resolve", "MATCH,DIRECT"]
    """.trimIndent()
    private val forwards = listOf(TailscalePortForward(8022), TailscalePortForward(2222, 8022))
    private fun withForwards() = ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "device", "", true, forwards)

    @Test fun existingProfilesKeepAllInboundPortsClosedByDefault() {
        assertTrue(TailscalePortForwards.read(ConnectionEditing.node(profile, "TAIL")).isEmpty())
        val edited = ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "renamed-device", "", true)
        assertFalse(ConnectionEditing.node(edited, "TAIL").containsKey("listen-ports"))
        ProfileCheck.check(edited)
    }

    @Test fun changingForwardingKeepsIdentityCredentialsAndRouting() {
        val edited = withForwards()
        val before = ConnectionEditing.node(profile, "TAIL")
        val after = ConnectionEditing.node(edited, "TAIL").toMutableMap()
        assertEquals(forwards, TailscalePortForwards.read(after))
        after.remove("listen-ports")
        assertEquals(before, after)
        assertEquals(ProfileEditor.view(profile).rules, ProfileEditor.view(edited).rules)
        assertEquals(ProfileEditor.root(profile)["dns"], ProfileEditor.root(edited)["dns"])
        ProfileCheck.check(edited)
    }

    @Test fun renameAndUnrelatedEditKeepForwardsAndRemovingLastPortClosesThem() {
        val renamed = ConnectionEditing.tailscale(withForwards(), "TAIL", "NEW", "new-device", "", true)
        val node = ConnectionEditing.node(renamed, "NEW")
        assertEquals(forwards, TailscalePortForwards.read(node))
        assertEquals("unchanged-identity", node["state-dir"])
        val removed = ConnectionEditing.tailscale(renamed, "NEW", "NEW", "new-device", "", true, emptyList())
        assertFalse(ConnectionEditing.node(removed, "NEW").containsKey("listen-ports"))
    }

    @Test fun disabledRuntimeRemovesListenerButKeepsSavedConfigForReenable() {
        val edited = withForwards()
        val disabled = ConnectionPolicy.setEnabled(edited, "TAIL", false)
        assertEquals(forwards, TailscalePortForwards.read(ConnectionEditing.node(disabled, "TAIL")))
        val runtime = ProfileEditor.root(ConnectionPolicy.runtime(disabled))
        assertTrue((runtime["proxies"] as List<*>).none { (it as Map<*, *>)["type"] == "tailscale" })
        assertFalse(ConnectionPolicy.runtime(disabled).contains("listen-ports"))
        val enabled = ConnectionPolicy.runtime(ConnectionPolicy.setEnabled(disabled, "TAIL", true))
        assertEquals(forwards, TailscalePortForwards.read(ConnectionEditing.node(enabled, "TAIL")))
    }

    @Test fun subscriptionReplacementKeepsLocalTailscaleForwardsAndIdentity() {
        val edited = withForwards()
        val imported = ClashImport.prepare("""
            proxies: [{name: remote, type: socks5, server: example.invalid, port: 1080}]
            rules: ["MATCH,remote"]
        """.trimIndent())
        val merged = ClashImport.apply(edited, imported, keepRules = true)
        assertEquals(ConnectionEditing.node(edited, "TAIL"), ConnectionEditing.node(merged, "TAIL"))
        ProfileCheck.check(merged)
    }

    @Test fun rejectsMalformedDuplicateAndOutOfRangePortsBeforeSavingOrStarting() {
        val invalid = listOf<Any>("8022", listOf(8022), listOf(mapOf("port" to "8022")),
            listOf(mapOf("port" to 8022.5)), listOf(mapOf("port" to 0)),
            listOf(mapOf("port" to 8022, "local-port" to 65536)),
            listOf(mapOf("port" to 8022), mapOf("port" to 8022)),
            (1..17).map { mapOf("port" to it) })
        for (ports in invalid) {
            val root = ProfileEditor.root(profile)
            val node = ConnectionEditing.node(profile, "TAIL").toMutableMap()
            node["listen-ports"] = ports
            root["proxies"] = listOf(node)
            assertThrows(IllegalArgumentException::class.java) { ProfileCheck.check(ProfileEditor.dump(root)) }
        }
        assertEquals(listOf(TailscalePortForward(8022)),
            TailscalePortForwards.read(mapOf("listen-ports" to listOf(mapOf("port" to 8022L)))))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "device", "", true, listOf(TailscalePortForward(8022, -1)))
        }
    }
}
