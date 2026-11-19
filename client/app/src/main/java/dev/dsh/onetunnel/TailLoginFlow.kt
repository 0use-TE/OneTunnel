// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

/** Only an explicit login gesture may open a browser, including URLs delivered by later polls. */
internal class TailLoginFlow {
    private val waiting = mutableSetOf<String>()
    fun begin(name: String) { waiting.add(name) }
    fun cancel(name: String) { waiting.remove(name) }
    fun receive(name: String, state: String, url: String): Boolean {
        if (state in listOf("Running", "NeedsMachineAuth")) { waiting.remove(name); return false }
        if (url.isBlank()) return false
        return waiting.remove(name)
    }
    fun clear() { waiting.clear() }
}
