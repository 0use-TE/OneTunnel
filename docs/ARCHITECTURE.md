# 单 Android VPN + Mihomo + WG + Tailscale：源码核验与第一版架构

## 结论

在固定的 Mihomo Meta 提交 `0455d0d28c4526937380505153e65a843d8612e8` 中，**WireGuard 和 Tailscale 已是原生出站**。没有必要把三个现有 Android App 的 VPN 叠加，也没有必要额外使用 SOCKS 包装 Tailscale 来丢失 UDP 能力。

第一版选择：**Compose 界面 + 单独的 `:vpn` 服务进程 + 一个 Android VpnService + 内嵌 ARM64 Mihomo c-shared 库**。核心完全在用户手动启动后工作。

本文是源码/编译层面的结论，**不等于已在这台平板上验证网络连通性**。用户明确要求不切换现有 Clash，所有功能实测由用户完成。

## 源码证据

所有本地路径均相对于仓库根目录。上游版本见 [UPSTREAMS](../UPSTREAMS.md)。

| 能力 | 证据 |
|---|---|
| 原生 Tailscale | [tailscale.go:1–67](../upstream/mihomo/adapter/outbound/tailscale.go#L1-L67)：构建标签 `with_gvisor && !no_tailscale`，包含 hostname/auth-key/control-url/state-dir/accept-routes/exit-node 字段 |
| 内嵌 tsnet，而非第二个系统 VPN | [tailscale.go:148–179](../upstream/mihomo/adapter/outbound/tailscale.go#L148-L179)：`tsnet.Server`，系统 TCP/UDP 连接交给 Mihomo dialer |
| Android 特殊网络接口处理 | [tailscale.go:69–111](../upstream/mihomo/adapter/outbound/tailscale.go#L69-L111)：针对 Android SDK 30 的 net.Interfaces 限制注册替代接口枚举 |
| Tailscale TCP/UDP | [tailscale.go:349–417](../upstream/mihomo/adapter/outbound/tailscale.go#L349-L417)：TCP 走 netstack，UDP 走 tsnet.ListenPacket |
| MagicDNS 接入 | [tailscale.go:419–454](../upstream/mihomo/adapter/outbound/tailscale.go#L419-L454) 与 [dns/tailscale.go](../upstream/mihomo/dns/tailscale.go)：`tailscale://出站名称` 和 LocalClient.QueryDNS |
| Tailscale 子网/出口节点偏好 | [tailscale.go:272–347](../upstream/mihomo/adapter/outbound/tailscale.go#L272-L347)：accept-routes 映射 RouteAll，exit-node 处理逻辑存在；不代表 ACL、批准流程或具体服务端已配置 |
| 原生 WG | [wireguard.go:72–103](../upstream/mihomo/adapter/outbound/wireguard.go#L72-L103)：private-key、ip、peers、allowed-ips、keepalive、远程 DNS 等 |
| Android fd 注入 | [CMFA tun.go:54–70](../upstream/ClashMetaForAndroid/core/src/main/golang/native/tun/tun.go#L54-L70)：FileDescriptor + AutoRoute:false + sing_tun.New |
| Socket 防回环 | [CMFA TunModule.kt:57–66](../upstream/ClashMetaForAndroid/service/src/main/java/com/github/kr328/clash/service/clash/module/TunModule.kt#L57-L66) 与 [Mihomo socket_hook.go](../upstream/mihomo/component/dialer/socket_hook.go)：VpnService.protect / DefaultSocketHook |
| 配置解析并不总是离线 | [config.go:625–640](../upstream/mihomo/config/config.go#L625-L640)：完整解析可能加载/下载 geodata；因此 UI 的离线检查只做 YAML 语法和结构检查 |

Tailscale 原生支持已经消除了之前假设中的“SOCKS 中转是否支持 UDP”问题，但仍需验证 APK 运行、DERP、ACL、路由审批及 Android 后台行为。

## 数据路径

```text
安卓应用（不含本 App 本身）
  ↓ IPv4 + IPv6 默认路由
Android VpnService 创建一个 TUN
  ↓ 同进程 fd，Go 侧 Dup 后交给 sing-tun/gVisor
Mihomo rules
  ├─ 普通代理出站
  ├─ WireGuard 用户态协议栈
  ├─ Tailscale tsnet 用户态协议栈
  ├─ DIRECT（仅按用户显式规则）
  └─ REJECT
```

- 所有核心底层 socket 经 JNI 回调 `VpnService.protect(fd)`；protect 失败即拒绝连接。
- 核心没有调用 Network.bindSocket，VPN 的 underlying networks 保持 null，跟随系统默认物理网络；不能把默认回调返回的 VPN 本身写为底层网络。API 29+ 使用 setMetered(false) 继承底层计费状态，Wi-Fi 不会被无条件标为计费连接。
- 本 App 自身也被排除在其 VPN 捕获之外，减少控制面回环风险；不是排除其他 App。
- Android 接管 IPv4/IPv6 路由，核心不能自行添加系统路由或建立第二个 TUN。
- MTU 采用保守的 1280，捕获 DNS/53 交给核心。没有声称支持所有 MTU/协议组合。
- 系统私有 DNS、应用自带 DoH/DoT 不一定属于普通 DNS/53 劫持，必须单独测试。

## Android/JNI 边界

新代码不引入 CMFA 的完整 Gradle 插件/CMake/多模块系统，而是自行实现小型 JNI 桥接：

1. Java/Kotlin `TunnelService` 在用户确认及系统授权之后创建 TUN。
2. 启动 `libonetunnel.so`；传入应用私有状态目录、内存中的 YAML、TUN fd 和 protect 回调对象。
3. Go 调用固定版本核心的配置解析、ApplyConfig 和 sing_tun.New。
4. 停止时先关闭入口，再清理核心；即使原生初始化卡住，独立 `:vpn` 进程也有退出兜底，不影响 Compose、Termux 或原 Clash 的进程。

0.3.3 起增加无 TUN 的配置准备阶段：在建立 Android VPN 前调用 JNI prepare，加载或下载数据库并校验核心配置，然后关闭临时出站对象。此阶段保持原网络/VPN，不启动监听；界面显示准备规则，最长 120 秒。准备成功才建立 TUN，核心/TUN 阶段另有 60 秒超时。日志只记录固定阶段与分类错误，不记录配置或原始内核错误；数据库失败单独提示。

0.3.8 起可在调试时通过应用私有目录 `no_backup/core/route-trace-once` 标记启用下一次 VPN 会话的分流追踪。启动即消费标记，默认关闭，最多运行五分钟；仅订阅并筛选核心的 TCP/UDP 路由事件，最多保留 256 条，另附最多 128 条活动连接的流量计数。每秒原子写入同目录 `route-trace.json`，权限 0600；没有 HTTP 控制器或导出组件。认证与其他日志不进入文件，原始错误转换为固定类别。文件留在禁止备份的应用私有目录供诊断读取，诊断完成后可删除；它不包含报文或图片内容，也不用于回溯启用前的连接。

0.3.10 起规则页允许 running 状态下增删、结构化编辑与排序，走现有加密保存 → CoreControl.reloadSavedProfile → 同 UID Messenger → GoReloadCore 链路，不暴露 Mihomo HTTP API。规则编辑携带原索引与内容用于拒绝过期提交；排序携带完整原列表与索引排列，必须是全排列且 MATCH 索引固定末尾。修改条目保持原顺序和 no-resolve，排序使用独立索引区分重复条目。排序弹窗中的多次移动只在“完成”时一起持久化/应用。重载会关闭旧代理连接和出站实例，保留系统 TUN；不是对既有 TCP 流原地切换出口。

0.3.12 的规则展示与排序 UI 位于 RuleUi.kt。排序使用 Compose 的 detectDragGesturesAfterLongPress，父列表接收手势，拖动条目使用独立浮层；列表中保留透明占位，避免 item 重排/滚出视口时丢失当前指针。浮层位置与指针均以视口坐标保存，滚动只改变列表条目的几何信息；逐帧根据边缘距离计算滚动速度并重新定位目标。MATCH 位于可拖列表外。视口几何函数单独放在 RuleDragGeometry.kt，便于在没有设备时测试。

Go 的 `go.mod` 内 dependency replace 不会传递到主模块，因此本项目显式复制了上游 protobuf replacement。构建标签为 `with_gvisor,no_easytier,no_zerotier`；未裁掉 Tailscale。

为避免“在 Termux 能编译，却不能作为独立 App 使用”，还做了静态可移植性处理：移除 Termux Clang 注入的私有 RUNPATH；仅保留 Android 系统 libc/libdl/liblog 动态依赖；设置 App 自己的 HOME/TMPDIR/XDG 目录与系统证书目录，不访问 Termux 私有状态。JNI_OnLoad 和 start/stop 导出已检查，ELF LOAD 段保持 16 KiB 对齐。这些都不是实际 dlopen/联网测试，运行结果仍由用户验证。

## 配置覆盖边界

为避免导入旧配置后额外监听 7890/9090 或执行系统级操作，第一版固定覆盖：

- 导入的 HTTP/SOCKS/mixed/redir/tproxy 入站端口固定禁用。应用 `x-onetunnel` 显式启用 HTTP 代理后才打开指定 mixed-port（1024–65535），默认绑定 127.0.0.1；局域网共享开启后绑定 `*`，只允许私网/本地来源并默认使用用户名/密码认证，只有用户关闭“添加账号”才免认证，不继承导入配置的免认证规则。
- 外部控制接口、网页面板、其他 listeners/tunnels、TUIC 入站禁用。
- profile 中的 TUN 设置不采用，由 Android VpnService 统一管理。
- iptables、写系统时间、geodata 自动更新禁用。
- 强制 rule 模式、关闭按进程查询；第一版不支持 PROCESS-* 规则。
- DNS 内核启用，但本地独立 DNS 监听端口禁用，通过 TUN 劫持处理。

普通节点、proxy-providers、rule-providers、proxy-groups、显式规则等仍由 Mihomo 解析。provider、geodata 或健康检查可能在**用户启动核心之后**产生网络请求；“离线检查”不会做这些事。订阅下载与核心启动分离，不承诺复杂桌面配置无需修改即可使用。

## 简洁界面与配置修改

- 连接 / 规则 / 设置三个入口，高级 YAML 编辑单独进入；设置和高级编辑页禁止截图。
- 主题为跟随系统 / 浅色 / 深色，仅主题偏好放普通 SharedPreferences，网络设置和代理认证随 YAML 加密保存。
- `ProfileEditor` 修改同一份配置，没有独立于核心的第二套规则。新模板在自定义 WG/TS 规则之后加入 `GEOSITE,cn,DIRECT`、`GEOIP,CN,DIRECT,no-resolve`，以 MATCH 结束。已有导入规则保持原样，用户可自行切换策略。
- 图形化添加域名、域名后缀、关键词、IPv4/IPv6 网段规则；复杂规则仍通过高级 YAML 保留。MATCH 下拉选择配置中的节点、代理组或 DIRECT/REJECT，不等同于实时修改 select 组的成员。
- 网络配置在连接期间只读；编辑、检查、加密保存后，下次手动连接应用。未启用外部 HTTP controller，后续实时操作可增加窄范围 JNI/IPC 接口。
- 代理监听与局域网共享使用独立字段读取与校验；HTTP 开关关闭时不监听，共享关闭时仅允许本机。其他设备需自行配置 HTTP/SOCKS5 代理，不是自动网关或热点透明代理。

## HTTP 代理

HTTP 代理设置使用应用元数据 `http-enabled`、`lan-port` 和既有 LAN 认证字段，原生 `appsettings.ParseProxy` 严格验证后映射到 Mihomo mixed listener。缺少 http-enabled 时兼容旧 lan-enabled；显式关闭始终关闭所有应用代理监听。默认只绑定 127.0.0.1，共享开启后使用私网来源限制和强制认证；导入配置中的 ports / authentication 仍被忽略。设置页停止连接后编辑，有效端口与认证信息自动保存，代理跟随统一 VPN 服务启停。

0.3.7 增加 `lan-auth-enabled`（缺省 true），对应界面“添加账号”；显式 false 才省略 authentication，保留 LAN 来源限制及原凭据。运行中添加连接允许使用既有重载链路，CoreControl 在服务通道未连接时排队并等待绑定；排队最长 10 秒，核心重载最长 120 秒，更新期间暂停其他提交和状态请求。前后台切换时保留正在更新的 IPC。重载仍会关闭旧连接 / 出站并应用新配置，不重建 Android TUN；失败保留已保存的配置并停止 VPN。Tailscale 在线设备明细默认折叠，状态查询本身不会展开。

## Tailscale 的身份与限制

- OneTunnel 的认证调用经过 Mihomo `ParseProxy` 返回的 `autoCloseProxyAdapter`；overlay 显式转发 `OneTunnelAction`，避免接口嵌入丢失自定义方法。回归测试使用真实解析器包装，发送无效动作验证到达 Tailscale，且不启动网络或创建身份。
- `StartLoginInteractive` 发起认证后立即返回；UI 通过同 UID 状态轮询取得异步 AuthURL。只有用户点击认证才登记浏览器请求，同一请求只打开一次，Running / 等待管理员批准时不再打开。认证按钮在统一 VPN 运行时仍可用，直接使用当前已启用节点；空闲时保留独立无 TUN 认证流程。
- 删除连接同步处理规则、代理组、dialer-proxy、DNS 和启用元数据，已删除出口的引用改为 REJECT / REFUSED。删除当前订阅重新生成本地配置，保留独立 WG/TS、其基本路由、国内直连偏好与 LAN 设置；订阅缓存索引先原子更新再删除对应加密快照。服务端 Tailnet 设备吊销不属于本机条目删除。

- 本 App 内 tsnet 是一个新节点，不会继承原 Tailscale App 的身份或私有状态。
- 浏览器登录通过同一进程内 tsnet 的 LocalClient.StartLoginInteractive 与异步 AuthURL 状态实现；项目 overlay 仅增加 status/login/logout。Compose 经同 UID Messenger → 服务单工作线程 → JNI 调用，不开放 HTTP 控制 API。
- 初次登录使用独立认证会话：只加载所选 TS 节点，不创建 TUN、端口或订阅 providers，保留当前网络供浏览器使用。认证成功自动结束会话，身份写入同一私有目录；之后用户手动建立统一 VPN。认证会话最多 10 分钟，可主动取消。
- 登录 URL 只接受官方登录 HTTPS 主机或所配置的 HTTPS Headscale 主机；URL、节点详情仅经内存 IPC 传输，不写状态日志。界面可查看地址与设备状态，尚未实测真实账号登录。
- 身份状态存放在应用 no-backup 私有目录；卸载会删除，tailnet 管理后台可能仍留有节点记录。
- accept-routes 不等于自动把 Android 所有目标送入 Tailscale：还需正确的 Mihomo rules，且 tailnet 侧子网路由已发布、批准并获 ACL 允许。
- 出口节点和 MagicDNS 的源码能力存在，但未做本设备实测。
- tsnet 支持通过 Server.Listen 注册入站；上游适配器未注册时，未处理的本机 TCP 端口会被拒绝。这不表示 tsnet 只能出站。官方 Android 同样使用用户态 WireGuard 引擎，配合系统 TUN 将流量送到宿主网络栈；不能将差异归因于“官方使用内核 WireGuard”。
- 0.3.13 增加 `listen-ports: [{port: 8022, local-port: 8022}]`。固定源码补丁给 TailscaleOption 增加配置、构造时验证、启动时注册 tsnet TCP listener、关闭时清理。转发器在 0.3.14 合并至 `native/overlay/onetunnel_forward.go`；TS 监听只在当前 tsnet 节点地址上，目标固定 IPv4 loopback，每条连接最多 16 个监听与 64 个活动流。每条流双向复制并保留 TCP 半关闭，失败或停止时关闭套接字；本机服务失败不会回退其他主机或出站。
- `OneTunnelStartInbound` 经过 autoCloseProxyAdapter 显式转发。统一 VPN 完成 TUN 接入，以及热重载替换旧出站后，主动启动配置了端口的 Tailscale，避免依赖 UI 轮询或首次出站请求。未配置端口的适配器保持惰性启动；普通配置预检查不启动 tsnet；无 TUN 认证会话在解析前移除端口字段。启动失败停止 VPN 并返回固定错误类别，不暴露原始配置。
- 编辑、关闭、删除连接沿用原有保存和重载流程；旧监听和活动转发随旧适配器关闭。仅修改端口不改变 state-dir 或认证身份。端口不经过 Mihomo 的出站分流，仍受 Tailnet 的网络访问策略和本机服务认证控制；这里没有实现 Tailscale SSH、UDP 端口转发、完整 IP 转发、局域网发现或完整官方 App 的平台集成。

## WireGuard 本机 TCP 转发（0.3.14）

- WG 与 TS 使用相同 `listen-ports` 结构、配置验证、编辑控件和共享转发器。普通导入/编辑保持原端口设置，显式传入空列表才移除；旧连接默认无监听。开关、删除、订阅替换沿用原有连接策略，禁用的 WG 在运行配置中不存在，不启动其监听。
- 构造 WG 时只验证端口。统一 VPN 启动/重载的 `startTunnelInbounds` 通过包装接口主动初始化有端口配置的 WG，避免依赖首次出站。初始化时在现有 mipstack/gVisor 设备的 `ListenTCP` 上绑定虚拟双栈端口，不打开 Android/Wi-Fi 的 TCP listener，也不接管 `.conf` ListenPort。WG 关闭时取消转发上下文、关闭监听及活动本机连接，随后关闭设备。启动失败返回通用的固定 `TUNNEL_INBOUND_FAILED` 类别并停止 VPN。
- 传入数据仍需有效 WG 对端密钥、AllowedIPs 和路由。NAT 端点发现/保持由原有 WG Endpoint/Keepalive 参数负责；不会为了打开入站而修改密钥、Peer 网段或 Keepalive。该转发不新增 Mihomo 规则，也不将接收服务访问交给 MATCH；本机 SSH 仍要求 sshd 启动及正常认证。
- 回归测试在宿主 UDP loopback 上创建真实 WireGuard 对端，分别运行默认 mipstack 和 gVisor：加密传输、原有出站 TCP、IPv4/IPv6 入站、二进制数据与半关闭、未配置端口拒绝，以及关闭 WG 后清理本机活动连接。测试不是用户真实 WG 服务端/NAT/Android 后台环境的实测。

## 订阅与字符传输

- `SubscriptionClient` 接收 HTTP/HTTPS Clash YAML，使用 ClashMeta User-Agent、有限重定向、gzip 和解压后 1 MiB 限制；禁止 HTTPS 降级到 HTTP。错误不回显 URL 或响应正文。HTTP 用于兼容用户已有订阅，链接输入默认隐藏。
- `ClashImport` 去掉桌面监听、控制面、PROCESS 规则及远端应用私有设置，补足 DNS 与 MATCH。provider 缓存路径限制为私有相对路径，不执行脚本。规则内容、节点协议仍交给 Mihomo 解析。
- 多订阅索引和各自快照均通过 Keystore AES-GCM 存入 no-backup 目录；应用配置单独保存。更新缓存不改变运行中配置，切换经预览进入草稿，再显式保存；保留本地添加的 WG/TS，检测名称与规则出口冲突。
- JNI 对字符串显式做 UTF-16 与标准 UTF-8 转换，避免 modified UTF-8 损坏包含国旗等 emoji 的节点名。

## 安全与故障语义

- 安装/打开 App 不加载或启动核心，不申请 VPN，不测试节点。
- 启动前的确认明确说明现有 VPN 会被替换，GPT 可能失联；没有开机启动或自动重连。
- YAML 配置使用 Android Keystore AES-GCM 存储，不放入 saved-state、备份或原样日志；配置页启用 FLAG_SECURE。
- 核心运行时缓存、provider 文件和 Tailscale 状态由上游管理，位于应用私有 no-backup 目录，**不声称这些所有文件都被 Keystore 加密**。
- 原始核心错误可能包含密钥，因此本版只保留泛化状态，详细日志关闭；代价是排障信息有限。
- 不自动加 DIRECT 回退；WG/TS 命中的出口失败时是否重试/回退仍受用户配置中的组策略影响。
- 没有设置 Android 系统级 lockdown/always-on。若 VPN 进程崩溃或用户停止，系统可能恢复普通联网；本版不是完整的系统级 kill switch。
- 原 VPN 被替换后，不会替用户重新启动 Clash，恢复步骤由用户掌握。

## 许可证

- Mihomo：GPLv3；本项目自写代码采用 GPL-3.0-or-later，分发时须遵守核心及各依赖的具体许可。
- Tailscale 上游主要为 BSD-3-Clause；实际 fork 版本和第三方组件仍须保留其许可证。
- CMFA 是 GPLv3 项目，本次用作接入设计参考，不包含它的 release keystore，不以官方客户端名义发布。
- 下游发布正式产品前应完善传递依赖许可证清单、对应源码提供方式及隐私说明；不能把“用了开源库”当作可以闭源分发的许可。
