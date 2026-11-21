# 构建与静态验证记录

## 0.3.21-alpha：WG / Tailscale 全部 TCP、UDP 端口转发（2026-11-21）

- 转发页为每个 WG / Tailscale 连接新增默认关闭的“全部端口转发”开关，点击立即保存并在运行中应用。启用后将该连接虚拟 IP 的 TCP / UDP 入站转至本机 127.0.0.1 的相同端口，无需为无线 ADB 变化的配对 / 调试端口反复配置。保留原有显式映射；自定义目标优先，关闭的条目作为排除端口；关闭全部端口后恢复原有逐项 TCP 转发。
- Go 网络组件增加 Tailscale TCP / UDP fallback，以及 WireGuard mipstack / gVisor 两条路径；只接收节点自身地址，不转发其他目的网段。UDP 按来源与目的分开维护、保留报文边界和空报文，空闲一分钟回收，TCP / UDP 合计最多 64 条活动转发。停止、重载与失败回滚清理监听、回调及活动连接；仅认证不启用转发。远程订阅不能开启全部端口，刷新订阅保留本地开关选择。
- 96 项 JVM 测试全部通过；原生 13 项顶层测试（含 WireGuard 的两种协议栈 × 两种模式四个子用例）及 go vet 通过。宿主验证包含加密 WG IPv4 / IPv6 入站、动态 TCP / UDP 端口、双 UDP 来源、空与二进制报文、出站共存、UDP 回收、禁用端口及清理；Tailscale 使用 SDK 接口替身连接真实本机 TCP / UDP 服务，没有执行真实 tailnet 认证或跨设备 ADB。
- ARM64 网络组件、assembleDebug、testDebugUnitTest 与 lintDebug 完成。Lint 为 0 错误 / 9 个现有配置或依赖警告；APK 签名验证通过，包名与原开发证书不变，可覆盖安装保留数据。APK 内原生库与本次构建逐字节一致，ELF LOAD 段为 16 KiB 对齐。四个原生补丁的新目录应用与重复准备已核对一致。
- 本次未安装、打开应用或操作用户 VPN；明暗界面交互、真实 tailnet / WG 对端与无线 ADB 的设备验收由用户完成。没有修改安卓系统内核。
- APK：`artifacts/OneTunnel-0.3.21-alpha-arm64.apk`，versionCode 25，29,047,483 字节，SHA-256：`5a38d645beb215ca1268558f373718fb9c49c9d4cf73c1f81913faf759772bb2`。
- APK 内 ARM64 原生库 SHA-256：`f3801fd1902bbcdb8ec8b172dd6fa5c6cb6ba09e22a8652b246e6c44e8c1d3f2`。

## 0.3.20-alpha：移除自动恢复，保留小组件和快捷开关（2026-11-19）

- 用户反馈 0.3.19 更新后打开应用闪烁且无法工作，按要求移除 RecoveryPolicy / RecoveryStore、自动重建 / 重试预算 / 延迟、系统常驻状态同步及设置入口。服务恢复为用户显式启动且所有分支 START_NOT_STICKY，清单禁用系统 always-on；旧版恢复状态文件不再读取，不迁移或清除连接数据。
- 保留现有猫形 Logo、桌面组件、下拉快捷开关、通知停止按钮、独立前台服务以及 stopWithTask=false。所有停止入口直接停止 VPN；移除仅供常驻模式使用的系统设置跳转。正常服务仍可在离开界面后继续工作，进程异常结束后需要手动连接。原生核心、连接、订阅、规则与端口转发未改动。
- 删除恢复功能的 8 项测试，快捷入口测试保留并更新；90 项 JVM 测试通过。assembleDebug、lintDebug、签名、包名和空白检查通过；APK 内原生库与工作区库一致，沿用原开发签名。
- 只读检查连接设备的选定日志窗口，未捕获 OneTunnel 致命异常；系统 always-on 配置为空。构建阶段未安装、打开应用或启停设备 VPN，不能确认闪烁根因或声称实机已恢复。覆盖安装后的界面与联网验收交给用户。
- 构建后收到用户“先帮我 install”指令，已通过 ADB `install -r` 覆盖安装至 HA2NHJLY，返回 Success，并核对 versionCode 24 / 0.3.20-alpha。未清数据，未打开应用或启动 VPN，继续由用户测试。
- APK：`artifacts/OneTunnel-0.3.20-alpha-arm64.apk`，versionCode 24，29,011,795 字节，SHA-256：`e7f380d5958494fea8994f33d1ee5e49ecdf5770ff8dd8e06a63d7fac8ba66a9`。

## 0.3.19-alpha：猫形快捷图标与明确的断开操作（2026-11-19）

- 将通用条形 ic_status 改成现有批准 Logo 的猫形单色矢量，透明眼睛和笑脸；磁贴、添加磁贴预览、桌面按钮、通知统一使用，保留原桌面 PNG。矢量经 Windows WPF 渲染检查，预览位于 artifacts/onetunnel-shortcut-icon-preview.png，未编辑原 Logo 位图。
- ShortcutPolicy 统一新旧系统磁贴与组件的动作：运行时“点击断开”，准备 / 启动时“点击取消”，停止中禁用，认证进入应用，系统常驻进入系统 VPN 设置。修正旧安卓磁贴原先单独推导动作的路径；QuickConnectActivity 使用生命周期 RESUMED 挂起等待，避免只检查 onResume 时序而遗留快捷操作。
- 新增 3 项策略测试，覆盖启停、启动取消 / 停止禁用、认证与系统常驻；全部 98 项 JVM 测试通过。assembleDebug、lintDebug（0 错误 / 8 个原有警告）、签名、包名和空白检查通过。原生库与 0.3.18 完全一致，包名及开发签名不变，可覆盖保留数据。
- 本次未连接、安装或操作平板；真实磁贴点击、首次授权、锁屏、旧安卓兼容与组件效果继续由用户验收。移除快捷入口由系统下拉设置编辑完成，本身不停止 VPN。
- APK：`artifacts/OneTunnel-0.3.19-alpha-arm64.apk`，versionCode 23，29,025,251 字节，SHA-256：`3ac73926aeb9c01210a45849d2ef61c494c1d56ed2b606dabbdbb77b5efcdc30`。

