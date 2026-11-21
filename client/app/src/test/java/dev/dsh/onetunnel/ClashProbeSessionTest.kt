// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class ClashProbeSessionTest {
    @Test fun watchdogFollowsEachNodesTimeoutAndKeepsItAcrossStateRefreshes() {
        val session = ClashProbeSession()
        val nodes = mapOf("slow" to ClashNode("slow", "SOCKS5", timeoutMs = 15_000, probeHost = "test.invalid"),
            "bounded" to ClashNode("bounded", "SOCKS5", timeoutMs = Int.MAX_VALUE))
        session.start("core", "group", listOf("slow", "bounded", "default"), nodes)
        val slow = requireNotNull(session.next())
        assertEquals(17_000L, slow.responseTimeoutMs)
        assertEquals("test.invalid", slow.host)
        assertEquals(32_000L, session.next()?.responseTimeoutMs)
        assertEquals(7_000L, session.next()?.responseTimeoutMs)
        assertEquals(15_000, slow.timeoutMs)
    }

    @Test fun labelsDistinguishProbeFailuresFromControlFailuresAndCancellation() {
        assertEquals("DNS 失败", ClashDelay("failed", reason = "dns").label)
        assertEquals("TLS 失败", ClashDelay("failed", reason = "tls").label)
        assertEquals("响应不符", ClashDelay("failed", reason = "status").label)
        assertEquals("请求失败", ClashDelay("failed", reason = "control").label)
        assertEquals("探测失败", ClashDelay("failed").label)
        assertEquals("探测超时", ClashDelay("timeout").label)
        assertEquals("请求超时", ClashDelay("timeout", reason = "ipc").label)
        assertEquals("已停止", ClashDelay("cancelled").label)
    }

    @Test fun limitsConcurrencyAndFinishingATimedOutNodeAdvancesTheQueue() {
        val session = ClashProbeSession()
        assertTrue(session.start("core", "group", (1..6).map { "node$it" }))
        val active = List(4) { requireNotNull(session.next()) }
        assertNull(session.next())
        assertEquals(4, session.testing.size)
        assertFalse(session.start("core", "group", listOf("other")))
        assertTrue(session.finish(active[0])) // A response or the UI watchdog frees the slot.
        assertEquals("node5", session.next()?.target)
        assertFalse(session.finish(active[0])) // Duplicate response cannot free another slot.
        assertNull(session.next())
        assertEquals(7_000L, active[0].responseTimeoutMs)
    }

    @Test fun stopClearsActiveAndQueuedLabelsBeforeNativeAcknowledges() {
        val session = ClashProbeSession()
        session.start("core", "group", listOf("one", "two", "three"))
        val first = requireNotNull(session.next())
        val second = requireNotNull(session.next())
        assertEquals(setOf("one", "two", "three"), session.cancel())
        assertFalse(session.busy)
        assertTrue(session.testing.isEmpty())
        assertNull(session.next())
        assertTrue(session.cancelling)
        assertFalse(session.start("core", "group", listOf("one")))
        session.cancellationFinished()
        assertTrue(session.cancelling) // Acknowledgement may precede the old probe replies.
        assertFalse(session.finish(first))
        assertFalse(session.finish(second))
        assertFalse(session.cancelling)
        assertTrue(session.start("core", "group", listOf("one")))
    }

    @Test fun repliesBeforeCancellationAcknowledgementCannotRestartTooSoon() {
        val session = ClashProbeSession()
        session.start("core", "group", listOf("one"))
        val old = requireNotNull(session.next())
        session.cancel()
        assertFalse(session.finish(old))
        assertTrue(session.cancelling)
        assertFalse(session.start("core", "group", listOf("one")))
        session.cancellationFinished()
        assertFalse(session.cancelling)
    }

    @Test fun cancelledResultsCannotFinishANewTestOfTheSameNodeInTheSameCoreGeneration() {
        val session = ClashProbeSession()
        session.start("core", "group", listOf("one"))
        val old = requireNotNull(session.next())
        session.cancel()
        session.cancellationFinished()
        session.finish(old) // Simulates the seven-second retirement watchdog.
        assertTrue(session.start("core", "group", listOf("one")))
        val next = requireNotNull(session.next())
        assertNotEquals(old.batch, next.batch)
        assertFalse(session.finish(old)) // Late reply after watchdog expiry.
        assertEquals(setOf("one"), session.testing)
        assertTrue(session.finish(next))
        assertFalse(session.busy)
    }

    @Test fun repeatedStopDoesNotForgetOutstandingCancellation() {
        val session = ClashProbeSession()
        session.start("core", "first", listOf("one"))
        val old = requireNotNull(session.next())
        session.cancel()
        assertTrue(session.cancel().isEmpty())
        assertTrue(session.awaitingCancellation)
        session.finish(old)
        assertTrue(session.cancelling)
        session.cancellationFinished()
        assertTrue(session.start("new-core", "second", listOf("two")))
        assertEquals("second", session.next()?.group)
    }

    @Test fun disconnectResetsPendingCancellationAndOldRepliesStayStale() {
        val session = ClashProbeSession()
        session.start("core", "first", listOf("one"))
        val old = requireNotNull(session.next())
        session.cancel()
        session.reset()
        assertFalse(session.cancelling)
        assertFalse(session.busy)
        assertTrue(session.start("core", "second", listOf("one", "one", "")))
        val next = requireNotNull(session.next())
        assertNull(session.next())
        assertFalse(session.finish(old))
        assertEquals(setOf("one"), session.testing)
        assertTrue(session.finish(next))
    }
}
