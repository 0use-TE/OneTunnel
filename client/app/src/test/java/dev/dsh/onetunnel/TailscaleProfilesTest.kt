package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class TailscaleProfilesTest {
    @Test fun addsLoginReadyNodeRoutesAndMagicDns() {
        val profile = TailscaleProfiles.add("", "TAILNET", "onetunnel-android", "")
        assertEquals(1, ProfileCheck.check(profile).tailscale)
        assertEquals("onetunnel-android", TailscaleProfiles.list(profile).single().hostname)
        assertTrue(profile.contains("tailscale://TAILNET"))
        assertFalse(profile.contains("auth-key"))
        assertTrue(ProfileEditor.view(profile).rules.any { it.startsWith("IP-CIDR,100.64.0.0/10,TAILNET") })
        assertThrows(IllegalArgumentException::class.java) { TailscaleProfiles.add(profile, "TAILNET", "device", "") }
    }
    @Test fun loginUrlsMustMatchConfiguredIdentityProvider() {
        assertEquals("https://login.tailscale.com/a/test", TailscaleProfiles.loginUrl("https://login.tailscale.com/a/test", ""))
        assertEquals("https://head.example.com/register/test", TailscaleProfiles.loginUrl("https://head.example.com/register/test", "https://head.example.com"))
        listOf("https://login.tailscale.com.evil.example/a/test", "http://login.tailscale.com/a/test", "javascript:alert(1)", "https://user@login.tailscale.com/a/test").forEach {
            assertThrows(IllegalArgumentException::class.java) { TailscaleProfiles.loginUrl(it, "") }
        }
        assertThrows(IllegalArgumentException::class.java) { TailscaleProfiles.loginUrl("https://login.tailscale.com/a/test", "https://head.example.com") }
    }
}