## 0.3.18-alpha：后台异常恢复、系统常驻与快捷入口（2026-11-19）

- 保留独立前台 VPN 服务，显式 stopWithTask=false。设置新增默认关闭的“异常自动恢复”，使用独立、跨进程加锁的恢复状态文件记录偏好和本次连接意愿；切换偏好更新服务 sticky 策略，不停止 VPN、不重载核心。手动停止 / 撤权 / 替换 VPN 取消本次意愿，启动失败阻断自动尝试，偏好保留；仅认证会话不自动变成 VPN。
- 接受系统 null Intent 重建以及真正的系统始终开启 VPN 启动，先校验授权；最多五分钟内三次自动重建，并增加 2 / 4 / 6 秒延迟，连续运行一分钟清预算。系统常驻独立于应用恢复设置；运行中停止入口引导系统 VPN 设置，失败时允许手动重试。新增同 UID lifecycle 状态同步，且该查询不会阻挡配置热重载。
- 新增约 2×1 RemoteViews 桌面组件：状态、总开关、拉宽显示 Logo、应用明暗外观；新增状态磁贴。快捷连接使用不导出的直接 PendingIntent Activity，检查配置和 VPN 授权，等待 Activity 可见，权限请求跨旋转恢复；固定连接 / 断开动作避免过期点击反转。设置提供组件 / 磁贴添加入口与系统后台设置；无常驻轮询或自建重启循环。
- 新增 8 项 RecoveryPolicy 测试，覆盖偏好开关、手动停止 / 再连接、撤权、系统常驻、仅认证、重试预算 / 退避、失败阻断及时间窗口。全部 95 项 JVM 测试、assembleDebug、lintDebug（0 错误 / 8 个原有警告）、签名、包名与空白检查通过。API 26–33 磁贴只能使用 Intent 旧重载，该兼容分支单独注释并定点抑制新版 lint；API 34+ 使用 PendingIntent。
- APK 原生库与 0.3.17 和工作区逐字节相同；包名 dev.dsh.onetunnel、开发签名不变，可覆盖升级。曾通过 ADB 覆盖安装复查前构建，但尚未验证 sticky / always-on、组件或磁贴；用户要求自行测试后停止设备操作，最终复查修正版未再次安装。最终包需覆盖安装后按 USER_TEST_CHECKLIST.md 验收，不声称进程恢复或后台稳定性已实机通过。
- APK：`artifacts/OneTunnel-0.3.18-alpha-arm64.apk`，versionCode 22，29,492,545 字节，SHA-256：`d8138ade4159e313b4b25f509f8323457111c0f9843f0bde2756a8be0bdd386d`。

## 0.3.17-alpha：每条端口转发独立开关（2026-11-19）

- WG / TS 的每条映射增加独立 Switch，切换自动加密保存并按原流程热重载。关闭时保留映射和目标端口，再开启恢复；旧映射缺省为开启，编辑关闭项不改变其开关。行内编辑和菜单仍独立操作，开关提供连接及端口的无障碍标签。
- 保存配置以映射的 `enabled: false` 记录关闭状态，非布尔值拒绝。准备、启动及重载共用的 ConnectionPolicy.runtime 只保留开启映射；全关时移除 listen-ports，不把关闭字段传入原生内核。保留身份、密钥、路由、订阅与连接整体状态，关闭项仍占用配置数量及端口唯一性。
- 新增 4 项测试：TS 独立切换 / 全关 / 配置保留、关闭项跨编辑 / 改名 / 订阅刷新 / 连接整体启停、非法开关值拒绝、WG 同等生命周期回归。全部 87 项 JVM 测试通过；assembleDebug、lintDebug（0 错误 / 8 个原有警告）、签名及空白检查通过。
- APK 内原生库与 0.3.16 及工作区库逐字节一致，无原生代码变化。包名与开发签名一致，可覆盖升级。未连接或安装用户设备；真实开关触摸、运行中入站拒绝 / 恢复及重载后的会话表现需设备验收。沿用核心整体热重载，已有网络会话可能重连，不重建系统 TUN。
- APK：`artifacts/OneTunnel-0.3.17-alpha-arm64.apk`，versionCode 21，28,991,111 字节，SHA-256：`6806d953c30502e346d807378c4e6f34bbb77047de0bf983c85ee95eedff8d15`。

## 0.3.16-alpha：独立端口转发页与连接操作菜单（2026-11-19）

- 底部导航新增“转发”，按 WG / TS 连接分组显示 TCP 映射，支持统一添加、指定连接添加、整行点击编辑和菜单删除；删除端口需确认。新增字段初始为空，仅显示 80 占位提示，不会将占位值保存为监听。已有映射无需迁移，保留原值。无连接时提供添加连接入口，满 16 条时禁用对应连接的新增操作。
- WG / TS 编辑弹窗移除端口列表；WG、TS、订阅及 Clash 文件的删除连接操作统一收入弹窗右上角菜单，沿用原有确认与删除语义。新增页面使用 Material 主题色、圆角卡片与图标按钮，适配现有明暗主题。
- 端口 CRUD 只更新指定节点的 listen-ports，保留身份、密钥、Peer、分流、订阅和启用状态，复用现有加密保存 / 核心热重载。新增 3 项回归，验证空占位不生成监听、WG / TS 增改删与配置保留、重复 / 失效映射 / 数量上限拒绝。全部 83 项 JVM 测试通过；assembleDebug、lintDebug（0 错误 / 8 个原有警告）、签名及空白检查通过。
- 原生库与 0.3.15 APK 完全一致，无原生代码变更。包名 dev.dsh.onetunnel、开发签名与上版相同，可覆盖升级。仅构建，未连接或安装用户设备；Android 界面触摸、明暗主题实机观感及修改后的真实入站连接仍需设备验收，步骤列于 USER_TEST_CHECKLIST.md。
- APK：`artifacts/OneTunnel-0.3.16-alpha-arm64.apk`，versionCode 20，29,437,373 字节，SHA-256：`626801855a0d04c170998c6f5c93f18efb45757adec1ed27ef02b6f795a85dbe`。

## 0.3.15-alpha：Clash 代理组页面、节点切换与延迟测试（2026-11-19）

