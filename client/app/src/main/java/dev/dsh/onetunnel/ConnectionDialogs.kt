// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun SubscriptionDialog(busy: Boolean, message: String, dismiss: () -> Unit, initial: SubscriptionSource? = null, remove: (() -> Unit)? = null, fetch: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { ConnectionEditorTitle(if (initial == null) "添加订阅" else "编辑订阅", remove, !busy) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("订阅名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, enabled = !busy, label = { Text("订阅链接") }, singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") } })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank() || message.isNotBlank()) Note(error.ifBlank { message }, error = true)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            require(name.trim().length in 1..64) { "请填写 1–64 个字符的订阅名称。" }
            val validated = SubscriptionClient.validate(url)
            error = ""; fetch(name.trim(), validated)
        }.onFailure { error = it.message ?: "链接无效。" }
    }, enabled = !busy) { Text(if (initial == null) "下载" else "完成") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable internal fun ClashPreviewDialog(preview: ClashPreview, hasCurrent: Boolean, isSelected: Boolean,
    canActivate: Boolean, busy: Boolean, message: String, dismiss: () -> Unit, apply: (Boolean, Boolean) -> Unit) {
    var activate by remember { mutableStateOf(canActivate && (!preview.refresh || isSelected)) }
    var keepRules by remember { mutableStateOf(preview.refresh && hasCurrent) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(preview.source?.name ?: "导入 Clash 配置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${preview.nodes} 个节点 · ${preview.groups} 个代理组\n${preview.rules} 条规则")
            if (preview.source != null) ToggleRow("设为当前配置", activate, canActivate && !busy) { activate = it }
            if (hasCurrent && (activate || preview.source == null)) ToggleRow("保留当前分流规则", keepRules, !busy) { keepRules = it }
            if (!canActivate) Note("正在处理连接，请稍后应用。")
            preview.warnings.forEach { Note(it) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Note(message, error = true)
        }
    }, confirmButton = { TextButton(onClick = { apply(preview.source == null || activate, keepRules) }, enabled = !busy && (canActivate || (preview.source != null && !activate))) { Text(if (preview.source != null) "保存订阅" else "导入") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable internal fun TailscaleSetupDialog(dismiss: () -> Unit, add: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("TAILNET") }
    var hostname by remember { mutableStateOf("onetunnel-android") }
    var control by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("添加 Tailscale") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("连接名称") }, singleLine = true)
            OutlinedTextField(hostname, { hostname = it }, label = { Text("此设备名称") }, singleLine = true)
            OutlinedTextField(control, { control = it }, label = { Text("Headscale 地址（可选）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false))
            if (error.isNotBlank()) Note(error, error = true)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { add(name, hostname, control) }.onFailure { error = if (it is IllegalArgumentException) it.message ?: "配置无效。" else "添加失败。" }
    }) { Text("添加") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable internal fun TailscaleCard(entries: List<TailscaleEntry>, snapshots: Map<String, TailSnapshot>,
    tunnelState: String, dirty: Boolean, busy: Boolean, connected: Boolean, error: String,
    login: (String) -> Unit, refresh: (String) -> Unit, open: (TailscaleEntry, String) -> Unit) {
    Panel {
        entries.forEach { entry ->
            val snapshot = snapshots[entry.name]
            var expanded by remember(entry.name) { mutableStateOf(false) }
            val online = snapshot?.peers.orEmpty().filter { it.online }
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text(when (snapshot?.state) {
                "Running" -> if (tunnelState == "running") "已连接 Tailnet" else "已完成认证"
                "NeedsLogin" -> "需要登录"; "NeedsMachineAuth" -> "等待管理员批准"; "Starting" -> "正在连接"
                "Stopped" -> "已停止"; else -> "尚未查询状态"
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot?.ips?.isNotEmpty() == true) Text(snapshot.ips.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot?.state !in listOf("Running", "NeedsMachineAuth"))
                    TextButton(onClick = { login(entry.name) }, enabled = !busy && !dirty && tunnelState !in listOf("preparing", "starting", "stopping", "auth-starting")) { Text("浏览器认证") }
                if (connected) TextButton(onClick = { refresh(entry.name) }, enabled = !busy) { Text("刷新状态") }
                if (snapshot?.authUrl?.isNotBlank() == true) TextButton(onClick = { open(entry, snapshot.authUrl) }) { Text("打开登录页") }
            }
            TextButton(onClick = { expanded = !expanded }) { Text("在线设备（${online.size}） ${if (expanded) "▴" else "▾"}") }
            if (expanded) online.forEach { peer ->
                Text("${peer.name}  ${peer.ips.joinToString()}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (tunnelState == "authenticated") Note("已登录")
        if (error.isNotBlank()) Note(error, error = true)
    }
}
