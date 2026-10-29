# Paseo Native for Android

使用 **Kotlin + Jetpack Compose + Material 3** 实现的独立 Android 客户端，连接已有的 Paseo daemon。界面和网络层均为原生实现。Android 包名为 `sh.paseo.android`，可以与官方客户端共存。

官方源码参考：相邻目录 `../paseo`，协议对照版本 `abc97e0545bb92e43eb96027102d845657c0a2eb`。本轮先对齐官方移动端的工作区导航和基本任务流程；完整功能与像素级视觉对齐仍在进行。

## 已实现

- 添加主机默认只需粘贴官方 `https://app.paseo.sh/#offer=…` 配对链接，主机名称从电脑自动读取；验证连接成功后才保存，主机要求密码时才显示密码输入框。
- 官方加密中继配对（`#offer=`、`relay://`、`#connect=`），支持 Noise NK protected 模式及旧版 NaCl 通道；校验配对公钥并拒绝不满足要求的降级连接。密码不进入中继 URL 或 HTTP 请求头。
- 多主机配置、密码认证、局域网 / VPN / Tailscale 直连；「更多选项」保留手动地址与可选自定义名称。
- 使用 Android Keystore 加密保存主机配置，进程重启后恢复连接，连接中断后指数退避重连。
- 官方 Zinc 灰阶深浅色布局：项目 / 工作区侧栏、主机状态、移动端代理选择器、底部配置与消息输入、独立文件 / 改动面板。
- 新建工作区首页采用官方 compact 表单：56 dp 填充选择行、项目 / 主机 / 隔离 / 分支 / 启动方式，目录输入收进项目弹层；消息框使用官方的 16 dp 圆角、配色、间距和 28 dp 按钮。支持首条消息附件、听写、空工作区及直接启动终端。
- 真实工作区目录订阅、分页、已加载工作区搜索、空项目 / 空工作区、工作区重命名、置顶和归档。
- 在已有目录或从当前分支新建 Git worktree，连同首个代理和初始任务一起创建；已有工作区可追加多个代理。
- 提供方 / 模型 / 模式选择，已有代理的模型和模式切换，斜杠命令建议。
- 使用创建请求幂等键和创建状态回执；同一草稿重试保留键，编辑配置后生成新键。现代创建协议用于支持 `creationLifecycle` 的 daemon，旧版提供基本创建兼容路径。
- 历史消息分页、实时输出、工具调用及结果、思考过程、任务清单、权限允许 / 拒绝、交互式单选 / 多选 / 文本提问、停止执行。
- 官方默认 steer、interrupt / queue 发送偏好、运行中长按发送切换行为、队列编辑 / 立即发送 / 空闲时顺序发送；立即清空输入和乐观消息、失败草稿恢复、同一消息 ID 重试。
- 按主机和代理隔离的加密持久草稿与未确认发送记录、展开编辑器、手机回车换行、斜杠命令和 @ 文件建议、模型 / 模式 / 思考强度 / 动态特性切换。
- 图片粘贴 / 拖放和文件附件（单文件 50 MiB 上限）、图片缩放预览、Issue / PR 上下文附件、按代理能力回退对话 / 文件、已完成回合折叠与消息复制。
- 官方 daemon 流式语音转写和语音对话协议：麦克风权限、取消、转写重试、文本追加 / 发送、静音、打断、播放确认；依赖主机启用对应语音服务。
- 原生 Markdown 基础排版、链接、可选择文本、可复制代码块。
- 目录浏览、UTF-8 文本查看与编辑，使用主机文件版本进行并发修改检查。
- 未提交 Git diff 查看。
- 文本模式终端：选择 / 创建 / 关闭终端、发送命令、Ctrl+C / Esc / Tab / Enter、每秒抓取最近 300 行。
- 未添加主机时使用官方欢迎页、Paseo 标志和 Lucide 图标；扫码、直接连接和配对链接三个入口均可用。配对弹层按官方尺寸、色值与输入框样式呈现，验证成功后保存主机。
- 全应用图标使用官方同版 Lucide 与 Paseo 自定义资源，内置 9 种代理标志和 54 种文件类型图标；文件图标保留官方 OKLab 调色。
- 官方全部 8 个内置主题：Light、Dark、系统、Zinc、Midnight、Claude、Ghostty、纯黑；侧栏「设置 → 外观」即时切换并在本机保存。系统模式跟随设备深浅色；侧栏、聊天、diff、终端、弹窗及系统栏同步切换。
- 系统返回、键盘避让。

## 当前边界

推送通知、完整 VT 终端、插件 UI、归档历史入口、日程、完整工作树与 PR 管理尚未实现。工作树目前仅支持从当前分支创建；Git 改动面板目前仅展示未提交改动。Markdown 暂不解析完整表格、图片和数学公式。

协议根据上述官方源码版本实现。已使用隔离的官方 daemon 0.13.2 及其真实 `#offer=` 链接，经公共中继验证原生 Kotlin 网络层的工作区读取、文件读写与终端操作；聊天流、审批、提问和界面流程使用协议模拟服务验证。尚未使用用户真实 AI 代理执行任务；不同 daemon 版本兼容性和低端设备性能仍需实机验证。

