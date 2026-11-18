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

Go 的 `go.mod` 内 dependency replace 不会传递到主模块，因此本项目显式复制了上游 protobuf replacement。构建标签为 `with_gvisor,no_easytier,no_zerotier`；未裁掉 Tailscale。

为避免“在 Termux 能编译，却不能作为独立 App 使用”，还做了静态可移植性处理：移除 Termux Clang 注入的私有 RUNPATH；仅保留 Android 系统 libc/libdl/liblog 动态依赖；设置 App 自己的 HOME/TMPDIR/XDG 目录与系统证书目录，不访问 Termux 私有状态。JNI_OnLoad 和 start/stop 导出已检查，ELF LOAD 段保持 16 KiB 对齐。这些都不是实际 dlopen/联网测试，运行结果仍由用户验证。

## 配置覆盖边界

为避免导入旧配置后额外监听 7890/9090 或执行系统级操作，第一版固定覆盖：

- HTTP/SOCKS/mixed/redir/tproxy 入站端口禁用，allow-lan 禁用；不争用现有端口。
- 外部控制接口、网页面板、其他 listeners/tunnels、TUIC 入站禁用。
- profile 中的 TUN 设置不采用，由 Android VpnService 统一管理。
- iptables、写系统时间、geodata 自动更新禁用。
- 强制 rule 模式、关闭按进程查询；第一版不支持 PROCESS-* 规则。
- DNS 内核启用，但本地独立 DNS 监听端口禁用，通过 TUN 劫持处理。

普通节点、proxy-providers、rule-providers、proxy-groups、显式规则等仍由 Mihomo 解析。provider、geodata 或健康检查可能在**用户启动核心之后**产生网络请求；“离线检查”不会做这些事。没有订阅管理界面，不承诺复杂现有配置无需修改即可使用。

## Tailscale 的身份与限制

- 本 App 内 tsnet 是一个新节点，不会继承原 Tailscale App 的身份或私有状态。
- 第一版只做配置型接入，推荐用户自己生成的 auth-key；没有浏览器登录面板和状态 URL 解析。
- 身份状态存放在应用 no-backup 私有目录；卸载会删除，tailnet 管理后台可能仍留有节点记录。
- accept-routes 不等于自动把 Android 所有目标送入 Tailscale：还需正确的 Mihomo rules，且 tailnet 侧子网路由已发布、批准并获 ACL 允许。
- 出口节点和 MagicDNS 的源码能力存在，但未做本设备实测。
- 这主要是发起连接的出站客户端，不承诺可以完整替代官方 App 的入站服务、局域网发现、ping/ICMP 和所有平台集成功能。

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
