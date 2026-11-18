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

`upstream/` 是忽略提交的独立上游副本，不包含用户配置。项目本身没有远程 origin，没有自动推送到 GitHub。
