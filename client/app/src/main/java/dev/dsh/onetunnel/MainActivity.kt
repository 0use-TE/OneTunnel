// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private val foreground = mutableStateOf(false)
    private val permissionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startTunnel()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }
    override fun onResume() { super.onResume(); foreground.value = true }
    override fun onPause() { foreground.value = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { App() } }
    private fun startTunnel() {
        runCatching { startForegroundService(Intent(this, TunnelService::class.java).setAction(TunnelService.START)) }
            .onFailure { StatusStore(this).write("error", "系统拒绝启动服务，请检查前台服务权限") }
    }
    private fun requestVpn() {
        runCatching { VpnService.prepare(this).let { if (it == null) startTunnel() else permissionResult.launch(it) } }
            .onFailure { StatusStore(this).write("error", "无法申请 VPN 权限") }
    }
    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestVpn()
    }
    private fun stopTunnel() {
        runCatching { TunnelActions.stop(this) }
            .onFailure { StatusStore(this).write("error", "停止请求失败，请在系统 VPN 设置中断开") }
    }
    private fun lanAddresses(): List<String> = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.filter { network -> cm.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true }.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .map { it.address }.filter { it.isSiteLocalAddress && it.address.size == 4 }
            .mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        val scope = rememberCoroutineScope()
        val prefs = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
        var theme by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
        val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
        val colors = if (dark) darkColorScheme(
            primary = Color(0xFF9AC2FF), onPrimary = Color(0xFF102F57),
            primaryContainer = Color(0xFF213F67), onPrimaryContainer = Color(0xFFD8E7FF),
            secondary = Color(0xFFAEC6E7), secondaryContainer = Color(0xFF2D435F), onSecondaryContainer = Color(0xFFD8E7FF),
            background = Color(0xFF101419), surface = Color(0xFF191F27),
            surfaceVariant = Color(0xFF25303E), onSurfaceVariant = Color(0xFFAFBAC9)
        ) else lightColorScheme(
            primary = Color(0xFF285EA8), onPrimary = Color.White,
            primaryContainer = Color(0xFFE1ECFF), onPrimaryContainer = Color(0xFF102F57),
            secondary = Color(0xFF425F84), secondaryContainer = Color(0xFFE4ECF8), onSecondaryContainer = Color(0xFF173651),
            background = Color(0xFFF5F7FA), surface = Color.White,
            surfaceVariant = Color(0xFFE8EEF7), onSurfaceVariant = Color(0xFF626F80)
        )
        SideEffect {
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
        var tab by remember { mutableIntStateOf(0) }
        var advanced by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") } // Never save plaintext credentials to instance state.
        var saved by remember { mutableStateOf("") }
        var status by remember { mutableStateOf(TunnelStatus()) }
        var busy by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf("") }
        var confirmStart by remember { mutableStateOf(false) }
        var pendingChange by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
        var template by remember { mutableStateOf<String?>(null) }
        var showPassword by remember { mutableStateOf(false) }
        var addRule by remember { mutableStateOf(false) }
        var editingRule by remember { mutableStateOf<Pair<Int, String>?>(null) }
        var sortingRules by remember { mutableStateOf<List<String>?>(null) }
        var visibleRuleLimit by remember { mutableIntStateOf(50) }
        var wgPreview by remember { mutableStateOf<WireGuardProfile?>(null) }
        var clashPreview by remember { mutableStateOf<ClashPreview?>(null) }
        var subscriptionDialog by remember { mutableStateOf(false) }
        var addConnection by remember { mutableStateOf(false) }
        var addTailscale by remember { mutableStateOf(false) }
        var editingConnection by remember { mutableStateOf<ConnectionEntry?>(null) }
        var editingClashFile by remember { mutableStateOf(false) }
        var clashPage by remember { mutableStateOf(false) }
        var clashPageId by remember { mutableStateOf("") }
        var clashPageSource by remember { mutableStateOf<ClashPreview?>(null) }
        var editingSubscription by remember { mutableStateOf<ClashPreview?>(null) }
        var pendingDeletion by remember { mutableStateOf<Pair<String, String?>?>(null) }
        var subscriptions by remember { mutableStateOf<List<SubscriptionEntry>>(emptyList()) }
        var pendingTailLogin by remember { mutableStateOf<String?>(null) }
        var authTarget by remember { mutableStateOf<String?>(null) }
        val store = remember { ConfigStore(this) }
        val library = remember { SubscriptionLibrary(this) }
        val core = remember { CoreControl(this) }
        val clash = remember { ClashControl(this) { done -> scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.load() } }
                .onSuccess { text = it; saved = it }.onFailure { message = "代理选择已保存，请返回后重新打开配置。" }
            done()
        } } }
        val dirty = text != saved
        val view = remember(text, advanced) { if (advanced) null else runCatching { ProfileEditor.view(text) }.getOrNull() }
        val summary = remember(saved) { runCatching { ProfileCheck.check(saved) }.getOrNull() }
        val editable = !busy && !status.active
        val addresses = remember(tab, status.state, foreground.value) { lanAddresses() }
        val selectedSubscription = remember(text, advanced) { if (advanced) null else ClashImport.subscription(text) }
        val matchOptions = remember(text) { runCatching { ConnectionEditing.matchOptions(text) }.getOrDefault(emptyMap()) }
        val ruleOptions = remember(text) { runCatching { ClashProfiles.ruleOptions(text) }.getOrDefault(emptyMap()) }
        val connections = remember(text) { ConnectionPolicy.entries(text) }
        val canToggle = !busy && !dirty && !core.updating && !clash.selecting && status.state !in listOf("preparing", "starting", "stopping", "auth", "auth-starting")
        val tailscaleEntries = remember(text, advanced) { if (advanced) emptyList() else TailscaleProfiles.list(text) }
        fun openTailLogin(entry: TailscaleEntry, url: String) {
            runCatching {
                val validated = TailscaleProfiles.loginUrl(url, entry.controlUrl)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(validated)))
            }.onFailure { message = if (it is IllegalArgumentException) it.message ?: "登录地址无效。" else "无法打开浏览器。" }
        }
        fun fetchSubscription(name: String, url: String, refresh: Boolean = false) {
            scope.launch {
                busy = true; message = ""
                runCatching { withContext(Dispatchers.IO) {
                    ClashImport.prepare(SubscriptionClient.fetch(url), SubscriptionSource(name, url, System.currentTimeMillis()), refresh)
                } }.onSuccess { clashPreview = it; subscriptionDialog = false }
                    .onFailure { message = if (it is IllegalArgumentException) it.message ?: "订阅格式无效。" else "订阅下载失败，原配置未更改。" }
                busy = false
            }
        }
        fun loginTailscale(name: String) {
            if (dirty || busy || status.state in listOf("preparing", "starting", "stopping", "auth-starting")) return
            if (status.state == "running" && ConnectionPolicy.entries(saved).none { it.name == name && it.enabled }) {
                message = "请先开启此 Tailscale 连接。"; return
            }
            runCatching {
                pendingTailLogin = name; authTarget = name
                if (status.state in listOf("running", "auth")) return
                startForegroundService(Intent(this@MainActivity, TunnelService::class.java).setAction(TunnelService.AUTH).putExtra("name", name))
                status = TunnelStatus("auth-starting", "正在准备 Tailscale 登录，不接管 VPN")
            }.onFailure { pendingTailLogin = null; message = "无法启动登录服务，请检查后台服务权限。" }
        }
        fun persist(next: String, afterSave: (suspend () -> Unit)? = null) {
            if (busy || core.updating || clash.selecting || status.state in listOf("preparing", "starting", "stopping", "auth", "auth-starting")) {
                message = "正在处理连接，请稍后再试。"; return
            }
            busy = true; message = ""
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Default) {
                        ProfileCheck.check(next)
                        ProfileCheck.check(ConnectionPolicy.runtime(next))
                    }
                    withContext(Dispatchers.IO) { store.save(next) }
                }.onSuccess {
                    text = next; saved = next
                    if (status.state == "running") core.reloadSavedProfile()
                    if (afterSave != null) runCatching { afterSave() }.onFailure { message = "配置已保存，订阅列表删除失败，请重试。" }
                }.onFailure { message = if (it is IllegalArgumentException) it.message ?: "配置无效。" else "保存失败，原配置未覆盖。" }
                busy = false
            }
        }
        fun applyChange(next: String) {
            val conflicts = WireGuardConflicts.find(next) - WireGuardConflicts.find(saved)
            if (conflicts.isNotEmpty()) pendingChange = next to conflicts else persist(next)
        }
        fun update(block: () -> String) {
            runCatching(block).onSuccess { applyChange(it) }
                .onFailure { message = it.message ?: "配置无法修改。" }
        }
        fun previewWireGuard(value: String) {
            runCatching { WireGuardImport.parse(value) }
                .onSuccess { wgPreview = it; message = "" }
                .onFailure { message = if (it is IllegalArgumentException) it.message ?: "WG 配置无效。" else "WG 配置无法读取。" }
        }
        BackHandler(advanced) { advanced = false }
        BackHandler(clashPage) { clashPage = false }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { store.load() } }
                .onSuccess { text = it; saved = it }
                .onFailure { message = "加密配置读取失败，请重新导入；原文件未覆盖。" }
            runCatching { withContext(Dispatchers.IO) { library.list() } }.onSuccess { subscriptions = it }
                .onFailure { message = "订阅列表读取失败，原文件未覆盖。" }
            busy = false
        }
        LaunchedEffect(text, advanced) {
            if (!advanced || busy || status.active || text == saved) return@LaunchedEffect
            delay(450)
            if (runCatching { ProfileCheck.check(text) }.isSuccess) applyChange(text)
        }
        DisposableEffect(Unit) { onDispose { core.unbind(); clash.unbind(force = true) } }
        LaunchedEffect(core.reloadFailed) { if (core.reloadFailed && status.state == "running") stopTunnel() }
        LaunchedEffect(status.active, foreground.value, core.updating) {
            if (!status.active || (!foreground.value && !core.updating)) { core.unbind(); return@LaunchedEffect }
            while (status.active && (foreground.value || core.updating)) {
                core.bind()
                if (core.connected && pendingTailLogin == null) {
                    val names = if (status.state in listOf("auth", "auth-starting")) listOfNotNull(authTarget)
                        else ConnectionPolicy.entries(saved).filter { it.type == "tailscale" && it.enabled }.map { it.name }
                    names.forEach { core.request(it, "status") }
                }
                delay(3000)
            }
        }
        LaunchedEffect(core.connected, pendingTailLogin) {
            val name = pendingTailLogin
            if (name != null) {
                while (core.connected && pendingTailLogin == name) {
                    if (core.request(name, "login")) { pendingTailLogin = null; break }
                    delay(200)
                }
            }
        }
        LaunchedEffect(core.browserRequest, foreground.value) {
            val request = core.browserRequest
            if (foreground.value && request != null) {
                core.consumeBrowserRequest()
                TailscaleProfiles.list(saved).firstOrNull { it.name == request.first }?.let { openTailLogin(it, request.second) }
            }
        }
        LaunchedEffect(foreground.value) {
            if (foreground.value) TunnelSurfaces.refresh(this@MainActivity)
            while (foreground.value) {
                status = withContext(Dispatchers.IO) { StatusStore(this@MainActivity).read() }
                delay(1000)
            }
        }
        DisposableEffect(advanced, tab, wgPreview != null, subscriptionDialog, clashPreview != null, addTailscale, editingConnection, editingSubscription, clashPage) {
            if (advanced || clashPage || tab == 3 || wgPreview != null || subscriptionDialog || clashPreview != null || addTailscale || editingConnection != null || editingSubscription != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val imported = contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                require(bytes.size() + n <= ConfigStore.MAX_BYTES) { "配置不能超过 1 MiB。" }
                                bytes.write(buffer, 0, n)
                            }
                            bytes.toString("UTF-8")
                        } ?: error("读取失败")
                        ClashImport.prepare(imported)
                    }
                }.onSuccess { clashPreview = it; message = "" }
                    .onFailure { message = if (it is IllegalArgumentException) it.message ?: "配置检查失败" else "导入失败，原配置未更改。" }
                busy = false
            }
        }
        val wgFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(out.size() + count <= WireGuardImport.MAX_BYTES) { "WG 配置不能超过 64 KiB。" }
                                out.write(buffer, 0, count)
                            }
                            out.toString("UTF-8")
                        } ?: error("无法读取配置")
                    }
                }.onSuccess { previewWireGuard(it) }
                    .onFailure { message = if (it is IllegalArgumentException) it.message ?: "WG 配置无效。" else "WG 文件读取失败。" }
                busy = false
            }
        }
        val wgImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) { QrImport.fromImage(this@MainActivity, uri) } }
                    .onSuccess { previewWireGuard(it) }
                    .onFailure { message = if (it is IllegalArgumentException) it.message ?: "二维码读取失败。" else "图片读取失败。" }
                busy = false
            }
        }
        val wgScan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val value = ScanInbox.take()
            if (result.resultCode == Activity.RESULT_OK && value != null) previewWireGuard(value)
            else message = "扫描已取消或相机权限未开启，可改用二维码图片导入。"
        }
        MaterialTheme(colorScheme = colors) {
            Scaffold(containerColor = colors.background,
                topBar = { TopAppBar(
                    title = { Text(if (clashPage) "Clash 代理" else if (advanced) "高级配置" else listOf("OneTunnel", "分流规则", "端口转发", "设置")[tab], fontWeight = FontWeight.Bold) },
                    navigationIcon = { if (advanced || clashPage) IconButton(onClick = { if (clashPage) clashPage = false else advanced = false }) { Icon(Icons.Default.ArrowBack, "返回") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
                ) },
                bottomBar = { Column {
                    if (!advanced && !clashPage) NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                    listOf("连接", "规则", "转发", "设置").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(listOf(Icons.Default.Home, Icons.Default.List, Icons.AutoMirrored.Filled.ArrowForward, Icons.Default.Settings)[index], label) },
                            label = { Text(label) })
                    }
                } } }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                if (clashPage) {
                    val current = clashPageId == (selectedSubscription?.let { SubscriptionLibrary.id(it.url) } ?: "")
                    val source = clashPageSource?.source
                    val pageProfile = if (current) text else clashPageSource?.yaml.orEmpty()
                    val activeSource = current && ConnectionPolicy.subscriptionEnabled(text)
                    Column(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
                        if (message.isNotBlank()) Text(message, modifier = Modifier.padding(20.dp), color = colors.error)
                        ClashProxyPage(pageProfile, if (current) selectedSubscription?.name ?: "Clash 配置" else source?.name ?: "Clash 配置",
                            activeSource, activeSource && status.state == "running" && !core.updating,
                            canToggle, foreground.value, clash,
                            activate = { update { if (current) ConnectionPolicy.setSubscription(text, true)
                                else ConnectionPolicy.setSubscription(ClashImport.apply(text, requireNotNull(clashPageSource), false), true) } },
                            selectOffline = { group, node -> update { ClashProfiles.select(text, group, node) } },
                            manage = { if (clashPageId.isEmpty()) editingClashFile = true else editingSubscription = clashPageSource },
                            update = source?.let { subscription -> { fetchSubscription(subscription.name, subscription.url) } },
                            modifier = Modifier.weight(1f))
                    }
                } else if (!advanced && tab == 2) {
                    PortForwardPage(text, canToggle, message.ifBlank { core.error },
                        addConnection = { tab = 0; addConnection = true }, save = { applyChange(it) },
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize())
                } else Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (advanced) {
                        SectionLabel("保留完整能力，按需编辑")
                        OutlinedTextField(value = text, onValueChange = {
                            if (it.toByteArray().size <= ConfigStore.MAX_BYTES) text = it else message = "配置不能超过 1 MiB。"
                        }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 640.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
                            label = { Text("Mihomo YAML") })
                        OutlinedButton(onClick = {
                            scope.launch {
                                busy = true
                                runCatching { withContext(Dispatchers.Default) { ProfileCheck.check(text) } }
                                    .onSuccess { message = "检查通过 · ${it.nodes} 个出站 / ${it.rules} 条规则" }
                                    .onFailure { message = it.message ?: "检查失败" }
                                busy = false
                            }
                        }, enabled = editable) { Text("检查配置") }
                        TextButton(onClick = { template = "unified-example.yaml" }, enabled = editable) { Text("三出口示例模板") }
                        TextButton(onClick = { template = "direct.yaml" }, enabled = editable) { Text("直连测试模板") }
                    } else when (tab) {
                        0 -> {
                            Spacer(Modifier.height(12.dp))
                            val stateLabel = if (core.updating && status.state == "running") "正在应用" else when (status.state) { "running" -> "已启动"; "preparing" -> "准备规则数据库"; "starting" -> "正在连接"; "stopping" -> "正在停止"; "auth-starting", "auth" -> "正在认证"; "error" -> "连接失败"; else -> "未连接" }
                            Panel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stateLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    Switch(checked = status.active, onCheckedChange = { if (it) confirmStart = true else stopTunnel() },
                                        enabled = !busy && status.state !in listOf("starting", "stopping") &&
                                            (status.active || (saved.isNotBlank() && !dirty && summary != null)))
                                }
                                SettingRow("其他流量使用", matchOptions[view?.match] ?: if (view == null) "待配置" else "自定义")
                            }
                            if (status.state == "error") Note(status.message, error = true)
                            Button(onClick = { addConnection = true }, enabled = canToggle, modifier = Modifier.fillMaxWidth()) { Text("添加连接") }
                            if (selectedSubscription == null && matchOptions.values.any { it == "Clash" }) {
                                SectionLabel("Clash")
                                ConnectionCard("Clash 配置", ConnectionPolicy.subscriptionEnabled(text), canToggle, {
                                    clashPageId = ""; clashPageSource = null; clashPage = true
                                }) {
                                    update { ConnectionPolicy.setSubscription(text, it) }
                                }
                            }
                            if (subscriptions.isNotEmpty()) {
                                SectionLabel("订阅")
                                subscriptions.forEach { entry ->
                                    ConnectionCard(entry.name,
                                        selectedSubscription?.let { SubscriptionLibrary.id(it.url) } == entry.id && ConnectionPolicy.subscriptionEnabled(text),
                                        canToggle, {
                                        busy = true; message = ""
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { library.load(entry.id) } }
                                                .onSuccess { clashPageSource = it; clashPageId = entry.id; clashPage = true }
                                                .onFailure { message = "订阅读取失败。" }
                                            busy = false
                                        }
                                    }) { enabled ->
                                                if (!enabled) update { ConnectionPolicy.setSubscription(text, false) }
                                                else {
                                                    busy = true
                                                    scope.launch {
                                                        val loaded = runCatching { withContext(Dispatchers.IO) { library.load(entry.id) } }
                                                        busy = false
                                                        loaded.onSuccess { preview -> update { ConnectionPolicy.setSubscription(ClashImport.apply(text, preview, false), true) } }
                                                            .onFailure { message = "订阅读取失败，请更新。" }
                                                    }
                                                }
                                    }
                                }
                            }
                            listOf("wireguard" to "WireGuard", "tailscale" to "Tailscale").forEach { (type, title) ->
                                val items = connections.filter { it.type == type }
                                if (items.isNotEmpty()) {
                                    SectionLabel(title)
                                    items.forEach { entry ->
                                        ConnectionCard(entry.name, entry.enabled, canToggle, { editingConnection = entry }) { enabled ->
                                            update { ConnectionPolicy.setEnabled(text, entry.name, enabled) }
                                        }
                                    }
                                }
                            }
                            if (core.error.isNotBlank()) Note(core.error, error = true)
                        }
                        1 -> {
                            SectionLabel("分流策略")
                            if (view == null) Note("先在连接页导入配置，再设置分流。")
                            else {
                                Panel {
                                    ToggleRow("国内流量直接连接", view.domestic, canToggle) {
                                        update { ProfileEditor.routing(text, it, view.match) }
                                    }
                                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                                    ExitPicker("其他流量使用", matchOptions.keys.toList(), view.match, canToggle, matchOptions) {
                                        update { ProfileEditor.routing(text, view.domestic, it) }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { SectionLabel("当前规则 · ${view.rules.size}") }
                                    TextButton(onClick = { sortingRules = view.rules }, enabled = canToggle && view.rules.size > 2) { Text("排序") }
                                }
                                Panel {
                                    view.rules.take(visibleRuleLimit).forEachIndexed { index, rule ->
                                        val parts = rule.split(',')
                                        val destination = if (parts.lastOrNull() == "no-resolve") parts.getOrNull(parts.size - 2) else parts.lastOrNull()
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            InsetRuleSurface(onClick = {
                                                if (ProfileEditor.editableRule(rule) != null) editingRule = index to rule
                                                else message = "此规则请在停止连接后使用高级规则编辑。"
                                            }, enabled = canToggle, modifier = Modifier.weight(1f)) {
                                                SettingRow(if (parts.firstOrNull() == "MATCH") "其余所有流量" else parts.take(2).joinToString(" · "), destination ?: "", "${index + 1}")
                                            }
                                            if (!rule.startsWith("MATCH,")) TextButton(onClick = { update { ProfileEditor.removeRule(text, index) } }, enabled = canToggle,
                                                contentPadding = PaddingValues(4.dp)) { Text("删除", style = MaterialTheme.typography.bodyMedium) }
                                        }
                                    }
                                    if (view.rules.size > visibleRuleLimit) TextButton(onClick = { visibleRuleLimit += 50 }) { Text("显示更多规则") }
                                }
                                Button(onClick = { addRule = true }, enabled = canToggle, modifier = Modifier.fillMaxWidth()) { Text("添加分流规则") }
                                TextButton(onClick = { advanced = true }, enabled = editable) { Text("高级规则编辑") }
                                if (core.error.isNotBlank()) Note(core.error, error = true)
                            }
                        }
                        3 -> {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Image(painterResource(R.drawable.onetunnel_logo), contentDescription = "OneTunnel 图标",
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)))
                                    Text("OneTunnel", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            SectionLabel("外观")
                            Panel {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                                        FilterChip(selected = theme == key, onClick = {
                                            theme = key; prefs.edit().putString("theme", key).apply(); TunnelSurfaces.refresh(this@MainActivity)
                                        }, label = { Text(label) })
                                    }
                                }
                            }
                            SectionLabel("快捷入口")
                            Panel {
                                TextButton(onClick = {
                                    val widgets = AppWidgetManager.getInstance(this@MainActivity)
                                    if (widgets.isRequestPinAppWidgetSupported) runCatching {
                                        widgets.requestPinAppWidget(ComponentName(this@MainActivity, TunnelWidget::class.java), null, null)
                                    }.onFailure { message = "请长按桌面添加 OneTunnel 小组件。" }
                                    else message = "请长按桌面添加 OneTunnel 小组件。"
                                }) { Text("添加桌面组件") }
                                TextButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) runCatching {
                                        getSystemService(StatusBarManager::class.java).requestAddTileService(
                                            ComponentName(this@MainActivity, TunnelTileService::class.java), "OneTunnel",
                                            Icon.createWithResource(this@MainActivity, R.drawable.ic_status), mainExecutor) { }
                                    }.onFailure { message = "请在下拉快捷设置中编辑并添加 OneTunnel。" }
                                    else message = "请在下拉快捷设置中编辑并添加 OneTunnel。"
                                }) { Text("添加快捷开关") }
                            }
                            SectionLabel("HTTP 代理")
                            if (view == null) Note("导入配置后可设置代理。")
                            else Panel {
                                ToggleRow("开启 HTTP 代理", view.lan.httpEnabled, editable) { enabled ->
                                    update { ProfileEditor.lan(text, view.lan.copy(httpEnabled = enabled)) }
                                }
                                ProxyFields(view.lan, editable, showPassword, { showPassword = !showPassword }, { enabled ->
                                    update {
                                        val password = view.lan.password.ifBlank {
                                            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
                                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
                                        }
                                        ProfileEditor.lan(text, view.lan.copy(enabled = enabled, password = password))
                                    }
                                }) { lan -> update { ProfileEditor.lan(text, lan) } }
                                if (view.lan.httpEnabled) {
                                    Text(if (!view.lan.enabled) "127.0.0.1:${view.lan.port}" else if (addresses.isEmpty()) "连接 Wi-Fi 后可查看局域网地址。"
                                        else addresses.joinToString("\n") { "$it:${view.lan.port}" }, color = colors.primary, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            SectionLabel("配置")
                            Panel {
                                TextButton(onClick = { importer.launch(arrayOf("text/*", "application/x-yaml", "application/yaml", "application/octet-stream")) }, enabled = canToggle) { Text("导入 Clash 配置文件") }
                                TextButton(onClick = { advanced = true }, enabled = !busy) { Text("高级配置") }
                            }
                        }
                    }
                    if (status.active && (tab == 3 || advanced)) Note("监听与高级配置需先停止连接。")
                    if (message.isNotBlank()) Note(message)
                    Spacer(Modifier.height(16.dp))
                }
                }
            }
            pendingChange?.let { pending -> AlertDialog(onDismissRequest = { pendingChange = null; if (advanced) text = saved },
                title = { Text("WireGuard 网段重叠") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { pending.second.take(20).forEach { Text(it) }; Text("重叠目标按规则顺序使用第一个匹配的出口。") } },
                confirmButton = { TextButton(onClick = { pendingChange = null; persist(pending.first) }) { Text("仍然应用") } },
                dismissButton = { TextButton(onClick = { pendingChange = null; if (advanced) text = saved }) { Text("取消") } }) }
            if (confirmStart) AlertDialog(onDismissRequest = { confirmStart = false },
                title = { Text("连接 OneTunnel") }, text = { Text("将替换当前 VPN 连接。") },
                confirmButton = { TextButton(onClick = { confirmStart = false; requestStart() }) { Text("连接") } },
                dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("取消") } })
            editingConnection?.let { entry ->
                if (entry.type == "wireguard") WireGuardEditDialog(text, entry.name, { editingConnection = null }, { pendingDeletion = entry.name to null }) { next ->
                    editingConnection = null; applyChange(next)
                }
                else TailscaleEditDialog(text, entry.name, { editingConnection = null }, { pendingDeletion = entry.name to null }, { next ->
                    editingConnection = null; applyChange(next)
                }) { changed ->
                    TailscaleCard(tailscaleEntries.filter { it.name == entry.name }, core.snapshots, status.state, dirty || changed, busy,
                        core.connected, core.error, { loginTailscale(it) }, { core.request(it, "status") }, { tail, url -> openTailLogin(tail, url) })
                }
            }
            editingSubscription?.let { cached -> SubscriptionDialog(busy, message, { editingSubscription = null; message = "" }, cached.source,
                { pendingDeletion = cached.source!!.name to SubscriptionLibrary.id(cached.source.url) }) { name, url ->
                val old = cached.source!!
                val selected = selectedSubscription?.url == old.url
                busy = true; message = ""
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        val edited = if (url != old.url) ClashImport.prepare(SubscriptionClient.fetch(url), SubscriptionSource(name, url, System.currentTimeMillis()))
                            else ClashPreview(cached.yaml, cached.nodes, cached.groups, cached.rules, cached.warnings, SubscriptionSource(name, url, old.updatedAt))
                        val merged = if (!selected) null else if (url == old.url) ConnectionEditing.subscriptionMeta(text, edited.source!!)
                            else ConnectionPolicy.setSubscription(ClashImport.apply(text, edited, false), ConnectionPolicy.subscriptionEnabled(text))
                        Triple(merged, library.save(edited, SubscriptionLibrary.id(old.url)), edited)
                    } }.onSuccess { (merged, entries, edited) ->
                        busy = false; subscriptions = entries; editingSubscription = null
                        if (clashPage) { clashPageSource = edited; clashPageId = SubscriptionLibrary.id(edited.source!!.url) }
                        if (merged != null) applyChange(merged)
                    }.onFailure { busy = false; message = if (it is IllegalArgumentException) it.message ?: "配置无效。" else "订阅修改失败。" }
                }
            } }
            if (editingClashFile) AlertDialog(onDismissRequest = { editingClashFile = false }, title = {
                ConnectionEditorTitle("Clash 配置", { pendingDeletion = "Clash 配置" to ""; editingClashFile = false }, canToggle)
            }, text = {
                Column {
                    TextButton(onClick = { editingClashFile = false; advanced = true }) { Text("编辑配置") }
                }
            }, confirmButton = { TextButton(onClick = { editingClashFile = false }) { Text("关闭") } })
            pendingDeletion?.let { target ->
                val currentSubscription = target.second == "" || (target.second != null && selectedSubscription?.let { SubscriptionLibrary.id(it.url) } == target.second)
                AlertDialog(onDismissRequest = { pendingDeletion = null }, title = { Text("删除 ${target.first}？") },
                    text = { Text(if (currentSubscription) "保留本机 WG / Tailscale 及其分流，其他流量改为阻止。" else if (target.second == null) "引用此连接的规则将改为阻止。" else "删除本机保存的订阅。") },
                    confirmButton = { TextButton(enabled = canToggle, onClick = {
                        if (!canToggle) return@TextButton
                        runCatching {
                            val next = if (target.second == null) ConnectionRemoval.node(text, target.first)
                                else if (currentSubscription) ConnectionRemoval.subscription(text) else null
                            pendingDeletion = null; editingConnection = null; editingSubscription = null
                            if (target.second != null) clashPage = false
                            if (next != null) persist(next) {
                                target.second?.takeIf { it.isNotEmpty() }?.let { id -> subscriptions = withContext(Dispatchers.IO) { library.remove(id) } }
                            } else scope.launch {
                                busy = true
                                runCatching { withContext(Dispatchers.IO) { library.remove(requireNotNull(target.second)) } }
                                    .onSuccess { subscriptions = it }.onFailure { message = "订阅删除失败，请重试。" }
                                busy = false
                            }
                        }.onFailure { message = "连接删除失败，原配置未更改。" }
                    }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
                    dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("取消") } })
            }
            if (addConnection) AlertDialog(onDismissRequest = { addConnection = false }, title = { Text("添加连接") }, text = {
                Column {
                    TextButton(onClick = { addConnection = false; message = ""; subscriptionDialog = true }, enabled = canToggle) { Text("订阅链接 · Clash / Mihomo") }
                    TextButton(onClick = { addConnection = false; importer.launch(arrayOf("*/*")) }, enabled = canToggle) { Text("Clash 配置文件") }
                    TextButton(onClick = { addConnection = false; wgFile.launch(arrayOf("*/*")) }, enabled = canToggle) { Text("WireGuard 配置文件") }
                    TextButton(onClick = { addConnection = false; ScanInbox.take(); wgScan.launch(Intent(this@MainActivity, WgScanActivity::class.java)) }, enabled = canToggle) { Text("WireGuard 扫码") }
                    TextButton(onClick = { addConnection = false; wgImage.launch("image/*") }, enabled = canToggle) { Text("WireGuard 二维码图片") }
                    TextButton(onClick = { addConnection = false; addTailscale = true }, enabled = canToggle) { Text("Tailscale / Headscale") }
                }
            }, confirmButton = { TextButton(onClick = { addConnection = false }) { Text("关闭") } })
            if (subscriptionDialog) SubscriptionDialog(busy, message, { subscriptionDialog = false; message = "" }) { name, url -> fetchSubscription(name, url) }
            clashPreview?.let { preview -> ClashPreviewDialog(preview, text.isNotBlank(), preview.source?.url == selectedSubscription?.url,
                canToggle, busy, message, { clashPreview = null; message = "" }) { activate, keepRules ->
                scope.launch {
                    busy = true; message = ""
                    runCatching { withContext(Dispatchers.IO) {
                        val merged = if (activate) ClashImport.apply(text, preview, keepRules) else null
                        val entries = if (preview.source != null) library.save(preview) else subscriptions
                        merged to entries
                    } }.onSuccess { (merged, entries) ->
                        busy = false
                        if (merged != null) applyChange(merged)
                        subscriptions = entries; clashPreview = null
                        message = ""
                    }.onFailure { message = if (it is IllegalArgumentException) it.message ?: "配置无效。" else "订阅保存失败，原配置未覆盖。" }
                    if (clashPreview != null) busy = false
                }
            } }
            if (addTailscale) TailscaleSetupDialog({ addTailscale = false }) { name, hostname, control ->
                update { TailscaleProfiles.add(text, name, hostname, control) }
                addTailscale = false
            }
            if (addRule && view != null) RuleDialog(ruleOptions.keys.toList(), ruleOptions,
                RoutingRule("DOMAIN-SUFFIX", "", view.match.takeIf { it in ruleOptions } ?: "REJECT"), false, canToggle, { addRule = false }) { next ->
                val changed = ProfileEditor.addRule(text, next.type, next.target, next.exit)
                applyChange(changed)
                addRule = false
            }
            editingRule?.let { (index, expected) -> ProfileEditor.editableRule(expected)?.let { initial ->
                val options = if (initial.type == "MATCH") matchOptions else ruleOptions
                RuleDialog((options.keys + initial.exit).distinct(), options, initial, true, canToggle, { editingRule = null }) { next ->
                    val changed = ProfileEditor.replaceRule(text, index, expected, next)
                    applyChange(changed)
                    editingRule = null
                }
            } }
            sortingRules?.let { expected -> RuleOrderDialog(expected, canToggle, { sortingRules = null }) { order ->
                val changed = ProfileEditor.reorderRules(text, expected, order)
                if (order != expected.indices.toList()) applyChange(changed)
                sortingRules = null
            } }
            wgPreview?.let { wg -> WireGuardImportDialog(wg, text.isBlank(), view?.exits.orEmpty(), { wgPreview = null }) { name, mode ->
                update { ProfileEditor.importWireGuard(text, wg, name, mode) }
                wgPreview = null
            } }
            template?.let { name -> AlertDialog(onDismissRequest = { template = null }, title = { Text("加载模板") },
                text = { Text("替换当前编辑内容，保存后生效。三出口模板需要填写自己的节点与密钥；直连模板仅用于测试。") },
                confirmButton = { TextButton(onClick = {
                    text = assets.open(name).bufferedReader().use { it.readText() }; template = null; advanced = true
                }) { Text("加载") } }, dismissButton = { TextButton(onClick = { template = null }) { Text("取消") } }) }
        }
    }
}