- 点击 Clash 订阅/配置进入独立代理页；按配置顺序切换代理组，使用自适应 LazyVerticalGrid 节点卡片、搜索和选中边框。显示策略、实际当前节点与嵌套选择路径，自动组/故障转移/负载均衡保留自动策略；未启动时不显示虚构的自动结果。Provider 真实成员从运行组读取，同名成员按内核选择语义去重。编辑/更新/删除收进管理菜单，缓存未激活的订阅可浏览并启用；保留连接/规则/设置三页。
- 新增同 UID Messenger → JNI → Go 窄接口，不开放 HTTP 控制器。组与节点使用显式安全 DTO，不序列化完整适配器，不传服务器、密码或订阅 URL。运行时验证手动 Selector 与真实组成员后保存/切换，修改立即作用于新连接，保留现有连接与 WG/TS，不重载整个 VPN。
- 选择写入加密 YAML 的 default-selected，按订阅散列分别记录于 x-onetunnel.clash-selections；同订阅更新、订阅切换和重启恢复有效选择。关闭上游独立 store-selected 缓存，避免覆盖应用配置。静态成员离线可选，Provider 运行后验证动态成员；失败回滚保存，返回/离开页面时保留进行中的保存确认，防止 UI 使用过期配置。
- 单节点与整组测速通过实际 Mihomo URLTest，沿用该组 testUrl/expectedStatus，默认使用核心默认测试 URL。最多四并发、单请求五秒，显示未测试、进行中、毫秒、失败/超时。专用探测线程不阻塞 VPN 控制，停止测速取消排队和活动请求；重载/停止切换 generation，旧响应不会污染新配置，跨组不混用不同 URL 的测量。
- 普通规则出口只显示 Clash 组、本机 WG/TS、直连和阻止；MATCH 保留简化入口。不改写旧规则；已有直接引用单节点的条目，在编辑时单独保留其原出口，避免因 UI 精简改变路由。
- 新增 8 项 JVM 测试，全部 80 项通过：组结构与策略、嵌套路径/循环保护、选择与配置保留、拒绝非法/自动组选择、规则菜单与旧规则兼容、刷新/失效节点、订阅独立选择、Provider 验证。新增 6 项 Go 测试通过：真实解析组及 inline Provider（含重复来源）/DTO 隐私、选择验证、恢复默认选择、组测试 URL/状态码、loopback HTTP 实际出站测试与取消。Windows 宿主测试依赖补充至 go.mod/go.sum。
- 原生 ARM64 交叉编译、assembleDebug、lintDebug（0 错误 / 8 警告）、签名、JNI/Go 动态导出及空白检查通过。APK 原生库与本次构建逐字节一致，ELF LOAD 段为 16 KiB 对齐；开发签名和包名与 0.3.14 相同，可覆盖升级。忽略新增 Kotlin 编译缓存目录。
- 只生成 APK，没有连接或安装用户设备；未执行实机 Binder/触摸网格、真实公网订阅逐节点测速、长时 Provider 刷新和后台行为验证。宿主测试不等同于上述端到端验证，验收步骤列于 USER_TEST_CHECKLIST.md。
- APK：`artifacts/OneTunnel-0.3.15-alpha-arm64.apk`，versionCode 19，29,366,151 字节，SHA-256：`e5063047e51c6450f562bffb57d00a8f7eb29c4f8dcdf28b46f5b89ca9627d59`。

## 0.3.14-alpha：WireGuard 本机 TCP 端口转发（2026-11-19）

- WG 编辑页加入与 TS 一致的“本机端口转发”，支持添加/移除最多 16 条 TCP 映射，默认无监听，新增行预填 8022 → 8022。共享 Kotlin 配置/控件改为 TunnelPortForwards，沿用原 listen-ports YAML 字段，已保存的 TS 配置无需迁移。有效修改完成后加密保存，运行中热重载，密钥、Peer 扩展、Keepalive 及规则保持原有编辑语义。
- 固定 Mihomo 增加 wireguard-inbound.patch，通过原有 WG 用户态协议栈 ListenTCP 监听虚拟 IPv4/IPv6 端口，目标固定 127.0.0.1。复用共享转发器，保留二进制数据和半关闭；不打开手机 LAN TCP 端口，不修改 .conf ListenPort、系统路由或 NAT 参数。停止/禁用/删除和重载时清理旧监听与活动流。
- 统一 VPN 启动与重载主动初始化配置了入站的 WG，无需首次出站触发；预检查及普通构造不启动设备。通用启动接口同样服务 TS，错误统一为 TUNNEL_INBOUND_FAILED。构建脚本复制新共享 overlay、清理旧 TS 专用文件，并幂等应用三个补丁。
- Go 共 9 项顶层回归测试通过。新增真实加密 WireGuard UDP loopback 对端测试，分别覆盖默认 mipstack 和可选 gVisor：同一实例原有 TCP 出站、IPv4/IPv6 入站、SSH banner、二进制响应和半关闭、未配置端口拒绝、关闭 WG 后本机活动连接释放；新增真实解析器包装/默认关闭/配置校验测试。原 TS 包装与共享转发测试继续通过。
- 新增 5 项 WG 配置 JVM 测试，全部 72 项通过，覆盖默认关闭、编辑保留凭据与规则、重命名/移除、禁用再启用、订阅替换与无效端口拒绝。原生 ARM64 交叉编译、assembleDebug、lintDebug（0 错误 / 8 警告）、APK 签名及空白检查通过；APK 内原生库与构建库逐字节一致，ELF LOAD 段为 16 KiB 对齐。
- 包名及开发签名与 0.3.13 一致，可覆盖升级。仅构建 APK，没有连接或安装用户设备；真实手机 WG 服务端、NAT、Peer 间路由和 SSH 登录尚待用户验证。测试不是 Android 设备端到端验证，用户步骤见 USER_TEST_CHECKLIST.md。
- APK：`artifacts/OneTunnel-0.3.14-alpha-arm64.apk`，versionCode 18，28,884,647 字节，SHA-256：`6ef87ff213d02f407a8472b9bf7d1a53adae64029e91df3e9840442cfcfa17ed`。

