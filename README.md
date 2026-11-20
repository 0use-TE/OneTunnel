# 一隧 / OneTunnel — Mihomo 统一安卓 VPN（第一版）

Compose 客户端，将安卓应用流量送入一个 VpnService/TUN，由原生 Mihomo 按规则选择普通代理、WireGuard、Tailscale 或明确配置的直连出口。

**这是供用户手动测试的 alpha。VPN 由用户主动连接；异常退出后不自动恢复。**

## 已实现

- OneTunnel：连接、规则、转发、设置四页；浅色、深色及跟随系统。连接页采用总开关和各连接独立开关。
- VPN 使用独立前台服务，退出界面不主动停止。0.3.20 移除了异常自动恢复、系统常驻入口及其生命周期查询；所有启动路径返回 START_NOT_STICKY，仅处理用户明确发起的连接 / 认证，不处理系统重建或开机启动。旧版恢复偏好不再读取，订阅、密钥、身份和分流不受影响。
- 设置“快捷入口”可请求添加约 2×1 桌面组件和下拉快捷开关。组件显示连接状态与总开关，随明暗外观变化，可拉宽显示图标；磁贴显示连接状态并启停。首次连接走系统 VPN 授权，无配置时进入应用添加。状态变化触发更新，组件另有系统约 30 分钟周期刷新；进程突然被结束时桌面缓存可能暂时滞后，打开应用 / 磁贴会刷新，不靠每秒轮询保活。
- 快捷开关、桌面按钮和通知小图标使用现有猫形 Logo 的单色矢量版，系统负责着色；桌面原图标保持。下拉磁贴明确显示“点击连接 / 点击断开 / 点击取消”，连接过程中也可取消，断开中阻止重复点击。若只想移除下拉入口，在系统快捷设置编辑页拖出该磁贴，移除本身不改变 VPN 连接。
- 点击 Clash 订阅/配置进入独立代理页；WG、TS 条目继续打开编辑，开关仅启停。WG 支持密钥、地址、DNS、多 Peer、Endpoint、AllowedIPs 与分流方式；TS 支持设备名与控制服务。订阅编辑、更新和删除收在代理页“管理”菜单，连接首页无更新按钮。
- Clash 代理页按组切换，节点使用自适应网格、选中边框和搜索，显示策略、当前节点及嵌套选择路径。手动组点击切换；自动选择/故障转移/负载均衡显示核心结果，不强制改为手动。单节点或整组测速通过 Mihomo URLTest，沿用组的测试地址和预期状态码，最多四个并发、单次五秒超时；支持停止测速。Provider 节点在连接后从真实运行组读取。
- 手动选择运行时直接修改代理组，不重启 VPN，新连接立即采用新节点，已有连接不会迁移。选择保存在同一加密配置，并按订阅分别记录；停止时可选择静态组成员，下次启动生效。订阅刷新/切换会恢复仍有效的选择，自动策略不保存伪造的手动结果。未启用订阅可浏览缓存并启用后操作。
- “其他流量使用”只列 Clash、各 WG/TS、直连和阻止；订阅细分组、单节点和核心内置项由高级配置处理。
- 当前一次启用一份 Clash 订阅，多份缓存可切换；WireGuard/Tailscale 可多个同时启用，按规则分流。关闭的出口在运行配置中替换为 REJECT，原始凭据和规则仍保留。
- 完成导入或图形设置后立即加密保存；取消“未保存 / 保存更改”流程。运行中切换连接经同 UID IPC/JNI 重载核心并关闭旧连接，保持 Android TUN；失败时停止 VPN。
- 导入或启用 WG 时检查新出现的有效 IPv4/IPv6 路由重叠，显示具体连接、网段，允许取消或明确继续；仅添加出口且无对应路由规则不提示。
- 三出口模板默认国内域名/IP 直连，其余 MATCH；可选择 MATCH 出口、切换国内直连、添加或删除域名/网段规则。导入已有配置保留原规则。
- 运行中可切换国内直连与 MATCH、添加/删除规则，点击常用域名、网段、GEOSITE/GEOIP 或 MATCH 条目弹出编辑；完成即加密保存并重载，保留系统 VPN 隧道，旧连接会重连。规则条目有圆角内边距与细边框；“排序”弹窗长按整行上下拖拽，边缘自动滚动，MATCH 固定最后，完成后一起应用；复杂组合规则仍通过停止后的高级配置编辑。
- 普通规则的出口列表只显示 Clash 代理组、本机 WG/TS、直连和阻止，不列出整份订阅的单节点与核心内部出口。已有直接引用单节点的规则继续保留，并在编辑该条时单独保留其当前出口；MATCH 继续使用简化选择。
- 设置页可开关 HTTP 代理并修改监听端口（默认 7890，范围 1024–65535），有效输入短暂防抖后自动加密保存。默认只监听 127.0.0.1；允许局域网连接后默认需要认证，可关闭“添加账号”改为免认证，均只接受私网/本地来源。沿用支持 HTTP/SOCKS5 的 mixed listener，随统一 VPN 启停；停止后修改，下次连接生效。旧版 LAN 配置自动兼容。
- 运行中可添加 WG 文件 / 扫码 / 二维码图片、Tailscale 和 Clash 配置 / 订阅，确认保存后经现有 IPC 热重载，保留 Android TUN；已有连接会重连。文件选择器返回时等待服务通道恢复，重载期间阻止重复提交，重载失败停止连接并保留已保存配置。
- Tailscale 在线设备列表默认折叠，点击标题展开，只显示在线设备。
- 独立“转发”页按 WireGuard / Tailscale 连接分组管理本机 TCP 端口转发（每条连接最多 16 个端口）。新增端口留空，仅以 80 作占位提示；输入有效值并保存后才开放。每条映射有独立开关，关闭时保留配置但不监听，再开启恢复；旧映射默认保持开启。点击映射编辑，右侧菜单删除；连接编辑弹窗的删除操作收在右上角菜单。目标固定为 127.0.0.1；随该连接和统一 VPN 启停，运行中切换或修改自动加密保存并重载，已有网络会话会重连。TS 认证会话不开放转发，端口修改保留现有身份、密钥、启用状态与分流。
- 设置页“配置”仅保留导入 Clash 配置文件和高级配置；三出口示例、直连测试模板收在高级配置中。
- SAF 导入 Mihomo YAML、最多 1 MiB；离线语法/结构检查；确认导入后自动加密保存。
- Clash/Mihomo YAML 订阅：粘贴 HTTP/HTTPS 链接、下载预览、独立加密缓存、手动更新和切换；已用电脑中的 Ouse 与魔戒订阅实际下载验证。连接期间可更新缓存，停止后应用配置。
- Tailscale 添加节点、触发系统浏览器认证、查看状态和设备；通过同一 tsnet 身份工作。未连接时用独立认证会话，已连接时直接向正在运行的核心请求认证。认证链接异步返回后打开浏览器，不因普通状态轮询反复弹出。
- 连接列表采用整张圆角卡片点击反馈，开关独立操作；点击条目编辑并删除。删除 WG/TS 后其规则和 DNS 引用改为阻止；删除当前订阅保留本机 WG/TS、身份配置、分流和 LAN 设置，原订阅出口不回退直连。删除只影响本机配置，不代替 Tailnet 管理后台的设备吊销。
- WireGuard `.conf` 文件导入（64 KiB 上限）、相机二维码、图片二维码（8 MiB 上限）；离线转换并预览，确认添加后自动保存，不自动启动 VPN。可仅添加出站、按 AllowedIPs 分流或选择为 MATCH。
- Android Keystore AES-GCM 配置存储；配置页禁止截图；不备份应用配置和核心状态。
- 一个 Android VpnService，独立 `:vpn` 进程；IPv4/IPv6 TUN、DNS 劫持、socket protect JNI 回调。
- VPN 跟随系统默认底层网络并继承计费状态，避免把自身作为底层网络或将普通 Wi-Fi 无条件标为计费连接。QQ 发图复现后修复，用户实测确认恢复。
- Mihomo Meta 固定提交的原生代理/WG/Tailscale 出站能力；没有用 sing-box 替代内核。
- 主界面启动前明确提示现有 VPN 会被替换；快捷入口仍经过系统授权。没有外部 HTTP 控制 API；不自动恢复或开机连接。
- 停止入口和前台通知；原生启动卡住时可结束独立 VPN 进程。
- 三出口结构示例和只用于基础验证的直连模板，均不包含真实凭据。

