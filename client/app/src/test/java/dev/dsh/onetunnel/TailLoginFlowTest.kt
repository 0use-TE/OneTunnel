package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class TailLoginFlowTest {
    @Test fun delayedUrlFromStatusOpensBrowserOnlyOnceAfterExplicitLogin() {
        val flow = TailLoginFlow()
        assertFalse(flow.receive("TAIL", "NeedsLogin", "https://login.tailscale.com/test"))
        flow.begin("TAIL")
        assertFalse(flow.receive("TAIL", "NeedsLogin", ""))
        assertTrue(flow.receive("TAIL", "NeedsLogin", "https://login.tailscale.com/test"))
        assertFalse(flow.receive("TAIL", "NeedsLogin", "https://login.tailscale.com/test"))
    }
    @Test fun completedOrCancelledLoginDoesNotReopenBrowser() {
        val flow = TailLoginFlow()
        flow.begin("TAIL")
        assertFalse(flow.receive("TAIL", "Running", ""))
        assertFalse(flow.receive("TAIL", "NeedsLogin", "https://login.tailscale.com/test"))
        flow.begin("TAIL"); flow.cancel("TAIL")
        assertFalse(flow.receive("TAIL", "NeedsLogin", "https://login.tailscale.com/test"))
    }
    @Test fun separateIdentitiesCannotConsumeAnotherLoginRequest() {
        val flow = TailLoginFlow()
        flow.begin("A")
        assertFalse(flow.receive("B", "NeedsLogin", "https://login.tailscale.com/test"))
        assertTrue(flow.receive("A", "NeedsLogin", "https://login.tailscale.com/test"))
        flow.begin("A"); flow.clear()
        assertFalse(flow.receive("A", "NeedsLogin", "https://login.tailscale.com/test"))
    }
}