## 0.3.13-alpha：Tailscale 本机 TCP 端口转发（2026-11-19）

- Tailscale 编辑页增加“本机端口转发”，支持添加/移除最多 16 条入站端口 → 本机端口映射，新增行预填 8022 → 8022；旧连接默认没有转发。校验整数、范围与重复端口，完成后加密保存，运行中沿用热重载。端口调整保留节点身份、凭据、DNS 与分流规则。
- 对固定 Mihomo 增加独立可复现补丁，使用 tsnet.Server.Listen 接受显式 TCP 端口，固定转往 127.0.0.1，不修改 tsnet 默认未注册端口拒绝行为。双向复制保留半关闭，单节点限制 64 个活动连接；本机连接失败立即结束入站，监听启动失败回滚全部已绑定端口。停止、关闭、删除或重载旧适配器时关闭监听和现有转发。
- 统一 VPN 启动及重载后主动启动已配置入站的 TS，避免依赖出站流量或前台状态轮询；无端口的节点保持惰性启动。普通配置预检查不启动监听；单独浏览器认证会话移除入站配置。启动失败返回固定安全类别并停止 VPN。
- 7 项 Go 回归测试通过：真实解析器包装转发接口/配置解析、默认关闭、无效与重复端口、双向二进制及 SSH banner 传输、半关闭保留响应、取消清理活动流、本机服务不可用、监听失败回滚。传输测试使用宿主 loopback listener 替代 Tailnet listener，不视作真实 Tailnet 或 SSH 身份认证验证。
- 新增 6 项配置/生命周期 JVM 测试，全部 67 项通过；Android 原生交叉编译、assembleDebug、lintDebug（0 错误 / 8 警告）、APK 签名和 diff 空白检查通过。APK 内 ARM64 原生库与本次生成库逐字节一致，ELF LOAD 段为 16 KiB 对齐。包名和签名与 0.3.12 一致，可覆盖升级；没有清除应用数据或执行安装。
- 按用户在家自行安装的要求，仅构建 APK，没有连接用户设备。实际 Tailnet ACL/Grants、Termux sshd 监听与远端 SSH 登录尚需用户验证，操作列于 USER_TEST_CHECKLIST.md。
- 用户后续报告远端 SSH 在版本交换前被重置；Termux 本机连接 127.0.0.1:8022 返回 Connection refused。指导启动 sshd 后，用户确认远端连接成功。此为用户实测反馈，没有开发端直接抓包或设备日志；不据此推断全部 Tailnet 策略及网络场景均通过。WG 当前未接入本机入站转发，不能套用 TS 的验证结果。
- APK：`artifacts/OneTunnel-0.3.13-alpha-arm64.apk`，versionCode 17，28,866,043 字节，SHA-256：`882bc536813f4c3f2e8d7fb9a7bf62b17c08e19f6b19fd58af7ffabdac270035`。

## 0.3.12-alpha：规则内边距与长按拖拽（2026-11-19）

- 规则点击区域增加 12dp 横向/10dp 纵向内边距、56dp 最小高度、16dp 圆角和细边框；按下/键盘聚焦时边框使用强调色，反馈覆盖完整规则区域，删除仍为独立按钮。深浅主题使用各自的配色。
- 排序弹窗移除可见的上下移动按钮；长按整行后显示跟手浮层及轻触反馈，拖过其他条目调整顺序，靠近列表边缘后按帧自动滚动，即使手指停留也会继续滚动。普通滑动保持滚动列表。采用稳定条目索引区分重复规则，重排首个可见条目后在新列表进入 composition 时恢复视口锚点。
- MATCH 单独固定在底部，不进入拖动列表/落点。手势取消还原该次拖动，取消弹窗不保存；完成后一次加密保存并沿用热重载。保留无可见按钮的辅助功能移动操作。纠正规则运行时仍提示需停止的旧文案，仅监听和高级配置保留停止要求。
- 新增 4 项拖拽几何测试，覆盖不同高度、已滚动视口、间隙、空列表、异常坐标、小视口与边缘滚动方向/限速；全部 61 项 JVM 测试通过。assembleDebug、lintDebug（0 错误 / 8 警告）、APK 签名及 diff 空白检查通过。签名证书与 0.3.11 相同，包名不变，可手动覆盖升级；APK 内原生核心与上一版逐字节相同。
- 按用户要求仅构建 APK，没有连接或安装至用户设备。尝试只读、无窗口本地模拟器，但 AVD 缺少系统镜像，未能启动；未进行实机或模拟器的触摸手势验证，不将几何单元测试视为端到端验证。待验证手势列于 USER_TEST_CHECKLIST.md。
- APK：`artifacts/OneTunnel-0.3.12-alpha-arm64.apk`，versionCode 16，28,842,107 字节，SHA-256：`b5e30e4f12e4de7b12439fc95e77859630d1823aa94a3605cab8d9253a1edbd5`。

## 0.3.11-alpha：采用用户确认的蓝猫图标（2026-11-19）

- 将用户确认的对称蓝色实心猫 PNG 原样保存到 `client/app/src/main/res/drawable-nodpi/onetunnel_logo.png`；与确认稿逐字节散列一致，没有再次生成或调整图案。
- 桌面自适应图标继续共用圆形/普通图标入口，背景改为白色，前景使用原图并预留 14.8% 内边距，避免耳朵在常见圆形遮罩下紧贴边缘。设置页顶部显示同一图像资源及 OneTunnel 名称，不增加说明小字。
- assembleDebug、lintDebug（0 错误 / 8 警告）、签名、APK 前景资源引用及 diff 空白检查通过。这次为图像资源和展示调整，没有新增或重复执行单元测试。
- ADB 覆盖安装 0.3.11-alpha / versionCode 15，四份加密配置/订阅文件前后散列一致，恢复用户原有 VPN 运行。实机设置页能找到 72dp 图标；系统应用详情中实际加载的自适应图标经截图检查为蓝猫白底，耳朵和身体未裁切。尚未单独验证所有第三方 Launcher 或主题遮罩。
- APK：`artifacts/OneTunnel-0.3.11-alpha-arm64.apk`，SHA-256：`1c19ed6ad6ae135e65f5d76bef07151ee2363ede1db928e115c25e48263ed081`。