## 完成度与后续优先项

连接、分流、代理组、端口转发和快捷入口已形成完整主流程。QQ 图片发送及 Tailscale 到 Termux 的 SSH 已有用户成功反馈；0.3.19 升级后收到闪烁及无法工作的反馈，0.3.20 已移除自动恢复，仍需用户确认修复效果及组件、磁贴在设备上的表现。

- 优先验证升级后打开应用、手动连接 / 断开、锁屏、网络切换、WG 入站及快捷入口首次授权；见 [测试清单](docs/USER_TEST_CHECKLIST.md)。
- 值得补充：订阅定时更新、加密配置备份与恢复、按应用选择分流。
- 正式签名、发布升级流程与应用商店发布；系统 lockdown / 完整 kill switch 尚未完成专项验证。
- ARM64 以外 ABI、ZeroTier/EasyTier 出站、全部已有 Clash 配置的无修改兼容。
- WireGuard/Tailscale 的 UDP 入站转发、完整 IP 入站及路由器功能；现有本机转发仅支持显式 TCP 端口。

Tailscale 可使用浏览器登录，也可在高级配置中填写 auth-key；会创建新 tailnet 身份，不复用原 Tailscale App。核心的状态和 provider 缓存位于私有目录，不声称所有运行时文件都经过 Keystore 加密。

