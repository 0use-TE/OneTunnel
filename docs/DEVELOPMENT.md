# 开发说明

OneTunnel 使用 Android Compose 编写界面，通过一个 Android VPN 服务将流量交给内嵌 Mihomo，按规则选择 Clash 代理、WireGuard、Tailscale 或直连出口。

- [架构与源码分析](ARCHITECTURE.md)
- [构建与验证记录](BUILD_RESULT.md)
- [手动测试清单](USER_TEST_CHECKLIST.md)
- [上游版本与补丁](../UPSTREAMS.md)
- [官网构建与发布](WEBSITE.md)

## 实现与兼容范围

- VPN 在独立的 `:vpn` 进程运行，使用 Android VpnService/TUN、IPv4/IPv6 路由、DNS 劫持和 socket protect JNI 回调。服务为 START_NOT_STICKY；不处理异常自动恢复、开机连接或系统 always-on。正常退出界面不主动停止前台服务。
- Clash 代理组选择、测速、Tailscale 状态 / 认证和配置重载经同 UID Messenger/JNI 操作，不开放外部 HTTP 控制接口。节点选择直接修改运行中的代理组；其他配置重载保留 Android TUN，但关闭旧网络连接。热重载失败时停止 VPN，保留已保存配置。
- Clash 手动测速最多四并发，使用 Mihomo URLTest 的 HTTP HEAD 探测。组内显式节点遵循组配置，Provider 节点使用对应来源的健康检查 URL / 超时 / 状态码，显式组 URL 作为该来源的额外检测任务优先；同 URL 的状态码策略保留 Provider 原值。默认五秒、最多三十秒，界面在各节点上限后额外等待两秒兜底。点击结果可看检测域名、上限和 DNS / TLS / 响应状态 / 网络 / 控制请求错误，不显示完整 URL 或原始错误。停止立即清除活动和排队状态，已完成结果保留；不同批次隔离旧回复，等取消确认和旧请求退出后再允许新测速。自动选择组仍由本地 Mihomo 按订阅组策略运行，不由订阅服务器分配节点。
- 订阅入口接收 Clash/Mihomo YAML，不转换 Base64 节点列表或 `vless://` 等单节点分享链接。内核可解析 VLESS、VMess、SS/SSR、Trojan、Hysteria/Hysteria2、TUIC、SOCKS5/HTTP 等出站 YAML。桌面脚本不执行，PROCESS 规则在导入预览提示后移除；订阅不能自行打开局域网共享或控制端口。
- WireGuard 导入支持标准客户端 `.conf`、IPv4/IPv6、多个 Peer、预共享密钥和 AllowedIPs；多个 Peer 的 Keepalive 需一致。脚本、按 App 选项、多个同族本地地址及不兼容扩展会拒绝。DNS 用于 WG 出站解析；搜索域及 ListenPort/Table/SaveConfig 的差异在导入预览说明。
- Tailscale 使用内嵌 tsnet 身份，可浏览器登录，也可在高级配置填写 auth-key；不复用官方 App 身份。WG / TS 各有默认关闭的“全部端口转发”开关，开启后将发给该连接自身地址的 TCP / UDP 流量转到 `127.0.0.1` 的同名端口；原有最多 16 条手动映射优先，关闭映射中的端口排除在全部转发之外。关闭全部转发后恢复原来的显式 TCP 映射。不是完整 IP 入站、子网路由器或内置 Tailscale SSH。
- 配置与订阅缓存经 Android Keystore AES-GCM 加密，身份和 provider 缓存位于应用私有目录；不声称所有运行时文件都经过 Keystore 加密。升级沿用包名、签名及原数据；应用禁止系统备份连接配置和核心状态。
- HTTP 代理设置沿用 HTTP/SOCKS5 mixed listener，端口默认 7890、可设 1024–65535。默认仅本机监听；允许局域网后只接受私网 / 本地来源，可设置账号认证。修改监听需停止连接，下次启动生效。
- 国内分流使用 GEOSITE/GEOIP，首次启动可能下载规则数据库；离线配置检查不下载。自建配置和导入订阅遵循各自保存的规则，不增加隐式直连回退。

## 构建环境

以下命令在仓库根目录执行。原生库和上游副本由构建脚本生成，不纳入 Git；完整 APK 必须包含真实原生库。

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

## 本机服务访问

在“转发”页选择 WG 或 Tailscale，显式添加需要的 TCP 入站端口和本机端口，然后启用该连接与总 VPN。目标服务需已在设备本机监听；软件不代替启动 Termux 的 sshd 或其他服务。

ADB 无线调试等端口会变化的服务，可开启该连接的“全部端口转发”，使用 OneTunnel 的隧道地址和 ADB 当前显示的配对 / 调试端口，不必每次修改映射。TCP 和 UDP 都支持；不跨隧道广播 mDNS，不替代 ADB 配对与认证。已有映射在全部模式下也作用于 UDP；关闭的映射阻止该入站端口的两个协议。UDP 按来源 / 目标分会话、保留报文边界，空闲一分钟后清理；每连接 TCP 与 UDP 合计最多 64 个活跃转发会话，不在 Android / Wi-Fi 上创建全端口监听。

例如将入站 2222 映射到本机 8022，在 Termux 启动 `sshd`，其他设备使用：

```sh
ssh -p 2222 <Termux 用户名>@<OneTunnel 的 WG 或 Tailscale 地址>
```

Termux 用户名可通过 `whoami` 查询。使用对应连接的本机地址；Tailscale 需允许访问相应端口，WG 对端需有对应路由和 AllowedIPs，经中心服务器访问还需其转发 / 防火墙配置。NAT 后保持可达可按需设置 Keepalive；软件不会自动修改对端网络参数。

## 验证与后续工作

实机验收和已验证范围以 [构建记录](BUILD_RESULT.md) 与 [测试清单](USER_TEST_CHECKLIST.md) 为准。当前优先检查升级后启动、手动启停、锁屏、网络切换、WG 入站和快捷入口首次授权。

后续方向包括订阅定时更新、加密备份与恢复、按应用分流、正式签名与发布流程、更多 ABI，以及完整 IP 入站。尚未实现的能力不作为已支持功能介绍。

## 许可证与来源


自写代码 GPL-3.0-or-later；Mihomo 为 GPLv3，Tailscale 和各传递依赖遵循各自许可。根目录 [LICENSE](../LICENSE) 是 GPLv3 正文。许可副本和通知见 [licenses](../licenses) 与 APK assets/licenses。正式公开分发前需完善对应源码与全部传递依赖许可合规，不应宣称是 Mihomo、Tailscale 或 Clash 官方客户端。

项目远程仓库为 https://github.com/0use-TE/OneTunnel 。上游副本和本地构建产物不计入本项目 Git 历史；上游提交与补丁记录在 [UPSTREAMS.md](../UPSTREAMS.md)，构建时获取固定源码并应用项目补丁。