## 0.3.10-alpha：运行时分流编辑与排序（2026-11-19）

- 开放运行时国内直连、MATCH 选择、规则添加与删除，沿用加密保存和同 UID 热重载通道；启动/停止/认证/保存/重载期间禁用重复操作。重载保留 Android TUN，旧网络连接会关闭重连。设置中的监听与完整 YAML 编辑仍需停止 VPN。
- 点击规则弹出编辑，支持 DOMAIN、DOMAIN-SUFFIX、DOMAIN-KEYWORD、IP-CIDR、IP-CIDR6、GEOSITE、GEOIP 与 MATCH；保留原条目位置及 no-resolve 语义，MATCH 仅修改出口，出口列表沿用简化名称并保留当前自定义出口。复杂规则提示使用高级编辑，超过 50 条可逐批显示。
- 增加“排序”弹窗，使用上下箭头调整规则位置，完成后一次保存/应用；MATCH 固定末尾。以原索引标识条目，重复内容的规则不会丢失，保存前拒绝过期列表、漏项、重复索引及将 MATCH 提前的顺序。
- 新增 6 项 JVM 测试，覆盖编辑后规则/凭据/监听保留、解析选项、MATCH 限制、无效及过期编辑、重复规则排序、非法排列；共 57 项测试通过。APK、lint（0 错误 / 8 警告）、签名与 diff 空白检查通过。
- ADB 覆盖安装 0.3.10-alpha / versionCode 14，安装前后四份加密文件散列相同。恢复原 VPN 后，实机添加 `.invalid` 测试域名规则，修改目标与出口，将其从第 8 条上移到第 7 条并核对保存结果，最后删除；四次操作都观察到核心重载完成，VPN PID 始终为 14604，系统 VPN 网络 123 仍活跃且正确继承 Wi-Fi 的非计费状态。未代用户发送消息。
- 清理后原 10 条规则、节点、密钥与其余完整配置的规范化指纹和测试前一致；没有遗留测试规则。未测试全部复杂规则形式或新规则集下载失败的场景。
- APK：`artifacts/OneTunnel-0.3.10-alpha-arm64.apk`，SHA-256：`cab9d25aca7760c7325f45c2e7249770d4b00641d87a1b468ad0598d18b2d7f7`。

## 0.3.9-alpha：QQ 发图与 VPN 网络状态修复（2026-11-19）

- 用户复现 QQ 图片发送卡住。0.3.8 临时诊断版记录的 QQ 相关连接命中 GeoIP(cn) → DIRECT，没有将发图卡住直接归因为 Clash 代理。
- 实机发现 VPN 的 UnderlyingNetworks 指向自身，且 Wi-Fi 下缺少 NOT_METERED。删除将默认网络回调直接写入 VPN 底层网络的逻辑：核心只调用 protect、没有 Network.bindSocket，按 Android VpnService 文档使用 null 跟随系统默认网络。API 29+ 显式 setMetered(false)，从底层网络继承计费状态。
- 0.3.8 起提供仅应用私有标记启用的一次性分流诊断：256 条路由事件、128 条活动连接，每秒更新私有 0600 文件，五分钟自动停止；默认不订阅、不写文件，没有新增外部 API。只保留端点、规则、出口、字节计数与固定错误类别，丢弃原始错误、认证 URL、报文内容。新增 2 项 Go 测试验证解析及敏感日志过滤。
- 原生构建、10 项 Go 测试（2 项 diagnostics / 8 项 appsettings）、51 项 JVM 测试、lint（0 错误 / 8 警告）、APK 签名与 diff 空白检查通过。
- ADB 覆盖安装 0.3.9-alpha / versionCode 13，前后四份加密配置与订阅文件散列一致。恢复用户原有 VPN 运行后，实机确认底层网络为 Wi-Fi、没有自身引用，并具有 NOT_METERED。QQ 重试期间相关 TCP/UDP 连接为 DIRECT、上传计数增长；用户明确确认“这次可以发出了”。两处网络状态一起修复，未单独归因于其中一项，也未宣称验证所有应用或网络切换场景。
- APK：`artifacts/OneTunnel-0.3.9-alpha-arm64.apk`，SHA-256：`8a3101d1e51542886feafdef28eaf5754e1ad9d338daeaf39cd6b46a10df2268`。

## 0.3.7-alpha：运行中添加、折叠设备与可选 LAN 认证（2026-11-19）

- 运行中可用 WG 文件 / 扫码 / 图片、Tailscale、Clash 文件 / 订阅入口；确认后沿用自动保存和核心重载，保留 Android TUN。新订阅可只缓存或立即启用。启动 / 停止 / 认证过渡及核心更新中禁止重复提交；规则、监听与高级 YAML 仍需停止后编辑。
- 修复文件选择器返回时未重绑 IPC 就误停 VPN：CoreControl 排队等待服务连接，排队 10 秒、重载 120 秒；更新期间跨前后台维持绑定，并显示“正在应用”。队列超时按代数区分，防止旧超时影响下一次请求；绑定失败 / 重载失败停止连接，保存的配置保留。
- Tailscale 设备明细默认折叠，显示在线数量，点击展开 / 收起，只列在线设备；状态轮询不重置展开选择。
- 局域网代理增加用户指定的“添加账号”开关。缺省保留认证，显式关闭才免认证并隐藏账号密码字段；仍只接受私网 / 本地来源，不启用外部控制 API。保留关闭认证前的凭据，不将隐藏字段的无效草稿写回配置。
- 51 项 JVM 测试、8 项 Go appsettings 测试、原生重编、APK、lint（0 错误 / 8 警告）与签名校验通过。新增测试覆盖匿名模式、认证默认值、私网来源限制，以及新增 WG / TS 后原开关、路由、DNS 与代理设置保留。
- ADB 覆盖安装 0.3.7-alpha / versionCode 11，安装前后加密配置 / 订阅快照一致。设备安装前正在运行 OneTunnel，安装后已恢复运行；实机六种添加入口全部可用，Tailscale 列表默认收起，点击展开 / 再收起通过。
- 实机临时关闭并重新开启原 WG 开关，观察两次 profile_reload_begin → profile_reload_complete；VPN PID 均为 11424，服务始终 running，结束恢复 WG 原开启状态并保持统一连接运行。没有添加测试节点、切换订阅或修改出口凭据；该检查验证重载链路，未实际导入新账号或在设备上开启匿名 LAN 监听。
- APK：`artifacts/OneTunnel-0.3.7-alpha-arm64.apk`，SHA-256：`78c5cdb166c11f299af381498881d2d71b0bac24b3546effd4a34ecd343b59a9`。

