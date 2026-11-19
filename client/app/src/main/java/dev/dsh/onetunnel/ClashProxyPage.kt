// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable internal fun ClashProxyPage(profile: String, title: String, activeSource: Boolean, live: Boolean,
    enabled: Boolean, foreground: Boolean, controller: ClashControl, activate: () -> Unit,
    selectOffline: (String, String) -> Unit, manage: () -> Unit, update: (() -> Unit)?, modifier: Modifier = Modifier) {
    val configured = remember(profile) { runCatching { ClashProfiles.configuredGroups(profile) }.getOrDefault(emptyList()) }
    var groupName by remember(title) { mutableStateOf(configured.firstOrNull()?.name.orEmpty()) }
    var search by remember(title) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val runtime = controller.state?.takeIf { live && it.group == groupName }
    val groups = configured.map { config -> runtime?.groups?.firstOrNull { it.name == config.name } ?: config }
    val group = groups.firstOrNull { it.name == groupName }
    val nodes = runtime?.nodes ?: remember(profile, groupName) { runCatching { ClashProfiles.nodes(profile, groupName) }.getOrDefault(emptyList()) }
    val visible = nodes.filter { it.name.contains(search, ignoreCase = true) }
    val available = enabled && !controller.selecting && (!live || runtime != null)
    val testable = available && live && controller.connected
    val list = rememberLazyGridState()
    LaunchedEffect(configured.map { it.name }) { if (configured.none { it.name == groupName }) groupName = configured.firstOrNull()?.name.orEmpty() }
    DisposableEffect(Unit) { onDispose { controller.unbind() } }
    LaunchedEffect(live, foreground, groupName) {
        if (!live || !foreground) { controller.unbind(); return@LaunchedEffect }
        controller.cancelTests()
        list.scrollToItem(0)
        while (live && foreground) { controller.bind(); controller.refresh(groupName); delay(3000) }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), state = list, modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }, enabled = enabled) { Text("管理") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (update == null) "配置管理" else "编辑订阅") }, onClick = { menu = false; manage() })
                        if (update != null) DropdownMenuItem(text = { Text("更新订阅") }, onClick = { menu = false; update() })
                    }
                }
            }
        }
        if (!activeSource) item(span = { GridItemSpan(maxLineSpan) }) {
            Button(onClick = activate, enabled = enabled) { Text("启用此订阅") }
        }
        else if (!live) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("未连接 · 选择将在启动后生效", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.name }) { item -> FilterChip(selected = groupName == item.name,
                    onClick = { groupName = item.name; search = "" }, label = { Text(item.name) }) }
            }
        }
        if (group != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (live && runtime == null) "读取中" else if (!live && !group.manual) "连接后显示当前节点"
                            else if (live) ClashProfiles.selectionLabel(group, runtime?.groups.orEmpty()) else group.now,
                            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        if (controller.testingBatch) controller.cancelTests()
                        else controller.test(groupName, nodes.map { it.name })
                    }, enabled = testable) { Text(if (controller.testingBatch) "停止测速" else "测试本组") }
                }
                OutlinedTextField(search, { search = it }, label = { Text("搜索节点 · ${nodes.size}") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        if (controller.error.isNotBlank() && live) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(controller.error, color = MaterialTheme.colorScheme.error)
        }
        if (configured.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("此配置没有代理组。") }
        else if (nodes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (live) "代理组暂无节点，请检查订阅或 Provider" else "连接后加载 Provider 节点")
        }
        items(visible, key = { it.name }) { node ->
            val selected = node.name == group?.now && ((live && runtime != null) || (!live && group?.manual == true))
            val measured = controller.delays[node.name].takeIf { live }
            val label = when {
                live && node.name in controller.testing -> "测速中"
                measured?.status == "ok" -> "${measured.millis} ms"
                measured?.status == "timeout" -> "超时"
                measured != null -> "失败"
                live && node.delay != null && node.delay > 0 -> "${node.delay} ms"
                live && node.delay == 0 -> "失败"
                else -> "未测试"
            }
            val color = MaterialTheme.colorScheme
            Surface(onClick = {
                if (live) controller.select(groupName, node.name) else selectOffline(groupName, node.name)
            }, enabled = available && group?.manual == true && activeSource,
                shape = RoundedCornerShape(16.dp), color = if (selected) color.primaryContainer else color.surface,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color.primary else color.outlineVariant)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 124.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text((if (selected) "✓  " else "") + node.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f), color = if (measured?.status in listOf("failed", "timeout")) color.error else color.onSurfaceVariant)
                        TextButton(onClick = { controller.test(groupName, listOf(node.name)) },
                            enabled = testable && !controller.testingBatch, contentPadding = PaddingValues(0.dp)) { Text("测速") }
                    }
                    if (groups.any { it.name == node.name }) TextButton(onClick = { groupName = node.name; search = "" },
                        contentPadding = PaddingValues(0.dp)) { Text("进入代理组") }
                }
            }
        }
    }
}
