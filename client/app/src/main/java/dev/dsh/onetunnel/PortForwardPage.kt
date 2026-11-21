// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class ForwardTarget(val connection: String, val port: Int? = null)

@Composable internal fun PortForwardPage(profile: String, enabled: Boolean, message: String,
    addConnection: () -> Unit, save: (String) -> Unit, modifier: Modifier = Modifier) {
    val entries = remember(profile) { ConnectionPolicy.entries(profile) }
    val ports = remember(profile) { entries.associate { entry ->
        entry.name to TunnelPortForwards.read(ConnectionEditing.node(profile, entry.name))
    } }
    val allPorts = remember(profile) { entries.associate { entry ->
        entry.name to TunnelPortForwards.allEnabled(ConnectionEditing.node(profile, entry.name))
    } }
    var editing by remember { mutableStateOf<ForwardTarget?>(null) }
    var deleting by remember { mutableStateOf<ForwardTarget?>(null) }
    var error by remember { mutableStateOf("") }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                val count = allPorts.values.count { it }
                Text(if (count > 0) "全部端口 · $count 个连接" else "${ports.values.sumOf { it.size }} 条转发",
                    modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                FilledTonalButton(onClick = { editing = ForwardTarget(entries.first { ports[it.name].orEmpty().size < 16 }.name) },
                    enabled = enabled && entries.any { ports[it.name].orEmpty().size < 16 }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加")
                }
            }
        }
        if (message.isNotBlank() || error.isNotBlank()) item { Note(error.ifBlank { message }, error = true) }
        if (entries.isEmpty()) item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("先添加 WireGuard 或 Tailscale 连接", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = addConnection, enabled = enabled) { Text("添加连接") }
                }
            }
        }
        items(entries, key = { it.name }) { entry ->
            val mappings = ports[entry.name].orEmpty()
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (entry.type == "wireguard") "WireGuard" else "Tailscale") +
                                if (entry.enabled) "" else " · 已关闭", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = ForwardTarget(entry.name) }, enabled = enabled && mappings.size < 16) {
                            Icon(Icons.Default.Add, "为 ${entry.name} 添加转发")
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全部端口转发", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = allPorts[entry.name] == true, enabled = enabled, onCheckedChange = { checked ->
                            runCatching { TunnelPortForwards.setAllEnabled(profile, entry.name, checked) }
                                .onSuccess { error = ""; save(it) }
                                .onFailure { error = it.message ?: "无法切换全部端口转发。" }
                        }, modifier = Modifier.semantics { contentDescription = "${entry.name} 的全部 TCP 和 UDP 端口转发" })
                    }
                    if (mappings.isEmpty() && allPorts[entry.name] != true)
                        Text("暂无转发", Modifier.padding(8.dp, 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    mappings.forEach { mapping -> key(mapping.port) {
                        var menu by remember { mutableStateOf(false) }
                        val target = ForwardTarget(entry.name, mapping.port)
                        Surface(onClick = { editing = target }, enabled = enabled,
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${mapping.port} → ${mapping.localPort}", Modifier.weight(1f).padding(end = 8.dp),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (mapping.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Switch(checked = mapping.enabled, enabled = enabled, onCheckedChange = { checked ->
                                    runCatching { TunnelPortForwards.setEnabled(profile, entry.name, mapping.port, checked) }
                                        .onSuccess { error = ""; save(it) }
                                        .onFailure { error = it.message ?: "无法切换转发。" }
                                }, modifier = Modifier.semantics {
                                    contentDescription = "${entry.name} 的端口 ${mapping.port} 转发"
                                })
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = enabled) {
                                        Icon(Icons.Default.MoreVert, "端口 ${mapping.port} 的操作")
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { menu = false; editing = target }, enabled = enabled)
                                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { menu = false; deleting = target }, enabled = enabled)
                                    }
                                }
                            }
                        }
                    } }
                }
            }
        }
    }
    editing?.let { target ->
        val original = ports[target.connection].orEmpty().firstOrNull { it.port == target.port }
        key(target) { ForwardEditDialog(entries, ports, target, original, enabled, { editing = null }) { name, value ->
            val next = TunnelPortForwards.update(profile, name, target.port, value)
            error = ""; save(next); editing = null
        } }
    }
    deleting?.let { target ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除端口 ${target.port}？") },
            text = { Text(target.connection) },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                runCatching { TunnelPortForwards.update(profile, target.connection, target.port, null) }
                    .onSuccess { error = ""; save(it); deleting = null }
                    .onFailure { error = it.message ?: "无法删除转发。"; deleting = null }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable private fun ForwardEditDialog(entries: List<ConnectionEntry>, ports: Map<String, List<TunnelPortForward>>,
    target: ForwardTarget, original: TunnelPortForward?, enabled: Boolean, dismiss: () -> Unit,
    save: (String, TunnelPortForward) -> Unit) {
    var connection by remember { mutableStateOf(target.connection) }
    var port by remember { mutableStateOf(original?.port?.toString().orEmpty()) }
    var local by remember { mutableStateOf(original?.localPort?.toString().orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (target.port == null) "添加转发" else "编辑转发") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = enabled && target.port == null,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) {
                    Text(connection, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (target.port == null) Icon(Icons.Default.ArrowDropDown, "选择连接")
                }
                DropdownMenu(menu, { menu = false }) { entries.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.name) }, enabled = enabled && ports[entry.name].orEmpty().size < 16,
                        onClick = { connection = entry.name; menu = false; error = "" })
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it; error = "" }, label = { Text("入站端口") }, placeholder = { Text("80") },
                    enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(local, { local = it; error = "" }, label = { Text("本机端口") }, placeholder = { Text("80") },
                    enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
            }
            if (error.isNotBlank()) Note(error, error = true)
        }
    }, confirmButton = { TextButton(enabled = enabled, onClick = {
        runCatching { save(connection, TunnelPortForwards.parse(port, local, original?.enabled ?: true)) }
            .onFailure { error = it.message ?: "无法保存转发。" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
