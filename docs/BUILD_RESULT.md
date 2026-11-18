# 构建与静态验证记录（已完成）

日期：2026-11-18。

- 平板：ARM64、Android 16、12 GB 级内存。
- Termux：Google Play `googleplay.2026.10.09`。
- JDK 21、Gradle 8.7、Android SDK 34、AGP 8.6.1、Kotlin/Compose compiler 2.0.21。
- Go 1.26.4、Clang 21.1.8、Termux NDK sysroot 29。

## 已完成

- 上游源码克隆和提交固定。
- Compose、资源和 Kotlin 编译通过：`compileDebugKotlin`，24 秒。
- 未执行任何 VPN、隧道、socket protect 或真实出口的运行测试。
- 没有安装或启动本 App，没有关闭/修改现有 Clash。

## 最终结果

- ARM64 c-shared 原生库编译成功，约 43.0 MiB；真实包含 Mihomo、gVisor、WireGuard 和 Tailscale 依赖。
- 第一轮原生桥接发现一个 Go 枚举赋值问题，已按上游类型修复；静态 ELF 清理工具也适配了 Play 版 Termux 的额外 argv 包装。
- 移除了 Termux 私有 RUNPATH，只依赖 Android 的 liblog.so/libdl.so/libc.so。
- JNI_OnLoad、NativeCore.start/stop 导出存在；ELF LOAD 段保持 16 KiB 对齐。
- 最终 assembleDebug 成功（增量 17 秒），APK 约 25 MiB。
- APK 签名校验通过（v2），包名 dev.dsh.onetunnel，版本 0.1.0-alpha，只有 arm64-v8a。
- 清单只申请网络、网络状态、前台服务及通知权限；没有 root、Shizuku、存储管理或无障碍权限，没有开机启动接收器。
- APK 内包含约 45,137,768 字节的真实原生库及核心许可资源，不是仅包含 UI 的占位包。
- 编译器构建信息见 [NATIVE_BUILDINFO](NATIVE_BUILDINFO.txt)，包含实际 Tailscale/WireGuard 模块版本与 with_gvisor 标签。

```text
APK SHA-256:
255f100c1c721bceec15945fd1448a68112bb94be324480066f59ddad7d227e8

libonetunnel.so SHA-256:
2586e093353fed1efe7006881fbc24972ae19a4047a5594bc84fa9974ac56fa8
```

## 未做的验证与已知边界

- 没有在安卓进程里实际加载该库，没有安装、打开或启动本 App，没有切换现有 Clash。
- 没有验证真实 VPN/TUN、protect 回调、各出口、DNS、UDP、IPv6、Tailscale 登录或后台行为。静态符号和签名正确不等于运行正确。
- SDK platform-tools 未安装/未自动接受该包许可，Gradle 有对应警告；本次不使用 adb，警告没有阻止 APK 构建。
- compile/target API 34 仅为首版兼容性基线；不宣称适配全部安卓版本或满足当前商店发布要求。
- 开发签名、alpha 状态、泛化错误日志；不能替代安全审计和用户功能测试。

所有设备功能验证交给用户；测试步骤见 [用户清单](USER_TEST_CHECKLIST.md)。
