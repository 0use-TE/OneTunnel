// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Contains only lifecycle flags. File lock + AtomicFile serialize UI and :vpn writers. */
class RecoveryStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "recovery.json"))
    private val lockFile = File(context.noBackupFilesDir, "recovery.lock")
    fun read(): RecoveryState = locked { load() }
    fun configure(enabled: Boolean, running: Boolean): RecoveryState = change { RecoveryPolicy.configure(it, enabled, running) }
    fun stop(failed: Boolean = false): RecoveryState = change { RecoveryPolicy.stop(it, failed) }
    fun begin(origin: StartOrigin, alwaysOn: Boolean, authorized: Boolean): RecoveryDecision = locked {
        val decision = RecoveryPolicy.begin(load(), origin, alwaysOn, authorized, SystemClock.elapsedRealtime())
        save(decision.state); decision
    }
    fun stable() = change { if (it.requested) it.copy(attempts = 0, window = SystemClock.elapsedRealtime()) else it }
    private fun change(block: (RecoveryState) -> RecoveryState): RecoveryState = locked { block(load()).also(::save) }
    private fun <T> locked(block: () -> T): T = synchronized(localLock) {
        RandomAccessFile(lockFile, "rw").use { handle -> handle.channel.lock().use { block() } }
    }
    private fun load(): RecoveryState = runCatching {
        if (!file.baseFile.exists()) return@runCatching RecoveryState()
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        RecoveryState(json.getBoolean("enabled"), json.getBoolean("requested"),
            json.optInt("attempts").coerceIn(0, 3), json.optLong("window"), json.optBoolean("blocked"))
    }.getOrDefault(RecoveryState())
    private fun save(state: RecoveryState) {
        val bytes = JSONObject().put("enabled", state.enabled).put("requested", state.requested)
            .put("attempts", state.attempts).put("window", state.window).put("blocked", state.blocked)
            .toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    companion object { private val localLock = Any() }
}
