package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class TunnelPortForwardsTest {
    private val profile = """
        dns: {enable: true, nameserver-policy: {'+.ts.net': 'tailscale://TAIL'}}
        proxies:
          - {name: TAIL, type: tailscale, hostname: device, state-dir: unchanged-identity, auth-key: test-secret, accept-routes: true}
        rules: ["IP-CIDR,100.64.0.0/10,TAIL,no-resolve", "MATCH,DIRECT"]
    """.trimIndent()
    private val forwards = listOf(TunnelPortForward(8022), TunnelPortForward(2222, 8022))
    private fun withForwards() = ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "device", "", true, forwards)

    @Test fun allPortModeDefaultsOffAndRoundTripsWithoutChangingExistingConfiguration() {
        assertFalse(TunnelPortForwards.allEnabled(ConnectionEditing.node(profile, "TAIL")))
        val before = withForwards()
        val enabled = TunnelPortForwards.setAllEnabled(before, "TAIL", true)
        assertTrue(TunnelPortForwards.allEnabled(ConnectionEditing.node(enabled, "TAIL")))
        assertTrue(TunnelPortForwards.allEnabled(ConnectionEditing.node(ConnectionPolicy.runtime(enabled), "TAIL")))
        assertEquals(ProfileEditor.root(before), ProfileEditor.root(TunnelPortForwards.setAllEnabled(enabled, "TAIL", false)))
        assertEquals(ProfileEditor.view(before).rules, ProfileEditor.view(enabled).rules)
        assertEquals(forwards, TunnelPortForwards.read(ConnectionEditing.node(enabled, "TAIL")))
    }

    @Test fun disabledMappingsStayExcludedInAllPortModeAndReturnToOldRuntimeWhenDisabled() {
        val disabled = TunnelPortForwards.setEnabled(withForwards(), "TAIL", 8022, false)
        val all = TunnelPortForwards.setAllEnabled(disabled, "TAIL", true)
        assertEquals(TunnelPortForwards.read(ConnectionEditing.node(all, "TAIL")),
            TunnelPortForwards.read(ConnectionEditing.node(ConnectionPolicy.runtime(all), "TAIL")))
        val restored = TunnelPortForwards.setAllEnabled(all, "TAIL", false)
        assertEquals(listOf(TunnelPortForward(2222, 8022)),
            TunnelPortForwards.read(ConnectionEditing.node(ConnectionPolicy.runtime(restored), "TAIL")))
        assertFalse(ConnectionPolicy.runtime(ConnectionPolicy.setEnabled(all, "TAIL", false)).contains("listen-all-ports"))
    }

    @Test fun allPortModeSurvivesRenameSubscriptionRefreshAndNewPortEdits() {
        var all = TunnelPortForwards.setAllEnabled(withForwards(), "TAIL", true)
        all = ConnectionEditing.tailscale(all, "TAIL", "NEW", "new-device", "", true)
        all = TunnelPortForwards.update(all, "NEW", null, TunnelPortForward(80, 8080))
        val imported = ClashImport.prepare("""
            proxies: [{name: remote, type: socks5, server: example.invalid, port: 1080}]
            rules: ["MATCH,remote"]
        """.trimIndent())
        val refreshed = ClashImport.apply(all, imported, keepRules = true)
        assertEquals(ConnectionEditing.node(all, "NEW"), ConnectionEditing.node(refreshed, "NEW"))
        assertTrue(TunnelPortForwards.allEnabled(ConnectionEditing.node(refreshed, "NEW")))
        assertEquals("unchanged-identity", ConnectionEditing.node(refreshed, "NEW")["state-dir"])
    }

    @Test fun allPortModeRejectsMalformedFlagsBeforeSavingOrStarting() {
        for (value in listOf<Any?>("true", "false", 1, 0, null)) {
            val node = ConnectionEditing.node(profile, "TAIL").toMutableMap()
            node["listen-all-ports"] = value
            val invalid = ConnectionEditing.replace(profile, "TAIL", node)
            assertThrows(IllegalArgumentException::class.java) { ProfileCheck.check(invalid) }
            assertThrows(IllegalArgumentException::class.java) { ConnectionPolicy.runtime(invalid) }
        }
    }

    @Test fun remoteSubscriptionCannotEnableAllDevicePorts() {
        val incoming = """
            proxies: [{name: remote-tail, type: tailscale, hostname: remote, listen-all-ports: true}]
            rules: ["MATCH,remote-tail"]
        """.trimIndent()
        val preview = ClashImport.prepare(incoming, SubscriptionSource("test", "https://example.invalid/sub", 0))
        assertFalse(TunnelPortForwards.allEnabled(ConnectionEditing.node(preview.yaml, "remote-tail")))
        val local = TunnelPortForwards.setAllEnabled(withForwards(), "TAIL", true)
        val refreshed = ClashImport.apply(local, preview, keepRules = true)
        assertTrue(TunnelPortForwards.allEnabled(ConnectionEditing.node(refreshed, "TAIL")))
        assertFalse(TunnelPortForwards.allEnabled(ConnectionEditing.node(refreshed, "remote-tail")))
    }

    @Test fun wireGuardHasIndependentAllPortFlagThatSurvivesEditingAndConnectionToggle() {
        val conf = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.77.0.2/32
            [Peer]
            PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
            Endpoint = vpn.example.invalid:51820
            AllowedIPs = 10.77.0.0/24
        """.trimIndent()
        val wg = ProfileEditor.importWireGuard(profile, WireGuardImport.parse(conf), "WG", ProfileEditor.WG_OUTBOUND_ONLY)
        val all = TunnelPortForwards.setAllEnabled(wg, "WG", true)
        assertFalse(TunnelPortForwards.allEnabled(ConnectionEditing.node(all, "TAIL")))
        val renamed = ConnectionEditing.wireGuard(all, "WG", "HOME", conf, ProfileEditor.WG_OUTBOUND_ONLY)
        val off = ConnectionPolicy.setEnabled(renamed, "HOME", false)
        assertFalse(ConnectionPolicy.runtime(off).contains("listen-all-ports"))
        val on = ConnectionPolicy.setEnabled(off, "HOME", true)
        assertTrue(TunnelPortForwards.allEnabled(ConnectionEditing.node(ConnectionPolicy.runtime(on), "HOME")))
        ProfileCheck.check(on)
    }

    @Test fun portSwitchPreservesConfigButRemovesOnlyItsListenerFromRuntime() {
        val initial = withForwards()
        assertTrue(TunnelPortForwards.read(ConnectionEditing.node(initial, "TAIL")).all { it.enabled })
        val disabled = TunnelPortForwards.setEnabled(initial, "TAIL", 8022, false)
        assertEquals(listOf(TunnelPortForward(8022, enabled = false), TunnelPortForward(2222, 8022)),
            TunnelPortForwards.read(ConnectionEditing.node(disabled, "TAIL")))
        val runtime = ConnectionPolicy.runtime(disabled)
        assertEquals(listOf(TunnelPortForward(2222, 8022)), TunnelPortForwards.read(ConnectionEditing.node(runtime, "TAIL")))
        assertFalse(runtime.contains("enabled:"))
        assertEquals(ProfileEditor.root(profile).apply { remove("proxies") }, ProfileEditor.root(disabled).apply { remove("proxies") })
        val before = ConnectionEditing.node(initial, "TAIL").toMutableMap().apply { remove("listen-ports") }
        val after = ConnectionEditing.node(disabled, "TAIL").toMutableMap().apply { remove("listen-ports") }
        assertEquals(before, after)
        val allOff = TunnelPortForwards.setEnabled(disabled, "TAIL", 2222, false)
        assertFalse(ConnectionEditing.node(ConnectionPolicy.runtime(allOff), "TAIL").containsKey("listen-ports"))
        assertEquals(2, TunnelPortForwards.read(ConnectionEditing.node(allOff, "TAIL")).size)
        ProfileCheck.check(allOff)
        ProfileCheck.check(ConnectionPolicy.runtime(allOff))
        assertEquals(ProfileEditor.root(initial), ProfileEditor.root(TunnelPortForwards.setEnabled(disabled, "TAIL", 8022, true)))
    }

    @Test fun offStateSurvivesEditingRenamingSubscriptionRefreshAndConnectionToggle() {
        val disabled = TunnelPortForwards.setEnabled(withForwards(), "TAIL", 8022, false)
        val parsed = TunnelPortForwards.parse("8080", "80", enabled = false)
        val edited = TunnelPortForwards.update(disabled, "TAIL", 8022, parsed)
        val renamed = ConnectionEditing.tailscale(edited, "TAIL", "NEW", "device", "", true)
        val onAgain = ConnectionPolicy.setEnabled(ConnectionPolicy.setEnabled(renamed, "NEW", false), "NEW", true)
        val imported = ClashImport.prepare("""
            proxies: [{name: remote, type: socks5, server: example.invalid, port: 1080}]
            rules: ["MATCH,remote"]
        """.trimIndent())
        val refreshed = ClashImport.apply(onAgain, imported, keepRules = true)
        assertEquals(listOf(parsed, TunnelPortForward(2222, 8022)), TunnelPortForwards.read(ConnectionEditing.node(refreshed, "NEW")))
        assertEquals(listOf(TunnelPortForward(2222, 8022)),
            TunnelPortForwards.read(ConnectionEditing.node(ConnectionPolicy.runtime(refreshed), "NEW")))
        assertEquals("unchanged-identity", ConnectionEditing.node(refreshed, "NEW")["state-dir"])
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.update(edited, "TAIL", null, TunnelPortForward(8080)) }
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.setEnabled(edited, "TAIL", 8022, true) }
    }

    @Test fun invalidEnabledValuesCannotSilentlyExposeAListener() {
        for (enabled in listOf<Any?>("false", "true", 0, 1, null)) {
            val node = ConnectionEditing.node(profile, "TAIL").toMutableMap()
            node["listen-ports"] = listOf(mapOf("port" to 80, "enabled" to enabled))
            val edited = ConnectionEditing.replace(profile, "TAIL", node)
            assertThrows(IllegalArgumentException::class.java) { ProfileCheck.check(edited) }
            assertThrows(IllegalArgumentException::class.java) { ConnectionPolicy.runtime(edited) }
        }
    }

    @Test fun blankPlaceholderNeverBecomesAnOpenPort() {
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.parse("", "") }
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.parse("80", "") }
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.parse("", "80") }
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.parse("65536", "80") }
        assertEquals(TunnelPortForward(80), TunnelPortForwards.parse("80", "80"))
    }

    @Test fun dedicatedPageCrudPreservesBothTunnelTypesAndUnrelatedSettings() {
        val wg = ProfileEditor.importWireGuard(profile, WireGuardImport.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.77.0.2/32
            [Peer]
            PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
            Endpoint = vpn.example.invalid:51820
            AllowedIPs = 10.77.0.0/24
        """.trimIndent()), "WG", ProfileEditor.WG_OUTBOUND_ONLY)
        val initial = ConnectionPolicy.setEnabled(wg, "TAIL", false)
        var changed = TunnelPortForwards.update(initial, "TAIL", null, TunnelPortForward(8022))
        changed = TunnelPortForwards.update(changed, "WG", null, TunnelPortForward(80, 8080))
        changed = TunnelPortForwards.update(changed, "TAIL", 8022, TunnelPortForward(2222, 8022))
        assertEquals(listOf(TunnelPortForward(2222, 8022)), TunnelPortForwards.read(ConnectionEditing.node(changed, "TAIL")))
        assertEquals(listOf(TunnelPortForward(80, 8080)), TunnelPortForwards.read(ConnectionEditing.node(changed, "WG")))
        assertFalse(ConnectionPolicy.entries(changed).first { it.name == "TAIL" }.enabled)
        assertTrue(TunnelPortForwards.read(ConnectionEditing.node(ConnectionPolicy.runtime(changed), "WG")).isNotEmpty())
        assertFalse(ConnectionPolicy.runtime(changed).contains("2222"))
        ProfileCheck.check(changed)
        for (name in listOf("TAIL", "WG")) {
            val after = ConnectionEditing.node(changed, name).toMutableMap()
            after.remove("listen-ports")
            assertEquals(ConnectionEditing.node(initial, name), after)
        }
        val beforeRoot = ProfileEditor.root(initial).apply { remove("proxies") }
        val afterRoot = ProfileEditor.root(changed).apply { remove("proxies") }
        assertEquals(beforeRoot, afterRoot)
        changed = TunnelPortForwards.update(changed, "TAIL", 2222, null)
        changed = TunnelPortForwards.update(changed, "WG", 80, null)
        assertEquals(ProfileEditor.root(initial), ProfileEditor.root(changed))
    }

    @Test fun pageRejectsDuplicateAndStaleEditsWithoutOverwritingExistingMappings() {
        val initial = withForwards()
        assertThrows(IllegalArgumentException::class.java) {
            TunnelPortForwards.update(initial, "TAIL", null, TunnelPortForward(8022, 80))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelPortForwards.update(initial, "TAIL", 2222, TunnelPortForward(8022, 80))
        }
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.update(initial, "TAIL", 80, null) }
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.update(initial, "TAIL", 80, TunnelPortForward(80)) }
        val full = (1..16).map { TunnelPortForward(it) }
        val fullProfile = ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "device", "", true, full)
        assertThrows(IllegalArgumentException::class.java) { TunnelPortForwards.update(fullProfile, "TAIL", null, TunnelPortForward(17)) }
        assertEquals(full, TunnelPortForwards.read(ConnectionEditing.node(fullProfile, "TAIL")))
        assertEquals(forwards, TunnelPortForwards.read(ConnectionEditing.node(initial, "TAIL")))
    }

    @Test fun existingProfilesKeepAllInboundPortsClosedByDefault() {
        assertTrue(TunnelPortForwards.read(ConnectionEditing.node(profile, "TAIL")).isEmpty())
        val edited = ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "renamed-device", "", true)
        assertFalse(ConnectionEditing.node(edited, "TAIL").containsKey("listen-ports"))
        ProfileCheck.check(edited)
    }

    @Test fun changingForwardingKeepsIdentityCredentialsAndRouting() {
        val edited = withForwards()
        val before = ConnectionEditing.node(profile, "TAIL")
        val after = ConnectionEditing.node(edited, "TAIL").toMutableMap()
        assertEquals(forwards, TunnelPortForwards.read(after))
        after.remove("listen-ports")
        assertEquals(before, after)
        assertEquals(ProfileEditor.view(profile).rules, ProfileEditor.view(edited).rules)
        assertEquals(ProfileEditor.root(profile)["dns"], ProfileEditor.root(edited)["dns"])
        ProfileCheck.check(edited)
    }

    @Test fun renameAndUnrelatedEditKeepForwardsAndRemovingLastPortClosesThem() {
        val renamed = ConnectionEditing.tailscale(withForwards(), "TAIL", "NEW", "new-device", "", true)
        val node = ConnectionEditing.node(renamed, "NEW")
        assertEquals(forwards, TunnelPortForwards.read(node))
        assertEquals("unchanged-identity", node["state-dir"])
        val removed = ConnectionEditing.tailscale(renamed, "NEW", "NEW", "new-device", "", true, emptyList())
        assertFalse(ConnectionEditing.node(removed, "NEW").containsKey("listen-ports"))
    }

    @Test fun disabledRuntimeRemovesListenerButKeepsSavedConfigForReenable() {
        val edited = withForwards()
        val disabled = ConnectionPolicy.setEnabled(edited, "TAIL", false)
        assertEquals(forwards, TunnelPortForwards.read(ConnectionEditing.node(disabled, "TAIL")))
        val runtime = ProfileEditor.root(ConnectionPolicy.runtime(disabled))
        assertTrue((runtime["proxies"] as List<*>).none { (it as Map<*, *>)["type"] == "tailscale" })
        assertFalse(ConnectionPolicy.runtime(disabled).contains("listen-ports"))
        val enabled = ConnectionPolicy.runtime(ConnectionPolicy.setEnabled(disabled, "TAIL", true))
        assertEquals(forwards, TunnelPortForwards.read(ConnectionEditing.node(enabled, "TAIL")))
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
        assertEquals(listOf(TunnelPortForward(8022)),
            TunnelPortForwards.read(mapOf("listen-ports" to listOf(mapOf("port" to 8022L)))))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionEditing.tailscale(profile, "TAIL", "TAIL", "device", "", true, listOf(TunnelPortForward(8022, -1)))
        }
    }
}