## 构建

需要 JDK 27、Android SDK Platform 37.2 和 Build Tools 37.0.0；最低 Android 8.0（API 26）。项目包含带校验和的 Gradle Wrapper，无需全局安装 Gradle。

1. 在 Android Studio 中打开当前目录，将 Gradle JDK 设置为 JDK 27 并同步项目；命令行通过 `JAVA_HOME` 和 `ANDROID_HOME` 配置。Gradle、编译和测试使用 JDK 27，Java/Kotlin 字节码目标仍为 17。
2. 命令行构建（遵循 [AGENTS.md](AGENTS.md) 中的内存与并发限制）：

```sh
run_gradle() {
  systemd-run --user --scope -p MemoryMax=3G -p MemorySwapMax=0 -- \
    ./gradlew --no-daemon --no-parallel --max-workers=1 \
    -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
}
run_gradle :core:test
run_gradle :app:testDebugUnitTest
run_gradle :app:assembleDebug
run_gradle :app:lintDebug
```

上述命令使用用户级 systemd 作用域限制整个构建及子进程总内存为 3 GiB，并禁止使用 swap。没有用户级 systemd 的环境按 AGENTS.md 保留 JVM 上限、单 worker 和串行构建，并监控总内存。

安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

测试流畅度使用开启 R8 的优化构建：

```sh
systemd-run --user --scope -p MemoryMax=3G -p MemorySwapMax=0 -- \
  ./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
```

`benchmark` 使用本机 debug 密钥签名、关闭调试并允许 shell profiling，只用于本地验证。正式发布使用 `release`，需自行配置发布签名。

## 工具链与依赖版本

以下为 2026-10-29 核对官方发布源后的稳定版本，均固定版本号，不使用动态版本或预览版。

| 组件 | 版本 |
| --- | --- |
| 构建 JDK | 27（本机 OpenJDK 27.0.1） |
| Gradle Wrapper | 9.8.2 |
| Android Gradle Plugin | 9.5.0，使用内置 Kotlin |
| Kotlin / Compose 编译器插件 | 2.4.21 |
| Android compile SDK / target SDK | 37.2 / 37 |
| Android Build Tools / Command-line Tools | 37.0.0 / 23.0 |
| Compose BOM | 2026.09.00 |
| Activity / Lifecycle | 1.13.0 / 2.11.0 |
| Coroutines / Serialization | 1.11.0 / 1.12.0 |
| OkHttp / MockWebServer | 5.5.1 / 5.5.1 |
| Bouncy Castle 原语库 | 1.86（用于 X25519、ChaChaPoly、NaCl 兼容通道，不替换 Android 的系统 provider） |
| Material Icons / JUnit 4 | 1.7.8 / 4.13.2（这两个 artifact 的最新稳定版） |

项目默认关闭 Gradle daemon 和项目并行，限制一个 worker，并使用 Kotlin 进程内编译；Lint JVM 堆限制为 512 MiB，各验证阶段使用独立的串行调用；详细资源限制见 [AGENTS.md](AGENTS.md)。

