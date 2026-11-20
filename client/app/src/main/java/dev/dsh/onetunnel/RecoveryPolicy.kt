// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

data class RecoveryState(val enabled: Boolean = false, val requested: Boolean = false,
    val attempts: Int = 0, val window: Long = 0, val blocked: Boolean = false)

enum class StartOrigin { USER, SYSTEM, RESTORE, AUTH }
data class RecoveryDecision(val allowed: Boolean, val state: RecoveryState, val delay: Long = 0)

object RecoveryPolicy {
    private const val WINDOW = 5 * 60_000L
    fun configure(state: RecoveryState, enabled: Boolean, running: Boolean) =
        state.copy(enabled = enabled, requested = state.requested || running)
    fun stop(state: RecoveryState, failed: Boolean = false) =
        state.copy(requested = false, attempts = 0, window = 0, blocked = failed)
    fun sticky(state: RecoveryState, alwaysOn: Boolean, authOnly: Boolean) =
        !authOnly && !state.blocked && (alwaysOn || (state.enabled && state.requested))

    fun begin(state: RecoveryState, origin: StartOrigin, alwaysOn: Boolean, authorized: Boolean, now: Long): RecoveryDecision {
        if (origin == StartOrigin.AUTH) return RecoveryDecision(true, stop(state))
        if (!authorized) return RecoveryDecision(false, stop(state))
        if (origin == StartOrigin.USER) return RecoveryDecision(true,
            state.copy(requested = true, attempts = 0, window = now, blocked = false))
        if (!alwaysOn && (origin != StartOrigin.RESTORE || !state.enabled || !state.requested))
            return RecoveryDecision(false, state)
        if (state.blocked) return RecoveryDecision(false, state)
        val count = if (now < state.window || now - state.window >= WINDOW) 0 else state.attempts
        if (count >= 3) return RecoveryDecision(false, stop(state, failed = true))
        return RecoveryDecision(true, state.copy(requested = true, attempts = count + 1,
            window = if (count == 0) now else state.window), delay = if (!state.requested) 0 else (count + 1) * 2000L)
    }
}
