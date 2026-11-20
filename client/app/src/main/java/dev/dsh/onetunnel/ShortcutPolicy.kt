// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

enum class ShortcutAction { CONNECT, DISCONNECT, OPEN }

object ShortcutPolicy {
    fun action(status: TunnelStatus): ShortcutAction = when {
        status.state in listOf("running", "preparing", "starting") -> ShortcutAction.DISCONNECT
        status.active -> ShortcutAction.OPEN
        else -> ShortcutAction.CONNECT
    }
    fun enabled(status: TunnelStatus) = status.state != "stopping"
    fun active(status: TunnelStatus) = status.state in listOf("running", "preparing", "starting")
    fun hint(status: TunnelStatus): String = when (action(status)) {
        ShortcutAction.DISCONNECT -> if (status.state == "running") "点击断开" else "点击取消"
        ShortcutAction.OPEN -> if (status.state == "stopping") "正在断开" else "打开认证"
        ShortcutAction.CONNECT -> "点击连接"
    }
}