@Composable private fun WireGuardImportDialog(wg: WireGuardProfile, first: Boolean, exits: List<String>, dismiss: () -> Unit,
    add: (String, String) -> Unit) {
    var name by remember { mutableStateOf(generateSequence(1) { it + 1 }.map { if (it == 1) "WG" else "WG-$it" }.first { it !in exits }) }
    var mode by remember { mutableStateOf(if (wg.hasDefaultRoute) ProfileEditor.WG_OUTBOUND_ONLY else ProfileEditor.WG_ROUTES) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("导入 WireGuard") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("连接名称") }, singleLine = true)
            Text("${wg.peerCount} 个 Peer · ${wg.addresses.joinToString()}\n${wg.allowedIPs.size} 个目标网段", style = MaterialTheme.typography.bodyMedium)
            ExitPicker("导入方式", if (wg.hasDefaultRoute) listOf(ProfileEditor.WG_OUTBOUND_ONLY, ProfileEditor.WG_MATCH)
                else listOf(ProfileEditor.WG_ROUTES, ProfileEditor.WG_OUTBOUND_ONLY, ProfileEditor.WG_MATCH), mode, true,
                labels = mapOf(ProfileEditor.WG_ROUTES to "按 AllowedIPs 分流", ProfileEditor.WG_OUTBOUND_ONLY to "仅添加出口", ProfileEditor.WG_MATCH to "设为 MATCH 出口")) { mode = it }
            if (wg.hasDefaultRoute) Note("AllowedIPs 含默认路由。设为 MATCH 会让其余流量走 WG；已有国内直连和自定义规则仍优先。")
            else Note("网段：${wg.allowedIPs.take(8).joinToString()}${if (wg.allowedIPs.size > 8) " …" else ""}")
            if (first && mode != ProfileEditor.WG_MATCH) Note("首次创建配置：国内直连，其余流量暂时拒绝。可在规则页选择 MATCH 出口。")
            wg.warnings.forEach { Note(it) }
            if (error.isNotBlank()) Note(error, error = true)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { add(name, mode) }.onFailure { error = if (it is IllegalArgumentException) it.message ?: "导入失败。" else "导入失败，原配置未更改。" }
    }) { Text("添加") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun RuleDialog(exits: List<String>, labels: Map<String, String>, initial: RoutingRule,
    editing: Boolean, enabled: Boolean, dismiss: () -> Unit, save: (RoutingRule) -> Unit) {
    var type by remember(initial, editing) { mutableStateOf(initial.type) }
    var target by remember(initial, editing) { mutableStateOf(initial.target) }
    var exit by remember(initial, editing) { mutableStateOf(initial.exit) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (editing) "修改分流规则" else "添加分流规则") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initial.type != "MATCH") {
                ExitPicker("匹配类型", ProfileEditor.ruleTypes, type, enabled,
                    labels = mapOf("DOMAIN-SUFFIX" to "域名及子域名", "DOMAIN" to "完整域名", "DOMAIN-KEYWORD" to "域名关键词", "IP-CIDR" to "IPv4 网段", "IP-CIDR6" to "IPv6 网段", "GEOSITE" to "域名规则集", "GEOIP" to "IP 地区规则集")) { type = it }
                OutlinedTextField(target, { target = it }, enabled = enabled,
                    label = { Text(if (type.startsWith("IP")) "目标网段" else if (type.startsWith("GEO")) "规则集名称" else "域名 / 关键词") },
                    placeholder = { Text(when (type) { "IP-CIDR6" -> "fd7a:115c:a1e0::/48"; "IP-CIDR" -> "10.77.0.0/24"; "GEOSITE" -> "cn"; "GEOIP" -> "CN"; else -> "example.com" }) }, singleLine = true)
            }
            ExitPicker(if (initial.type == "MATCH") "其他流量使用" else "使用出口", exits, exit, enabled, labels) { exit = it }
            if (error.isNotEmpty()) Note(error, error = true)
        }
    }, confirmButton = { TextButton(enabled = enabled, onClick = {
        val noResolve = if (type == initial.type && editing) initial.noResolve else type.startsWith("IP-CIDR")
        runCatching { save(RoutingRule(type, target, exit, noResolve)) }.onFailure { error = it.message ?: "规则无效。" }
    }) { Text(if (editing) "完成" else "添加") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable internal fun Note(text: String, error: Boolean = false) {
    Text(text, modifier = Modifier.fillMaxWidth(), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium)
}
@Composable private fun SettingRow(title: String, value: String, index: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (index != null) Text(index, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.widthIn(max = 180.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun ConnectionCard(title: String, checked: Boolean, enabled: Boolean, edit: () -> Unit, change: (Boolean) -> Unit) {
    Surface(onClick = edit, enabled = enabled, shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(checked = checked, onCheckedChange = change, enabled = enabled)
        }
    }
}
@Composable internal fun ToggleRow(title: String, checked: Boolean, enabled: Boolean, edit: (() -> Unit)? = null, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (edit != null) Modifier.clickable(enabled = enabled, onClick = edit) else Modifier), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}
@Composable private fun ExitPicker(title: String, exits: List<String>, selected: String, enabled: Boolean,
    labels: Map<String, String> = emptyMap(), change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text("${labels[selected] ?: if (labels.isNotEmpty()) "自定义" else selected}  ▾") }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                exits.forEach { exit -> DropdownMenuItem(text = { Text(labels[exit] ?: exit) }, onClick = { change(exit); expanded = false }) }
            }
        }
    }
}
@Composable private fun Metric(title: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ProxyFields(lan: LanProxy, enabled: Boolean, reveal: Boolean, toggle: () -> Unit,
    share: (Boolean) -> Unit, change: (LanProxy) -> Unit) {
    var port by remember(lan.port) { mutableStateOf(lan.port.toString()) }
    var user by remember(lan.user) { mutableStateOf(lan.user) }
    var password by remember(lan.password) { mutableStateOf(lan.password) }
    var error by remember { mutableStateOf("") }
    OutlinedTextField(port, { port = it }, enabled = enabled, label = { Text("HTTP 监听端口") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    ToggleRow("允许局域网连接", lan.enabled, enabled && (lan.httpEnabled || lan.enabled), change = share)
    if (lan.httpEnabled && lan.enabled) {
        ToggleRow("添加账号", lan.authRequired, enabled) { required ->
            val secret = lan.password.ifBlank {
                val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            }
            change(lan.copy(authRequired = required, password = secret))
        }
    }
    if (lan.httpEnabled && lan.enabled && lan.authRequired) {
        OutlinedTextField(user, { user = it }, enabled = enabled, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, enabled = enabled, label = { Text("密码") }, singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = toggle) { Text(if (reveal) "隐藏" else "显示") } }, modifier = Modifier.fillMaxWidth())
    }
    LaunchedEffect(port, user, password, enabled, lan) {
        if (!enabled) return@LaunchedEffect
        val editingAuth = lan.httpEnabled && lan.enabled && lan.authRequired
        val changed = port != lan.port.toString() || (editingAuth && (user != lan.user || password != lan.password))
        if (!changed) { error = ""; return@LaunchedEffect }
        delay(450)
        val number = port.toIntOrNull()
        if (number == null || number !in 1024..65535) error = "端口范围为 1024–65535。"
        else {
            val next = lan.copy(port = number, user = if (editingAuth) user else lan.user, password = if (editingAuth) password else lan.password)
            error = runCatching { ProfileEditor.validateLan(next) }.exceptionOrNull()?.message.orEmpty()
            if (error.isEmpty()) change(next)
        }
    }
    if (error.isNotEmpty()) Note(error, error = true)
}