官方版本源：[Gradle](https://services.gradle.org/versions/current)、[AGP](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)、[Kotlin](https://kotlinlang.org/docs/releases.html)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping)、[Android SDK](https://dl.google.com/android/repository/repository2-3.xml)。

## 连接已有主机

在主机启动 Paseo，确认 daemon 的监听地址能被手机访问。Android 17 及以上在连接局域网主机时会请求「附近设备」权限；拒绝后可再次点击主机重试。公网主机和本机回环地址无需该权限。`ws://` 直连通过 Network Security Configuration 保持兼容。

推荐在电脑的 Paseo 中选择「配对设备」，或运行 `paseo daemon pair` 复制官方链接。在手机点击「添加主机」，粘贴链接后点「连接」。不需要填写主机名称；名称由主机提供。如 daemon 要求密码，应用收到认证提示后才显示密码输入框。

也可在「更多选项 → 手动输入地址」中使用 `192.168.1.10:6767` 或 `wss://paseo.example.com/ws`，并按需填写密码。新工作区的目录指主机上的绝对路径。连接后打开左上角侧栏选择已有工作区，或选择「新建工作区」。工作区内点击代理名称切换代理 / 终端；右上角按钮打开改动 / 文件面板。手机和主机可通过同一局域网或 VPN 互通。

Android 模拟器访问开发机使用 `10.0.2.2`；也可执行 `adb reverse tcp:6767 tcp:6767` 后连接 `127.0.0.1:6767`。

## 目录

```text
app/     Compose 界面、ViewModel、Android Keystore 配置存储
core/    纯 Kotlin 的 WebSocket 传输、协议模型、订阅、会话仓库和消息合并
tools/   本地模拟 daemon 和 Android UI smoke 脚本
```

流式 JSON 解析与状态处理在后台线程执行，按 50ms 合并界面更新。消息列表使用稳定 key 的 `LazyColumn`；时间线以 canonical 序号去重，只增量投影新增事件，并按工具调用身份合并生命周期记录。切换会话释放订阅；重连后重新订阅并读取历史快照。

## 验证

```sh
run_gradle() {
  systemd-run --user --scope -p MemoryMax=3G -p MemorySwapMax=0 -- \
    ./gradlew --no-daemon --no-parallel --max-workers=1 \
    -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
}
run_gradle :core:test
run_gradle :app:testDebugUnitTest
run_gradle :app:assembleDebug
run_gradle :app:lintDebug
```

单元测试覆盖地址与密码解析、局域网权限分类、认证拒绝、RPC 错误、历史和实时事件重叠、工具生命周期、长流式消息、权限响应、工作区目录更新、创建状态回执、失败后的工作区保留、多代理归属、提问答案格式、官方配对链接解析、Noise 标准向量、NaCl 互通、分块认证、重放拒绝和降级拒绝。

无需真实代理的 UI 验证（使用专用模拟器）：

```sh
python3 tools/mock_daemon.py --port 6768
# 另一个终端；emulator-5554 应已启动并安装 APK
python3 tools/android_smoke.py --reset --deny-lan-once
```

脚本覆盖主机恢复、局域网权限拒绝后重试、侧栏、多代理切换、聊天流、工具审批、提问、文件编辑、改动、终端、空工作区、创建工作区 / 代理与归档。

独立的主题回归使用同一个模拟服务和 API 37+ 专用模拟器，覆盖所有内置主题的实际绘制颜色、系统模式切换、手动模式不受系统影响、重启保存，以及聊天草稿 / 文件面板 / 终端的返回：

```sh
python3 tools/android_theme_smoke.py --reset --with-host
```

仅检查主题、不连接模拟主机时省略 `--with-host`。截图输出在 `app/build/theme-smoke/`。

欢迎页回归（同样使用专用模拟器，正常流程需要 6768 模拟服务）：

```sh
python3 tools/android_welcome_smoke.py --reset
# 单独检查窄屏与横屏，跳过连接与相机权限流程
python3 tools/android_welcome_smoke.py --reset --layout-only
```

覆盖欢迎页文案 / 标志 / 三种入口、主题设置返回、相机权限拒绝与重试、单个配对输入框、无效链接错误、直连表单与连接成功。截图输出在 `app/build/welcome-smoke/`。

`--reset` 会清除模拟器中本应用的数据。模拟主机密码为 `test`，不执行真实 shell 命令。截图输出在 `app/build/smoke/`。

官方加密库互通与 Android 配对 UI 回归可用下列工具（需要已安装官方 Paseo CLI，使用其依赖，不启动真实 AI）：

```sh
node tools/relay_pairing_fixture.mjs /tmp/paseo-relay-fixture.json 6770
# 另一终端，在模拟器停止时执行；测试构建遵循上述内存限制
PASEO_RELAY_FIXTURE_FILE=/tmp/paseo-relay-fixture.json run_gradle :core:test
# 编译完成后启动专用模拟器并安装 APK，再执行
python3 tools/android_pairing_smoke.py --fixture /tmp/paseo-relay-fixture.json
```

配对 UI 测试覆盖仅一个链接输入框、无效链接不落盘、按需询问密码、错误密码重试、自动主机名、加密文件请求和重启恢复。

真实协议集成测试为手动启用：在独立的临时 daemon home 中创建名为 `Real daemon workspace` 的无代理工作区，目录内放置含 `Native integration` 的 `README.md`。启动 daemon 时设置 `PASEO_DICTATION_ENABLED=false PASEO_VOICE_MODE_ENABLED=false PASEO_RELAY_ENABLED=false`，避免测试实例启动语音模型或中继。然后运行：

```sh
PASEO_SMOKE_URL=ws://127.0.0.1:6769/ws run_gradle :core:test --tests '*RealDaemonIntegrationTest'
```

此测试会修改测试目录的 README 并创建 / 关闭一个真实终端，只连接专用的临时测试实例；还可用 `PASEO_SMOKE_LINK_FILE` 指向官方配对链接文件、`PASEO_SMOKE_PASSWORD_FILE` 指向测试密码文件，验证经过真实中继的同一流程。未设置变量时跳过。构建与模拟器验证串行进行，资源限制遵循 AGENTS.md。

界面和协议映射见 [docs/official-alignment.md](docs/official-alignment.md)。

## 参考

- [官方协议源码](https://github.com/getpaseo/paseo/tree/abc97e0545bb92e43eb96027102d845657c0a2eb/packages/protocol)
- [官方客户端 SDK](https://github.com/getpaseo/paseo/tree/abc97e0545bb92e43eb96027102d845657c0a2eb/packages/client)
- [Jetpack Compose 项目配置](https://developer.android.com/develop/ui/compose/setup)

Apache-2.0，详见 `LICENSE` 和 `NOTICE`。

全应用图标验证：先以 `python3 tools/mock_daemon.py --port 6768 --icon-gallery` 启动模拟服务，再串行运行 `python3 tools/android_smoke.py --reset` 和 `python3 tools/android_icon_smoke.py`。后者检查侧栏、代理标志、diff、彩色文件图标（含 Kotlin 渐变）、主题菜单和终端；截图位于 `app/build/icon-smoke/`。
