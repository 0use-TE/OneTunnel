package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun enablingOrDisablingRecoveryNeverImpliesAConnectionStartOrStop() {
        val idle = RecoveryPolicy.configure(RecoveryState(), true, false)
        assertTrue(idle.enabled); assertFalse(idle.requested)
        assertFalse(RecoveryPolicy.sticky(idle, false, false))
        val running = RecoveryPolicy.configure(idle, true, true)
        assertTrue(RecoveryPolicy.sticky(running, false, false))
        val disabled = RecoveryPolicy.configure(running, false, true)
        assertTrue(disabled.requested); assertFalse(disabled.enabled)
        assertFalse(RecoveryPolicy.sticky(disabled, false, false))
        assertFalse(RecoveryPolicy.begin(disabled, StartOrigin.RESTORE, false, true, 100).allowed)
    }

    @Test fun manualStopCancelsOnlyThisSessionAndManualStartRearmsIt() {
        val connected = RecoveryPolicy.begin(RecoveryState(enabled = true), StartOrigin.USER, false, true, 100).state
        val stopped = RecoveryPolicy.stop(connected)
        assertTrue(stopped.enabled); assertFalse(stopped.requested)
        assertFalse(RecoveryPolicy.begin(stopped, StartOrigin.RESTORE, false, true, 200).allowed)
        val restarted = RecoveryPolicy.begin(stopped, StartOrigin.USER, false, true, 300)
        assertTrue(restarted.allowed); assertTrue(RecoveryPolicy.sticky(restarted.state, false, false))
    }

    @Test fun revokedPermissionPreventsRecoveryAndDoesNotRearmAConnection() {
        val running = RecoveryState(enabled = true, requested = true)
        for (origin in listOf(StartOrigin.USER, StartOrigin.RESTORE, StartOrigin.SYSTEM)) {
            val result = RecoveryPolicy.begin(running, origin, true, false, 100)
            assertFalse(result.allowed); assertFalse(result.state.requested)
        }
    }

    @Test fun systemStartRequiresAlwaysOnAndIsIndependentOfAppRecoveryPreference() {
        assertFalse(RecoveryPolicy.begin(RecoveryState(), StartOrigin.SYSTEM, false, true, 100).allowed)
        val start = RecoveryPolicy.begin(RecoveryState(), StartOrigin.SYSTEM, true, true, 100)
        assertTrue(start.allowed); assertTrue(start.state.requested); assertFalse(start.state.enabled)
        assertTrue(RecoveryPolicy.sticky(start.state, true, false))
        assertTrue(RecoveryPolicy.begin(start.state, StartOrigin.RESTORE, true, true, 200).allowed)
    }

    @Test fun authenticationOnlySessionNeverBecomesARecoverableVpn() {
        val result = RecoveryPolicy.begin(RecoveryState(enabled = true, requested = true), StartOrigin.AUTH, false, false, 100)
        assertTrue(result.allowed); assertFalse(result.state.requested)
        assertFalse(RecoveryPolicy.sticky(result.state, false, true))
        assertFalse(RecoveryPolicy.sticky(result.state, true, true))
    }

    @Test fun crashLoopIsBoundedAndBackoffIncreases() {
        var state = RecoveryState(enabled = true, requested = true, window = 100)
        for (index in 1..3) {
            val result = RecoveryPolicy.begin(state, StartOrigin.RESTORE, false, true, 100L + index)
            assertTrue(result.allowed); assertEquals(index * 2000L, result.delay)
            state = result.state
        }
        val fourth = RecoveryPolicy.begin(state, StartOrigin.RESTORE, false, true, 200)
        assertFalse(fourth.allowed); assertTrue(fourth.state.blocked); assertFalse(fourth.state.requested)
        assertFalse(RecoveryPolicy.begin(fourth.state, StartOrigin.SYSTEM, true, true, 300).allowed)
        assertTrue(RecoveryPolicy.begin(fourth.state, StartOrigin.USER, true, true, 400).allowed)
    }

    @Test fun startupFailureDoesNotRetryUntilTheUserExplicitlyConnects() {
        val failed = RecoveryPolicy.stop(RecoveryState(enabled = true, requested = true), failed = true)
        assertFalse(RecoveryPolicy.sticky(failed, true, false))
        assertFalse(RecoveryPolicy.begin(failed, StartOrigin.RESTORE, true, true, 100).allowed)
        val enabledAgain = RecoveryPolicy.configure(failed, true, false)
        assertTrue(enabledAgain.blocked)
        val retried = RecoveryPolicy.begin(failed, StartOrigin.USER, false, true, 100)
        assertTrue(retried.allowed); assertFalse(retried.state.blocked)
    }

    @Test fun oldCrashWindowOrRebootClockDoesNotConsumeTheNewRetryBudget() {
        val state = RecoveryState(enabled = true, requested = true, attempts = 3, window = 1000)
        for (now in listOf(500L, 400_000L)) {
            val result = RecoveryPolicy.begin(state, StartOrigin.RESTORE, false, true, now)
            assertTrue(result.allowed); assertEquals(1, result.state.attempts); assertEquals(now, result.state.window)
        }
    }
}