## 0.3.6-alpha：设置简化与 HTTP 监听端口（2026-11-19）

- 设置页的配置区只保留导入 Clash 配置文件和高级配置；三出口示例、直连测试模板移到高级配置。HTTP 代理区独立开关、监听端口与允许局域网连接，端口在关闭时也可预先设置；合法输入防抖 450 ms 自动加密保存，取消原“应用端口和认证信息”按钮。
- `LanProxy.httpEnabled` 使用旧 lan-enabled 作为缺省值，保留已有 LAN 用户的行为。显式关闭 HTTP 时不监听，开启后默认绑定 127.0.0.1；共享启用才绑定局域网并强制私网来源与认证。监听仍使用支持 HTTP/SOCKS 的 mixed listener；端口范围 1024–65535，默认 7890；导入的第三方端口与认证字段仍不会启用监听。
- 原生配置校验提取为 `native/appsettings`，新增 7 项 Go 测试，覆盖关闭、旧版兼容、本机端口、LAN 认证、私网限制、端口边界及忽略导入监听。新增 4 项 JVM 测试覆盖端口保存、模式切换、旧版迁移和订阅切换保留 HTTP 设置；共 49 项 JVM 测试通过。
- 原生库重编、APK 构建、lint（0 错误 / 8 警告）和开发签名校验通过。ADB 覆盖安装至 HA2NHJLY，确认 versionCode 10 / 0.3.6-alpha；实机设置页可见 HTTP 开关、监听端口 7890、共享开关和配置导入，普通设置页无测试模板。
- 没有修改用户端口或开启代理 / VPN。安装与界面检查前后加密配置、订阅快照、Tailscale tailscaled.state 散列全部一致；设备已有 authenticated 状态，登录身份保留。实际 HTTP / LAN 连接及端口占用场景未在设备上联网验证。
- APK：`artifacts/OneTunnel-0.3.6-alpha-arm64.apk`，SHA-256：`a53fcfd2456ec689b7bd5d963d7b1dffc4336f691da3b0e4e9547be7581c0c00`。

## 0.3.5-alpha：连接删除、整卡点击与 Tailscale 认证（2026-11-19）

- WG、Tailscale、Clash 订阅编辑窗口增加删除与确认；本地 Clash 文件配置也有删除入口。WG/TS 删除同步修复规则、代理组、DNS、拨号出口和关闭列表引用；删除最后一项仍保留有效的 REJECT 配置。删除当前订阅保留独立本机 WG/TS、身份目录配置、基本分流、国内直连偏好与 LAN 设置，移除订阅源和加密快照。
- 每条连接使用独立圆角 Surface，点击反馈覆盖包含内边距的整张卡片，右侧 Switch 独立处理启停。实机验证订阅编辑、删除入口、删除确认取消；没有删除用户真实连接。
- 认证根因：Mihomo ParseProxy 最后添加 autoCloseProxyAdapter，嵌入的 ProxyAdapter 接口不包含自定义 OneTunnelAction，原断言失败。overlay 显式转发该方法；增加真实解析器包装回归测试，使用无效动作验证到达 Tailscale，不进行网络或账户操作。
- 登录请求改为非阻塞发起认证，后续状态接收异步 AuthURL。UI 记录显式登录意图，延迟返回也打开浏览器且只打开一次；登录请求受状态查询占用时会重试发送。统一 VPN 运行时保留认证按钮，使用已启用的同一 TS 出站；未运行时继续使用无 TUN 的认证会话。
- 原生重编、45 项 JVM 测试、1 项 Go 包装层回归测试、APK 构建、lint 与签名校验通过。ADB 覆盖安装至 HA2NHJLY，确认 versionCode 9 / 0.3.5-alpha。
- 用户同意继续短暂验证后，实机点击现有 Tailscale 的“浏览器认证”，Chrome 自动打开 login.tailscale.com；页面包含 Tailscale 品牌及身份提供方选择，未出现浏览器网络错误。没有代用户选择账号或完成登录，不宣称已验证 Tailnet 握手、ACL、MagicDNS 或子网访问。
- 保留无 TUN 认证会话供用户完成登录（最多 10 分钟），没有启动 Android VPN。安装及取消删除前后加密配置 / 订阅文件散列完全一致。
- APK：`artifacts/OneTunnel-0.3.5-alpha-arm64.apk`，SHA-256：`755ea7a3bb2aa7a0cecb6615b0c4febb8c17f6a8adda344fdc8aae241037df18`。

## 0.3.4-alpha：Android VPN 应用的 TUN 接入修复（2026-11-19）

- 补齐数据库后，实机准备阶段已通过，但 core_start 后约 8 ms 失败。源码核对发现 Android 非嵌入式 TUN 路径无条件初始化 package manager，读取普通 App 不可访问的 `/data/system/packages.xml`；设备确认不可读。日志另有 netlink bind 拒绝记录，不将该记录直接等同于唯一致命错误。
- 增加仅针对 OneTunnel 的 Android 接入 build tag/两行源补丁，让应用路由继续由 VpnService.Builder 管理，跳过服务端 package manager。没有启用全局 cmfa tag，不改变文件安全路径和系统 DNS 的其他行为。两个原生构建脚本自动应用可重复补丁，原生 build info 与 Go 文件选择均已核对。
- 将 TUN 接入错误分成固定安全类别，Android 日志能区分系统包列表访问、权限、失效 FD 和其他接入失败；不记录原始配置与内核错误正文。
- 原生、APK、35 项单元测试与 lint 通过，签名通过；覆盖安装 0.3.4-alpha（versionCode 8）。用户明确允许短暂启动验证。
- 实机观察到 profile_prepare → tun_establish → core_start → running，约 344 ms；系统 vpn_management 确认为 OneTunnel 活跃 VPN，TUN RX/TX 计数增长。VPN 运行时百度 HTTPS 返回 200、gstatic generate_204 返回 204，随后状态仍为 running。
- 无 VPN 时两个 HTTPS 目标也可访问，因此该检查证明隧道运行与基本 HTTPS 可用，不单独宣称证明某个代理节点或 WG/TS 握手。未切换订阅或修改密钥、规则；按短暂验证约定结束测试。
- 产物：`artifacts/OneTunnel-0.3.4-alpha-arm64.apk`。