访问 Termux：底部“转发” → 添加 → 选择 Tailscale 连接，填写入站 `8022`、本机 `8022`，点保存。启用该 Tailscale 连接及总 VPN，在 Termux 启动 `sshd`；其他 Tailnet 设备执行 `ssh -p 8022 <Termux 的 whoami 用户名>@<OneTunnel 的 Tailscale IP>`。应使用 OneTunnel 状态显示的节点地址，并确保 Tailnet ACL 允许对应 TCP 端口。这里只转发显式配置的 TCP 端口，不提供完整官方 App 的 IP 入站行为或内置 Tailscale SSH。

WireGuard 用法相同，在“转发”页选择 WG 添加端口并启用连接后，远端 SSH 使用该 WG 的“本机地址”，而不是 Tailscale IP。对端需能通过 WG 路由到该地址，Peer 的 AllowedIPs 需允许实际来源；经中心服务器访问时还需中心服务器转发/防火墙配置。移动设备在 NAT 后需要保持端点可达，可在既有 Keepalive 输入中设置 25 秒。软件不自动修改这些网络参数，`.conf` 的 ListenPort 仍不作为 Android UDP 监听端口导入。

构建验证见 `docs/BUILD_RESULT.md`。原生库和上游副本由构建脚本生成，不纳入 Git；完整 APK 必须包含真实原生库。默认国内规则使用 GEOSITE/GEOIP，首次启动可能下载所需数据库；离线检查不会下载。局域网代理只接受私网/本地来源并要求认证，不会开放 Mihomo 控制接口。

WG 导入面向标准主动连接的客户端配置：支持 IPv4/IPv6、多个 Peer（Keepalive 需一致）、预共享密钥；保留 Peer 的 AllowedIPs。脚本、按 App 选项、多个同族本地地址及不兼容扩展会明确拒绝，不悄悄丢弃。DNS 只用于 WG 出站解析，搜索域和 ListenPort/Table/SaveConfig 的差异在导入预览说明。含默认路由时不自动添加覆盖全部目标的前置规则，可显式设为 MATCH；新建配置未选 MATCH 时其余流量暂设 REJECT。相机权限仅点击扫码时申请，不上传二维码或写入相册。

