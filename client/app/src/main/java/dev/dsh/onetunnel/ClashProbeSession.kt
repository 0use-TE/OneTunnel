// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import java.util.ArrayDeque

/** Main-thread batch lifecycle; a core generation can contain many separate tests. */
internal class ClashProbeSession {
    data class Probe(val batch: Long, val generation: String, val group: String, val target: String)

    private var batch = 0L
    private val queue = ArrayDeque<Probe>()
    private val active = mutableSetOf<Probe>()
    private val retiring = mutableSetOf<Probe>()
    var awaitingCancellation = false; private set
    val cancelling get() = awaitingCancellation || retiring.isNotEmpty()
    val testing get() = active.map { it.target }.toSet()
    val busy get() = active.isNotEmpty() || queue.isNotEmpty()

    fun start(generation: String, group: String, names: List<String>): Boolean {
        if (busy || cancelling) return false
        batch++
        names.distinct().filter { it.isNotBlank() }.forEach { queue.add(Probe(batch, generation, group, it)) }
        return true
    }

    fun next(): Probe? {
        if (cancelling || active.size >= 4 || queue.isEmpty()) return null
        return queue.removeFirst().also { active.add(it) }
    }

    fun finish(probe: Probe): Boolean {
        if (probe.batch != batch) { retiring.remove(probe); return false }
        return active.remove(probe)
    }

    /** Clear UI immediately, then wait for native cancellation before another batch. */
    fun cancel(): Set<String> {
        val unfinished = (active + queue).map { it.target }.toSet()
        if (active.isNotEmpty()) awaitingCancellation = true
        retiring.addAll(active)
        active.clear()
        queue.clear()
        batch++
        return unfinished
    }

    fun cancellationFinished() { awaitingCancellation = false }

    fun reset() { cancel(); retiring.clear(); cancellationFinished() }

    companion object {
        // Native URLTest uses a five-second deadline; allow two seconds for IPC.
        const val RESPONSE_TIMEOUT_MS = 7_000L
    }
}