## 0.3.3-alpha：首次启动卡在规则数据库下载（2026-11-19）

- 实机日志：原生库加载成功；状态 starting 持续约 90.16 秒后变为 error；GeoSite.dat 从约 2.48 MB 增长至 4.11 MB，完整同源文件约 4.36 MB。与上游 geodata.downloadToPath 的 90 秒超时一致，判断本次停在首次数据库下载；未据此断言节点凭据有错。
- 改为无 TUN 的核心配置预检查，先准备数据库和规则，再建立 Android VPN；准备阶段保留用户当前网络/VPN。区分“准备规则数据库”和“正在连接”。准备阶段最长 120 秒，TUN/核心启动最长 60 秒，超时关闭会话。
- 原始错误仍不外泄，但数据库失败返回固定分类，Android 日志记录安全的阶段名和错误类别，界面显示明确原因。
- 在电脑获取内核默认 MetaCubeX/meta-rules-dat 规则源数据，通过同版本 Go geodata CN matcher 与 MMDB 校验；用于修复实机不完整的规则缓存，不改订阅和用户规则。
- 原生重编、JNI prepare 导出、APK 签名、35 项单元测试与 lint 均通过。已覆盖安装 0.3.3-alpha（versionCode 7），在确认 VPN 服务未运行后原子替换 GeoSite.dat 和 geoip.metadb；设备文件 SHA-256 与已验证文件完全相同。未代用户启动或重连 VPN，待用户重试实际出口。
- 安装前后 4 份加密订阅/配置文件散列一致，没有改订阅、密钥或规则。产物：`artifacts/OneTunnel-0.3.3-alpha-arm64.apk`。

## 0.3.2-alpha：去掉页面顶部加载条（2026-11-19）

- 移除全局 busy 状态控制的顶部进度条，点击列表读取本地编辑内容时不再插入控件或推动页面布局；保留下载弹窗的进度反馈与操作互斥。
- `assembleDebug` 通过，已用 `adb install -r` 覆盖至 TB378FC，确认 versionCode 6 / 0.3.2-alpha。安装前后加密配置文件散列一致。产物：`artifacts/OneTunnel-0.3.2-alpha-arm64.apk`。

## 0.3.1-alpha：点击连接编辑与简化出口（2026-11-19）

- “其他流量使用”仅列出一个 Clash 出口、已配置的 WG/TS、直连与阻止；不再将 GLOBAL/PASS/COMPATIBLE、订阅全部节点和用途组铺在选择器中。完整配置与特殊出口仍保留在高级 YAML。
- 删除首页订阅更新按钮。点击订阅行编辑名称/链接；只改名称不下载、不重置当前规则，修改 URL 会下载并验证新源，替换列表条目而非生成重复项。
- 点击 WG 行打开地址/私钥/DNS/MTU/Keepalive、多 Peer 公钥/Endpoint/AllowedIPs/PSK 和路由方式编辑。保留未编辑的扩展字段；重命名同步引用和开关状态。WG 路由修改继续经过冲突提醒。
- 点击 TS 行编辑名称/设备名/控制服务与 accept-routes，登录与状态收进弹窗。重命名保留身份目录，更换控制服务使用独立身份目录并提示重新登录。所有编辑弹窗禁止截图，完成后自动保存。
- 新增 5 项编辑/引用/身份/出口选项测试；35 项测试全部通过，APK 构建与签名检查通过，lint 0 错误 / 8 警告。
- 已覆盖安装 0.3.1-alpha（versionCode 5）至 TB378FC。实际点击订阅行验证编辑弹窗，并检查规则页出口菜单仅有 Clash/直连/阻止（设备当前无 WG/TS 条目），无 GLOBAL 和更新按钮；取消编辑未修改配置。WG/TS 编辑逻辑通过单元测试，真实联网未测试。
- 安装前后 4 份加密文件散列完全相同；没有清除数据、写入测试配置或启动 VPN。APK：`artifacts/OneTunnel-0.3.1-alpha-arm64.apk`。

## 0.3.0-alpha：连接开关、自动保存与 WG 路由检查（2026-11-19）

- 首页总开关替代圆形连接按钮；Clash 订阅互斥选择，WG/TS 各自独立开关。开关表示允许该出口参与规则路由，不等于握手或连通性已验证。
- 图形操作和确认导入后自动加密保存，高级 YAML 的有效编辑短暂防抖保存；无效编辑不会覆盖最后一份有效配置。移除保存栏。
- 关闭的出口在运行配置中替换为同名 REJECT 组，保留原始配置；关闭 TS 对应 DNS 策略改为 REFUSED。运行中切换通过同 UID reload IPC/JNI 重载，清理旧连接与旧出站，不重建 Android TUN；失败停止 VPN。
- WG 有效 IP 路由新增重叠时显示确认弹窗，涵盖 IPv4/IPv6、完全相同、包含关系与默认路由。只检查参与路由的已启用 WG，不将 Peer AllowedIPs 单独视作系统路由；冲突不自动改写网络或提供多路径。
- 新增 6 项测试：独立开关与重载、关闭订阅保留本地连接、关闭 TS 的 DNS、LAN 设置保留元数据、IPv4/IPv6 路由交集；总计 30 项。
- 原生重编、`assembleDebug`、30 项单元测试和 `lintDebug` 均通过；JNI reload 导出与 APK 开发签名校验通过。
- 已用 `adb install -r` 覆盖安装至 TB378FC，确认 versionCode 4 / 0.3.0-alpha，打开首页检查真实开关布局；未运行真实 VPN 或验证在线重载联网。
- 安装前后比较应用 4 份加密配置/订阅文件的 SHA-256，全部一致，未清数据或写入测试配置。产物：`artifacts/OneTunnel-0.3.0-alpha-arm64.apk`。

