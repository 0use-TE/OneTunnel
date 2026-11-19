# 上游版本锁定

分析/开发日期：2026-11-18（Asia/Shanghai）。以下为本次实际克隆的提交，不以“最新版”作为可复现版本号。

| 用途 | 上游 | 分支/提交 |
|---|---|---|
| 实际嵌入核心 | https://github.com/MetaCubeX/mihomo | Meta / `0455d0d28c4526937380505153e65a843d8612e8` |
| Tailscale 行为参考 | https://github.com/tailscale/tailscale | `5528c3d1ba2a23ccadf5202a46748ebce7794556` |
| Android TUN/protect 接入参考 | https://github.com/MetaCubeX/ClashMetaForAndroid | `6e635124cc72f8c102b7e76d849c384deba28b3f` |

真正编入 Mihomo 的 Tailscale 是其 `go.mod` 指定的 MetaCubeX fork：

```text
github.com/metacubex/tailscale v0.0.0-20260821153257-ff0ecd818181
```

不要把独立克隆的 tailscale/tailscale HEAD 当成应用内实际版本。原生桥接主模块还显式复用了核心对 protobuf 的替换，避免 Go 忽略依赖模块中的 replace 指令。

注意：本次仓库默认 `main` 分支检出的是无关的 Python Mihomo API 项目，不能用于代理核心分析。已改为显式获取并固定 `Meta` 网络核心分支。正式复现必须使用上述提交，不要只执行无分支的 clone 后假定内容正确。

`upstream/` 是忽略提交的独立上游副本，不包含用户配置。项目远程 origin 为 https://github.com/0use-TE/OneTunnel.git ，上游副本不随 Git 提交。

项目在固定核心上添加 `native/overlay/onetunnel_tailscale.go`：仅向 JNI 暴露内嵌 tsnet 的 status/login/logout，不启用 HTTP controller。`scripts/prepare-core.sh` 和 Windows 原生构建脚本将该文件复制进上游 adapter/outbound；复现时必须执行这一步。

Android VPN App 使用额外的 `onetunnel` build tag，以及 `native/overlay/android-vpnservice.patch`：只调整 TUN package manager 的两处构建条件，避免读取系统私有 packages.xml。该路径与 VpnService 注入 FD、由 Android 管理应用路由的模式一致；不启用会影响其他核心行为的全局 cmfa tag。构建脚本幂等应用此补丁。

0.3.13 另加 `native/overlay/tailscale-inbound.patch`，为同一固定版本的 TailscaleOption 接入 `listen-ports`、启动和关闭钩子。没有修改 tsnet 默认未注册端口拒绝逻辑；只有显式配置的 Server.Listen 端口转发到设备 127.0.0.1。

0.3.14 增加 `wireguard-inbound.patch`，为 WireGuardOption 接入同一端口字段，设备初始化时通过现有用户态协议栈 ListenTCP 监听。共享转发器合并至 `native/overlay/onetunnel_forward.go`，WG 启动接口位于 `onetunnel_wireguard.go`。脚本复制共享 overlay/回归测试，清理旧版 TS 专用转发文件，并幂等应用三个补丁；保留默认 mipstack 和可选 gVisor，两者均由加密隧道回归测试覆盖。
