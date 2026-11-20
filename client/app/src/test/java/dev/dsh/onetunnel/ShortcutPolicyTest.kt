package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ShortcutPolicyTest {
    @Test fun tappingConnectedShortcutDisconnectsRatherThanStartingAgain() {
        val running = TunnelStatus("running")
        assertEquals(ShortcutAction.DISCONNECT, ShortcutPolicy.action(running))
        assertEquals("点击断开", ShortcutPolicy.hint(running))
        assertTrue(ShortcutPolicy.active(running)); assertTrue(ShortcutPolicy.enabled(running))
        for (state in listOf("idle", "error", "authenticated")) {
            assertEquals(ShortcutAction.CONNECT, ShortcutPolicy.action(TunnelStatus(state)))
            assertEquals("点击连接", ShortcutPolicy.hint(TunnelStatus(state)))
        }
    }
    @Test fun startupCanBeCancelledFromTheShortcut() {
        for (state in listOf("preparing", "starting")) {
            val status = TunnelStatus(state)
            assertEquals(ShortcutAction.DISCONNECT, ShortcutPolicy.action(status))
            assertEquals("点击取消", ShortcutPolicy.hint(status))
            assertTrue(ShortcutPolicy.enabled(status)); assertTrue(ShortcutPolicy.active(status))
        }
        assertFalse(ShortcutPolicy.enabled(TunnelStatus("stopping")))
    }
    @Test fun authenticationIsOpenedWithoutStartingAVpnAndAlwaysOnUsesSystemSettings() {
        for (state in listOf("auth", "auth-starting")) {
            assertEquals(ShortcutAction.OPEN, ShortcutPolicy.action(TunnelStatus(state)))
            assertFalse(ShortcutPolicy.active(TunnelStatus(state)))
        }
        assertEquals(ShortcutAction.VPN_SETTINGS, ShortcutPolicy.action(TunnelStatus("running", alwaysOn = true)))
        assertEquals(ShortcutAction.VPN_SETTINGS, ShortcutPolicy.action(TunnelStatus("preparing", alwaysOn = true)))
        assertEquals("系统 VPN 设置", ShortcutPolicy.hint(TunnelStatus("running", alwaysOn = true)))
        assertEquals(ShortcutAction.CONNECT, ShortcutPolicy.action(TunnelStatus("error", alwaysOn = true)))
    }
}
