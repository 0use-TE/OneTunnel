// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val foreground = mutableStateOf(false)
    private val permissionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startTunnel()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }
    override fun onResume() { super.onResume(); foreground.value = true }
    override fun onPause() { foreground.value = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OneTunnelScreen() }
    }
    private fun startTunnel() {
        runCatching { startForegroundService(Intent(this, TunnelService::class.java).setAction(TunnelService.START)) }
            .onFailure { StatusStore(this).write("error", "系统拒绝启动服务，请检查前台服务权限") }
    }
    private fun requestVpn() {
        runCatching {
            val request = VpnService.prepare(this)
            if (request == null) startTunnel() else permissionResult.launch(request)
        }.onFailure { StatusStore(this).write("error", "无法申请 VPN 权限") }
    }
    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestVpn()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun OneTunnelScreen() {
        val scope = rememberCoroutineScope()
        var tab by remember { mutableIntStateOf(0) }
        var text by remember { mutableStateOf("") } // Never put plaintext profiles in saved-state bundles.
        var saved by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf<ProfileSummary?>(null) }
        var status by remember { mutableStateOf(TunnelStatus()) }
        var message by remember { mutableStateOf("尚未导入配置。打开 App 不会启动 VPN。") }
        var busy by remember { mutableStateOf(false) }
        var confirmStart by remember { mutableStateOf(false) }
        var pendingTemplate by remember { mutableStateOf<String?>(null) }
        val dirty = text != saved
        val store = remember { ConfigStore(this) }
        LaunchedEffect(Unit) {
            busy = true
            runCatching { withContext(Dispatchers.IO) { store.load() } }
                .onSuccess {
                    text = it; saved = it
                    summary = runCatching { ProfileCheck.check(it) }.getOrNull()
                    if (it.isNotBlank()) message = "已读取本机加密配置，未建立任何连接。"
                }.onFailure { message = "安全配置读取失败，未覆盖原文件。请重新导入；不要清除数据以免丢失配置。" }
            busy = false
        }
        LaunchedEffect(foreground.value) {
            while (foreground.value) {
                status = withContext(Dispatchers.IO) { StatusStore(this@MainActivity).read() }
                delay(1000)
            }
        }
        DisposableEffect(tab) {
            if (tab == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                require(bytes.size() + n <= ConfigStore.MAX_BYTES)
                                bytes.write(buffer, 0, n)
                            }
                            bytes.toString("UTF-8")
                        } ?: error("No stream")
                    }
                }.onSuccess { text = it; message = "已导入编辑器，尚未保存或启动；请检查密钥与规则。"; tab = 1 }
                    .onFailure { message = "读取失败或文件超过 1 MiB；没有更改已保存配置。" }
                busy = false
            }
        }
        val colors = lightColorScheme(primary = Color(0xFF166957), background = Color(0xFFF2F5F4), surface = Color.White)
        MaterialTheme(colorScheme = colors) {
            Scaffold(
                containerColor = colors.background,
                topBar = { TopAppBar(title = { Column { Text("一隧", fontWeight = FontWeight.Bold); Text("Mihomo · WG · Tailscale", fontSize = 12.sp, color = Color(0xFF627671)) } }, actions = { Text("0.1 ALPHA", modifier = Modifier.padding(20.dp), color = colors.primary, fontSize = 12.sp) }) },
                bottomBar = {
                    NavigationBar {
                        listOf("总览", "配置", "说明").forEachIndexed { index, title ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "≡", "ⓘ")[index], fontSize = 22.sp) }, label = { Text(title) })
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when (tab) {
                            0 -> {
                                Surface(color = Color(0xFF183C36), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(if (status.state == "running") "一个入口，按规则抵达" else "一个 VPN，多个出口", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                        Text(status.message, color = Color(0xFFBDE6D3), fontSize = 15.sp)
                                        Text("ARM64 · 原生 Mihomo Meta 0455d0d", color = Color(0xFF92B7A8), fontSize = 12.sp)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    StatCard("Mihomo", "${summary?.nodes ?: 0} 个内联出站", Modifier.weight(1f))
                                    StatCard("WireGuard", "${summary?.wireguard ?: 0} 个配置", Modifier.weight(1f))
                                    StatCard("Tailscale", "${summary?.tailscale ?: 0} 个配置", Modifier.weight(1f))
                                }
                                InfoCard("配置状态", if (saved.isBlank()) "请先导入自己的 Mihomo YAML，或查看示例。这里的数量只反映配置，不代表已连接。" else "${summary?.rules ?: 0} 条规则 · 配置已在本机加密保存。这里的数量只反映内联配置，不包含 provider 内的节点，也不代表已连接。")
                                if (dirty) InfoCard("有未保存修改", "回到配置页保存后才能启动。保存不会联网，也不会切换 VPN。")
                                Button(onClick = { confirmStart = true }, enabled = !busy && !status.active && saved.isNotBlank() && !dirty && summary != null, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                                    Text("手动启动统一 VPN", fontSize = 17.sp)
                                }
                                OutlinedButton(onClick = {
                                    runCatching { startService(Intent(this@MainActivity, TunnelService::class.java).setAction(TunnelService.STOP)) }
                                        .onFailure { message = "停止请求失败，请在系统 VPN 设置中断开。" }
                                }, enabled = status.active, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("停止统一 VPN") }
                                InfoCard("你掌握切换时机", "启动会替换 Clash / WG / Tailscale 的现有 VPN，可能立即中断 GPT。没有自动启动、自动重连或后台测速。安装和打开本 App 都不会主动接管网络。")
                            }
                            1 -> {
                                InfoCard("完整 YAML 配置", "导入自己的 Mihomo 配置后，可同时添加 wireguard 和 tailscale 出站，再用 rules 分流。编辑页禁止截图，配置不写入备份或详细日志。")
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/x-yaml", "application/yaml", "application/octet-stream")) }, enabled = !busy && !status.active) { Text("导入 YAML") }
                                    TextButton(onClick = { pendingTemplate = "unified-example.yaml" }, enabled = !busy && !status.active) { Text("三出口示例") }
                                    TextButton(onClick = { pendingTemplate = "direct.yaml" }, enabled = !busy && !status.active) { Text("直连模板") }
                                }
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { if (it.toByteArray().size <= ConfigStore.MAX_BYTES) text = it else message = "配置不能超过 1 MiB。" },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 620.dp),
                                    enabled = !busy && !status.active,
                                    label = { Text("Mihomo YAML · 密钥在此可见，请勿外传") },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            busy = true
                                            runCatching { withContext(Dispatchers.Default) { ProfileCheck.check(text) } }
                                                .onSuccess { message = "离线语法/结构检查通过：${it.nodes} 个内联出站、${it.rules} 条规则。不是节点有效性或连通性验证。" }
                                                .onFailure { message = it.message ?: "语法检查失败" }
                                            busy = false
                                        }
                                    }, enabled = !busy && !status.active) { Text("离线检查") }
                                    Button(onClick = {
                                        scope.launch {
                                            busy = true
                                            runCatching {
                                                val parsed = withContext(Dispatchers.Default) { ProfileCheck.check(text) }
                                                withContext(Dispatchers.IO) { store.save(text) }
                                                parsed
                                            }.onSuccess { summary = it; saved = text; message = "已通过离线检查并加密保存。没有连接或切换 VPN。" }
                                                .onFailure { message = if (it is IllegalArgumentException) it.message ?: "配置检查失败" else "加密保存失败，未覆盖已有有效配置。" }
                                            busy = false
                                        }
                                    }, enabled = !busy && !status.active && text.isNotBlank()) { Text("加密保存") }
                                }
                                InfoCard("边界", "第一版强制使用规则模式，并接管 TUN 与 DNS；忽略导入配置中的本地代理端口、控制接口、其他入站和系统路由操作。不提供网页控制台、节点切换 UI、订阅管理 UI 或按 App 规则。")
                            }
                            else -> {
                                InfoCard("数据路径", "安卓应用 → 一个 VpnService/TUN → Mihomo rules → 普通代理 / 原生 WireGuard / 内嵌 Tailscale / DIRECT。不是启动三个原有 App。")
                                InfoCard("WireGuard", "在 YAML 添加 type: wireguard，填写自己的服务端、地址和密钥，再把目标网段路由到它。不需要 root 或 WireGuard App。")
                                InfoCard("Tailscale", "核心已包含 tsnet、TCP/UDP、accept-routes、exit-node 和 tailscale://名称 DNS。第一版建议使用你自己生成的 auth-key；没有可视化浏览器登录或设备列表。首次使用会注册一个新的 tailnet 节点，不会复用原 Tailscale App 身份。")
                                InfoCard("域名与网段", "WG / Wi-Fi / tailnet 子网不能靠名字自动去重，重叠网段要明确优先级。MagicDNS 和 WG 内网域名需要 nameserver-policy。现有连接不会无损迁移出口。")
                                InfoCard("手动测试与恢复", "仅你主动确认后才申请 VPN。请先确保可手动恢复原 Clash；再分别验证公网代理、WG 内网、tailnet TCP/UDP、DNS 和 IPv6。若失联，停止本 App，再手动启动原 Clash。本版尚未做设备连通性测试，不可视为生产级客户端。")
                                InfoCard("安全与开源", "配置使用 Android Keystore AES-GCM；Tailscale 状态在不备份的应用私有目录。没有广告、遥测、自动开机或原配置读取。项目 GPL-3.0-or-later；Mihomo 和第三方许可随源码提供。修改 targetSdk/升级依赖后需重新验证。")
                            }
                        }
                        if (message.isNotBlank()) InfoCard("操作提示", message)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (confirmStart) AlertDialog(
                onDismissRequest = { confirmStart = false },
                title = { Text("准备好替换当前 VPN 了吗？") },
                text = { Text("这不是并行开启第二个 VPN。继续申请授权或启动后，Clash / WG / Tailscale 可能立即断开，GPT 对话也可能失联。\n\n第一版仅编译验证，连通性需要你手动测试。请先确认已备好有效配置，并知道如何恢复原 Clash。") },
                confirmButton = { TextButton(onClick = { confirmStart = false; requestStart() }) { Text("我已准备好，手动切换") } },
                dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("保留当前 VPN") } }
            )
            pendingTemplate?.let { name -> AlertDialog(
                onDismissRequest = { pendingTemplate = null },
                title = { Text("替换编辑器内容？") },
                text = { Text("只替换当前编辑内容，不会覆盖已保存配置或启动 VPN。三出口示例必须替换所有占位符；直连模板没有翻墙能力。") },
                confirmButton = { TextButton(onClick = {
                    text = assets.open(name).bufferedReader().use { it.readText() }
                    message = "已加载示例，尚未保存。请先替换占位符并审阅路由。"
                    pendingTemplate = null
                }) { Text("加载示例") } },
                dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text("取消") } }
            ) }
        }
    }
}

@Composable private fun InfoCard(title: String, text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = Color(0xFF5D706B), fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
@Composable private fun StatCard(title: String, detail: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(detail, fontSize = 12.sp, color = Color(0xFF647A70))
        }
    }
}
