// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// Credentials stay in memory; deliberately no generated toString or saved-state fields.
private class PeerFields(value: Map<*, *>) {
    var publicKey by mutableStateOf(value["public-key"]?.toString().orEmpty())
    var psk by mutableStateOf(value["pre-shared-key"]?.toString().orEmpty())
    var endpoint by mutableStateOf(value["server"]?.toString()?.let { host ->
        "${if (':' in host) "[$host]" else host}:${value["port"] ?: 51820}"
    }.orEmpty())
    var allowed by mutableStateOf((value["allowed-ips"] as? List<*>)?.joinToString(", ") ?: "0.0.0.0/0, ::/0")
}

@Composable private fun EditField(label: String, value: String, change: (String) -> Unit, secret: Boolean = false, reveal: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(autoCorrect = false),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable internal fun WireGuardEditDialog(profile: String, originalName: String, dismiss: () -> Unit, remove: () -> Unit, save: (String) -> Unit) {
    val node = remember { ConnectionEditing.node(profile, originalName) }
    var name by remember { mutableStateOf(originalName) }
    var privateKey by remember { mutableStateOf(node["private-key"]?.toString().orEmpty()) }
    var address by remember { mutableStateOf(listOfNotNull(node["ip"], node["ipv6"]).joinToString(", ") { value ->
        val ip = value.toString(); if ('/' in ip) ip else "$ip/${if (':' in ip) 128 else 32}"
    }) }
    var dns by remember { mutableStateOf((node["dns"] as? List<*>)?.joinToString(", ").orEmpty()) }
    var mtu by remember { mutableStateOf(node["mtu"]?.toString().orEmpty()) }
    var keepalive by remember { mutableStateOf(node["persistent-keepalive"]?.toString() ?: "0") }
    var reveal by remember { mutableStateOf(false) }
    val peers = remember { mutableStateListOf<PeerFields>().apply {
        val originals = node["peers"] as? List<*>
        if (originals.isNullOrEmpty()) add(PeerFields(node)) else originals.forEach { add(PeerFields(it as Map<*, *>)) }
    } }
    var mode by remember { mutableStateOf(when {
        ProfileEditor.view(profile).match == originalName -> ProfileEditor.WG_MATCH
        ProfileEditor.view(profile).rules.any { it.startsWith("IP-CIDR") && ClashImport.target(it) == originalName } -> ProfileEditor.WG_ROUTES
        else -> ProfileEditor.WG_OUTBOUND_ONLY
    }) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("编辑 WireGuard") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditField("名称", name, { name = it })
            EditField("本机地址", address, { address = it })
            EditField("私钥", privateKey, { privateKey = it }, true, reveal)
            ToggleRow("显示密钥", reveal, true) { reveal = it }
            EditField("DNS", dns, { dns = it })
            EditField("MTU", mtu, { mtu = it })
            EditField("Keepalive（秒）", keepalive, { keepalive = it })
            peers.forEachIndexed { index, peer -> key(peer) {
                HorizontalDivider()
                Text("Peer ${index + 1}", style = MaterialTheme.typography.titleMedium)
                EditField("公钥", peer.publicKey, { peer.publicKey = it })
                EditField("Endpoint", peer.endpoint, { peer.endpoint = it })
                EditField("AllowedIPs", peer.allowed, { peer.allowed = it })
                EditField("预共享密钥", peer.psk, { peer.psk = it }, true, reveal)
                if (peers.size > 1) TextButton(onClick = { peers.remove(peer) }) { Text("移除 Peer") }
            } }
            TextButton(onClick = { peers.add(PeerFields(emptyMap<String, Any>())) }) { Text("添加 Peer") }
            val modes = mapOf(ProfileEditor.WG_ROUTES to "按网段分流", ProfileEditor.WG_OUTBOUND_ONLY to "仅添加出口", ProfileEditor.WG_MATCH to "用于其他流量")
            Box {
                TextButton(onClick = { expanded = true }) { Text("${modes[mode]} ▾") }
                DropdownMenu(expanded, { expanded = false }) { modes.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { mode = value; expanded = false })
                } }
            }
            if (error.isNotBlank()) Note(error, error = true)
            DeleteConnectionButton(remove)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val conf = buildString {
                appendLine("[Interface]"); appendLine("PrivateKey = $privateKey"); appendLine("Address = $address")
                if (dns.isNotBlank()) appendLine("DNS = $dns")
                if (mtu.isNotBlank()) appendLine("MTU = $mtu")
                peers.forEach { peer ->
                    appendLine("[Peer]"); appendLine("PublicKey = ${peer.publicKey}"); appendLine("Endpoint = ${peer.endpoint}")
                    appendLine("AllowedIPs = ${peer.allowed}"); appendLine("PersistentKeepalive = $keepalive")
                    if (peer.psk.isNotBlank()) appendLine("PresharedKey = ${peer.psk}")
                }
            }
            save(ConnectionEditing.wireGuard(profile, originalName, name, conf, mode))
        }.onFailure { error = if (it is IllegalArgumentException) it.message ?: "配置无效。" else "无法修改连接。" }
    }) { Text("完成") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable internal fun TailscaleEditDialog(profile: String, originalName: String, dismiss: () -> Unit,
    remove: () -> Unit, save: (String) -> Unit, status: @Composable (Boolean) -> Unit) {
    val node = remember { ConnectionEditing.node(profile, originalName) }
    var name by remember { mutableStateOf(originalName) }
    var hostname by remember { mutableStateOf(node["hostname"]?.toString() ?: "onetunnel-android") }
    var control by remember { mutableStateOf(node["control-url"]?.toString().orEmpty()) }
    var routes by remember { mutableStateOf(node["accept-routes"] == true) }
    val initialPorts = remember { TailscalePortForwards.read(node) }
    val ports = remember { mutableStateListOf<TailscalePortFields>().apply { initialPorts.forEach { add(TailscalePortFields(it)) } } }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("编辑 Tailscale") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditField("名称", name, { name = it }); EditField("设备名", hostname, { hostname = it })
            EditField("Headscale 地址（可选）", control, { control = it })
            ToggleRow("接受子网路由", routes, true) { routes = it }
            HorizontalDivider()
            Text("本机端口转发", style = MaterialTheme.typography.titleMedium)
            ports.forEach { port -> key(port) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port.port, { port.port = it }, label = { Text("入站端口") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(port.local, { port.local = it }, label = { Text("本机端口") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { ports.remove(port) }) { Text("移除端口") }
            } }
            TextButton(onClick = { ports.add(TailscalePortFields(TailscalePortForward(8022))) }, enabled = ports.size < 16) { Text("添加端口") }
            if (control.trim() != node["control-url"]?.toString().orEmpty()) Note("更换服务后需要重新登录。")
            status(name != originalName || hostname != (node["hostname"]?.toString() ?: "onetunnel-android") ||
                control != node["control-url"]?.toString().orEmpty() || routes != (node["accept-routes"] == true) ||
                ports.map { it.port to it.local } != initialPorts.map { it.port.toString() to it.localPort.toString() })
            if (error.isNotBlank()) Note(error, error = true)
            DeleteConnectionButton(remove)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val forwards = ports.map {
                TailscalePortForward(requireNotNull(it.port.toIntOrNull()) { "入站端口需为 1–65535 的整数。" },
                    requireNotNull(it.local.toIntOrNull()) { "本机端口需为 1–65535 的整数。" })
            }
            save(ConnectionEditing.tailscale(profile, originalName, name, hostname, control, routes, forwards))
        }
            .onFailure { error = if (it is IllegalArgumentException) it.message ?: "配置无效。" else "无法修改连接。" }
    }) { Text("完成") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

private class TailscalePortFields(value: TailscalePortForward) {
    var port by mutableStateOf(value.port.toString())
    var local by mutableStateOf(value.localPort.toString())
}

@Composable internal fun DeleteConnectionButton(remove: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = remove, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除连接") }
}
