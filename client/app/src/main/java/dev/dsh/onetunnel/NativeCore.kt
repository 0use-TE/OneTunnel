// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

// Loaded ONLY by the dedicated :vpn process after the user explicitly starts it.
object NativeCore {
    init { System.loadLibrary("onetunnel") }
    external fun start(home: String, config: String, fd: Int, owner: TunnelService): String
    external fun stop()
}
