// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

enum class ShortcutAction { CONNECT, DISCONNECT, VPN_SETTINGS, OPEN }

object ShortcutPolicy {
    fun action(status: TunnelStatus): ShortcutAction = when {
        status.alwaysOn && status.active -> ShortcutAction.VPN_SETTINGS
        status.state in listOf("running", "preparing", "starting") -> ShortcutAction.DISCONNECT
        status.active -> ShortcutAction.OPEN
        else -> ShortcutAction.CONNECT
    }
    fun enabled(status: TunnelStatus) = status.state != "stopping"
    fun active(status: TunnelStatus) = status.state in listOf("running", "preparing", "starting")
    fun hint(status: TunnelStatus): String = when (action(status)) {
        ShortcutAction.VPN_SETTINGS -> "系统 VPN 设置"
        ShortcutAction.DISCONNECT -> if (status.state == "running") "点击断开" else "点击取消"
        ShortcutAction.OPEN -> if (status.state == "stopping") "正在断开" else "打开认证"
        ShortcutAction.CONNECT -> "点击连接"
    }
}
