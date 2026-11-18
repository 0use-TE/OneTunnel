# 一隧 / OneTunnel — Mihomo 统一安卓 VPN（第一版）

Compose 客户端，将安卓应用流量送入一个 VpnService/TUN，由原生 Mihomo 按规则选择普通代理、WireGuard、Tailscale 或明确配置的直连出口。

**这是供用户手动测试的 alpha，不是已经验证联网的生产客户端。AI 不会安装/启动它，不会关闭或重配原 Clash。**

## 已实现

- Jetpack Compose 总览、配置编辑和说明页面，适合平板宽屏。
- SAF 导入 Mihomo YAML、最多 1 MiB；离线语法/结构检查；显式加密保存。
- Android Keystore AES-GCM 配置存储；配置页禁止截图；不备份应用配置和核心状态。
- 一个 Android VpnService，独立 `:vpn` 进程；IPv4/IPv6 TUN、DNS 劫持、socket protect JNI 回调。
- Mihomo Meta 固定提交的原生代理/WG/Tailscale 出站能力；没有用 sing-box 替代内核。
- 启动前明确警告现有 VPN 会被替换；没有开机启动、自动重连、自动测速或控制 API。
- 停止入口和前台通知；原生启动卡住时可结束独立 VPN 进程。
- 三出口结构示例和只用于基础验证的直连模板，均不包含真实凭据。

## 尚未实现/验证

- 本设备上的 VPN、代理、WG、Tailscale、UDP、MagicDNS、网络切换和后台稳定性均待用户验证。
- 浏览器式 Tailscale 登录、设备列表、订阅管理 UI、节点选择 UI、按 App/PROCESS 规则。
- 系统级 always-on/lockdown、完整 kill switch、正式签名和应用商店发布。
- NDK/其他 ABI、ZeroTier/EasyTier 出站、全部已有 Clash 配置的无修改兼容。

原生 Tailscale 首次推荐 auth-key，会创建新 tailnet 身份，不复用原 Tailscale App。核心的状态和 provider 缓存位于私有目录，不声称所有运行时文件都经过 Keystore 加密。

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

## 安全启动

先导入配置、离线检查并加密保存，确认自己能恢复原 Clash，然后手动点启动并完成系统授权。**VPN 授权/启动可能立刻中断现有 VPN 和 GPT 连接。**

本 App 不读取原 Clash、WG 或 Tailscale 的私有文件，不会自动导入你的密钥。不要在聊天里发送真实凭据。

## 许可证与来源

自写代码 GPL-3.0-or-later；Mihomo 为 GPLv3，Tailscale 和各传递依赖遵循各自许可。根目录 LICENSE 是 GPLv3 正文。许可副本和通知见 licenses 与 APK assets/licenses。正式公开分发前需完善对应源码与全部传递依赖许可合规，不应宣称是 Mihomo、Tailscale 或 Clash 官方客户端。

本仓库为本机新建 Git 仓库，没有配置远程 origin，也没有上传 GitHub。上游副本不计入本项目 Git 历史；源码交付包包含固定 Mihomo 源码，以便离线检查及复现。