协议与导入入口是两层：内嵌 Mihomo 可解析 VLESS、VMess、SS/SSR、Trojan、Hysteria/Hysteria2、TUIC、SOCKS5/HTTP 等出站 YAML。订阅入口接收 Clash/Mihomo YAML，不转换 Base64 节点列表或 `vless://` 等单节点分享链接；WG 扫码入口只处理 WG 配置文本。桌面脚本不执行，PROCESS 规则移除并在预览提示；订阅不能自行打开局域网共享或控制端口。更新和切换保留应用添加的 WG/TS 节点，可选择保留当前规则。

## 源码分析与测试

- [源码证据和架构](docs/ARCHITECTURE.md)
- [用户手动测试与恢复清单](docs/USER_TEST_CHECKLIST.md)
- [上游提交锁定](UPSTREAMS.md)
- [构建结果](docs/BUILD_RESULT.md)

## Termux 编译

当前平板已配置的环境：JDK 21、Gradle 8.7、Android SDK 34、Termux AAPT2；原生库另外需要 Termux Go/Clang/NDK sysroot。SDK 等的许可证仍然适用。

```sh
pkg install golang clang make openjdk-21 aapt2 git
bash scripts/fetch-upstreams.sh
bash scripts/build-native-termux.sh
bash scripts/build-client-termux.sh
```

构建脚本使用会话之前准备的 `~/dsh-android/env.sh` 设置 Gradle/SDK 路径。换设备时应自己准备相应 SDK 和 Gradle，或按以下环境变量替换脚本中的初始化：

```sh
export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export ANDROID_HOME="$HOME/dsh-android/sdk"
export GRADLE_USER_HOME="$HOME/dsh-android/gradle-cache"
export PATH="$HOME/dsh-android/tools/gradle-8.7/bin:$PATH"
```

可选的单次构建代理，不会修改 Clash 设置：

```sh
BUILD_PROXY=127.0.0.1:7890 bash scripts/build-client-termux.sh
```

脚本只编译，不安装或运行 APK。原生编译较慢，限制编译并发以减少内存占用。APK 仅 ARM64，最低 Android 8，compile/target API 34 是兼容性验证基线，不代表满足当前商店要求。构建产物使用开发签名，不应作为正式发布密钥。

## Windows 原生编译

准备 Go 1.26.4、Android NDK r29、JDK 21、SDK 34 和 Gradle 8.7。先用 `scripts/fetch-upstreams.sh` 获取固定源码，再执行：

```powershell
./scripts/build-native-windows.ps1 -NdkRoot 'C:/Android/android-ndk-r29' -GoExe 'go'
gradle -p client :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

原生脚本自动安装项目的 Tailscale 窄接口 overlay，输出 ARM64 JNI 库；不会安装或启动应用。

## 安全启动

先导入配置并确认，程序自动检查、加密保存；确认自己能恢复原 Clash，然后打开连接总开关并完成系统授权。**VPN 授权/启动可能立刻中断现有 VPN 和 GPT 连接。**

本 App 不读取原 Clash、WG 或 Tailscale 的私有文件，不会自动导入你的密钥。不要在聊天里发送真实凭据。

## 许可证与来源

自写代码 GPL-3.0-or-later；Mihomo 为 GPLv3，Tailscale 和各传递依赖遵循各自许可。根目录 LICENSE 是 GPLv3 正文。许可副本和通知见 licenses 与 APK assets/licenses。正式公开分发前需完善对应源码与全部传递依赖许可合规，不应宣称是 Mihomo、Tailscale 或 Clash 官方客户端。

项目远程仓库为 https://github.com/0use-TE/OneTunnel 。上游副本和本地构建产物不计入本项目 Git 历史；上游提交与补丁记录在 UPSTREAMS.md，构建时获取固定源码并应用项目补丁。