## 0.2.1-alpha：通用名称与界面精简（2026-11-19）

- 应用桌面名称、首页标题、系统 VPN 会话及通知名称统一为 OneTunnel；订阅名称输入使用通用标签，删除特定订阅示例。
- 删除宣传语、常驻说明、开关副说明和测试声明；保留操作标签、连接状态、导入兼容提示与错误信息。数据标签统一采用正常正文大小。
- `assembleDebug`、`lintDebug` 通过。未修改核心与分流逻辑，不额外增加 UI 文案测试。
- APK 位于 `artifacts/OneTunnel-0.2.1-alpha-arm64.apk`，versionCode 3；按用户此前的 ADB 安装要求覆盖安装至 TB378FC，确认版本并打开首页检查实际深色布局。未启动 VPN。

## 0.2.0-alpha：订阅与 Tailscale 登录（2026-11-18）

- 新增 HTTP/HTTPS Clash YAML 订阅下载、预览、独立加密缓存、更新与切换；已有 Clash YAML 文件也走同一导入流程。未扩展单节点分享链接、Base64 列表和桌面脚本兼容。
- 从用户电脑 Clash Verge 元数据读取两条 URL，仅在内存中请求；用应用实际 Kotlin 下载和归一化代码完成验证：Ouse 为 1 个 VLESS / 1 个组 / 5 条规则，魔戒为 30 个 VMess / 16 个组 / 3829 条有效规则（移除 23 条 PROCESS 规则）。未将真实链接、响应和凭据放入源码或 APK，未修改原 Clash。
- 新增 Tailscale 浏览器登录、状态与设备信息，内嵌 tsnet 的窄接口通过同 UID Messenger/JNI 调用；认证会话不建立 TUN，认证后手动连接。真实账号与安卓设备流程仍待验证。
- 使用 Go 1.26.4、官方 NDK r29 Clang 21、固定 Mihomo 源码及项目 overlay，成功交叉编译 Android ARM64 原生库。新增 Windows 构建脚本；未裁掉 Tailscale/gVisor。JNI 字符传输修复为标准 UTF-8，支持 emoji 节点名。
- ELF AArch64，LOAD 段 16 KiB 对齐；仅依赖 liblog/libdl/libc，无私有 RUNPATH。JNI_OnLoad、start/stop、prepareTailscale/tailscale 导出均存在。
- Gradle `assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部通过；24 项测试，0 失败 / 0 错误；lint 0 错误 / 8 警告。APK v2 开发签名校验通过，包名 dev.dsh.onetunnel，versionCode 2，最低 Android 8，ARM64。
- 完整 APK：`artifacts/OneTunnel-0.2.0-alpha-arm64.apk`，27,684,937 字节，包含真实原生库（51,396,184 字节）。构建工具和依赖下载位于忽略提交的 artifacts/，未改项目仓库配置。
- 未安装或启动应用、切换任何 VPN、运行真实代理或完成 Tailscale 账号登录；不将构建与下载解析通过视为设备联网通过。

```text
APK SHA-256:
cd2cb4d29f1b813d8488cc80b4b2e1dd00e4f9c8f1d45519421fdd0411b6d759

libonetunnel.so SHA-256:
349217742ba14eb0ee9fb1df238fdfa3e1b20eb80f29ce6b4d1a4531a2af6e50
```

以下为此前阶段记录，功能和产物状态以本节为准。

## Windows 工作区的界面与规则更新（2026-11-18）

- 已实现连接 / 规则 / 设置三页、浅色 / 深色 / 跟随系统；完整 YAML 位于高级配置。
- 新模板默认国内直连，MATCH 可选择出口；支持添加 / 删除域名与网段规则。
- 局域网代理的端口、开关与认证经加密配置传给原生核心，默认关闭；外部 controller 继续禁用。
- Android `:app:compileDebugKotlin` 通过（Kotlin 2.0.21 / Compose compiler 2.0.21 / JDK 21 / SDK 34）。
- Gradle `:app:testDebugUnitTest` 的 `ProfileEditorTest` 4 项测试全部通过，0 失败 / 0 错误：规则优先级与 MATCH、设置往返与认证校验、规则增删与输入校验、无效配置拒绝。另曾用 Kotlin 2.3.20 独立 JVM 执行同一测试，结果一致。
- `:app:lintDebug` 通过：0 errors / 7 warnings，提示现有 target SDK、依赖版本、ARM64-only、备份声明及图标资源；未为清零告警升级 SDK/依赖或更改兼容性基线。
- Windows 构建时临时使用 `artifacts/` 下的 Gradle 和本地 Maven 目录绕过 Java 下载问题；该目录被 Git 忽略，没有改动项目仓库地址或现有代理设置。SDK 34 使用本机已接受的许可证安装。
- 本工作区没有上游源码、NDK 和 `libonetunnel.so`；本轮未重新构建原生库，也未生成可交付的完整 APK。下面为原始版本的历史构建记录。
- 没有安装 / 打开 App、启动 VPN，或修改现有 Clash 设置。局域网共享、实际明暗界面、出口联网仍需设备验证。

### WireGuard 导入追加验证

- 增加 `.conf`、相机二维码、图片二维码入口。相机 Activity 不导出，扫码仅通过进程内存传递内容；返回 Intent 不含密钥，扫描 / 预览禁止截图。
- 标准 WG 客户端配置离线转换为 Mihomo `peers`（即使只有一个 Peer，也保留真实 AllowedIPs），添加至现有配置。含默认路由时明确选择是否作为 MATCH，保留国内直连优先级。
- `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:lintDebug` 均通过。共 12 项测试（原规则 4、WG 导入 5、二维码解码 3），涵盖双栈 / PSK / 路由合并 / 无效配置 / 旋转和反色 QR。追加后 lint 为 0 错误 / 8 警告（新增扫描页固定中文文案的国际化提示）。
- 相机实际权限流程、设备对焦、Android 图片读取、真实 WG 握手均未实测；没有生成包含原生库的完整新版 APK。Tailscale 浏览器登录和订阅 / 单节点分享链接导入仍未实现。

## 原始 Alpha 的历史构建记录

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
