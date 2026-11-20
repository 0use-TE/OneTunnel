// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

data class TunnelStatus(val state: String = "idle", val message: String = "未连接", val alwaysOn: Boolean = false) {
    val active: Boolean get() = state in listOf("preparing", "starting", "running", "stopping", "auth-starting", "auth")
}
class StatusStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "tunnel-status.json"))
    fun write(state: String, message: String, alwaysOn: Boolean = false) {
        val bytes = JSONObject().put("state", state).put("message", message.take(700))
            .put("pid", Process.myPid()).put("elapsed", SystemClock.elapsedRealtime()).put("alwaysOn", alwaysOn)
            .toString().toByteArray()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (_: Exception) { file.failWrite(output) }
        TunnelSurfaces.refresh(context)
    }
    fun read(): TunnelStatus = runCatching {
        if (!file.baseFile.exists()) return TunnelStatus()
        val obj = JSONObject(file.readFully().toString(Charsets.UTF_8))
        val status = TunnelStatus(obj.getString("state"), obj.getString("message"), obj.optBoolean("alwaysOn"))
        if (status.active) {
            require(obj.getLong("elapsed") <= SystemClock.elapsedRealtime())
            Os.kill(obj.getInt("pid"), 0)
        }
        status
    }.getOrElse { TunnelStatus("idle", "服务未运行") }
}
