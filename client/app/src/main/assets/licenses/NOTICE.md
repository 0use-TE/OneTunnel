# 第三方与版权通知

本项目非 MetaCubeX、Tailscale、WireGuard 或 Google 官方客户端，不使用这些项目的商标暗示背书。

- 自写 Kotlin/Compose UI、VPN 服务和 JNI/Go 桥接：GPL-3.0-or-later。
- Mihomo Meta（固定提交见 UPSTREAMS.md）：GPLv3，许可证正文随项目和 APK 提供。
- 内嵌 github.com/metacubex/tailscale（ff0ecd818181）：BSD-3-Clause；保留 Tailscale Inc & contributors 的版权和许可。
- AndroidX / Jetpack Compose、Kotlin、SnakeYAML：各自项目的 Apache-2.0 及相关通知适用。
- Mihomo 传递依赖还包含 Go 标准库、gVisor、WireGuard 实现及多种协议/密码库；它们的原始许可和版权不因本项目采用 GPL 而消失。

第一版源包包含固定 Mihomo 源码；原生模块的 go.mod/go.sum 和构建信息用于定位实际依赖。正式公开分发前必须核验完整依赖通知与对应源码交付，不能仅依赖本摘要作为完整法律审计。私钥、真实配置、签名私钥以及原 ClashMetaForAndroid 的 release.keystore 均不属于源码交付物。
