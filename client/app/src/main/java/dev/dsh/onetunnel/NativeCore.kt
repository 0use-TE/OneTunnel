// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

// Loaded ONLY by the dedicated :vpn process after the user explicitly starts it.
object NativeCore {
    init { System.loadLibrary("onetunnel") }
    external fun start(home: String, config: String, fd: Int, owner: TunnelService): String
    external fun stop()
    external fun prepareTailscale(home: String, config: String, name: String): String
    external fun tailscale(name: String, action: String): String
    external fun reload(config: String): String
    external fun prepare(home: String, config: String): String
    external fun clash(action: String, group: String, target: String, generation: String): String
}
