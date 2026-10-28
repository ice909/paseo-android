# Paseo Native for Android

使用 **Kotlin + Jetpack Compose + Material 3** 实现的独立 Android 客户端，连接已有的 Paseo daemon。界面和网络层均为原生实现。Android 包名为 `sh.paseo.android`，可以与官方客户端共存。

官方源码参考：相邻目录 `../paseo`，协议对照版本 `abc97e0545bb92e43eb96027102d845657c0a2eb`。这是原生实现的第一版，尚未与官方客户端完整对齐。

## 已实现

- 多主机配置、密码认证、局域网 / VPN / Tailscale 直连，支持 `ws`、`wss`、`tcp` 及直连配对链接。
- 使用 Android Keystore 加密保存主机配置，进程重启后恢复连接，连接中断后指数退避重连。
- 会话列表、项目目录分组、搜索与状态筛选、分页、新建会话、提供方及模型选择、重命名和归档。
- 历史消息分页、实时输出、工具调用及结果、思考过程、任务清单、权限允许 / 拒绝、停止执行。
- 原生 Markdown 基础排版、可选择文本、可复制代码块。
- 目录浏览、UTF-8 文本查看与编辑，使用主机文件版本进行并发修改检查。
- 未提交 Git diff 查看。
- 文本模式终端：选择 / 创建终端、发送命令、Ctrl+C / Esc / Tab / Enter、每秒抓取最近 300 行。
- 系统深浅色主题、系统返回、键盘避让。

## 当前边界

中继 E2EE / 中继二维码配对、推送通知、语音、附件、交互式问题表单、完整 VT 终端、插件 UI、工作树和 PR 管理尚未实现。`relay://` 链接会明确提示使用直连地址。Markdown 暂不解析完整表格、图片和数学公式。

协议根据上述官方源码版本实现；没有用户真实主机的连接信息，因此当前验证使用协议模拟服务器。不同版本 daemon 的兼容性、真实代理执行和低端设备性能仍需实机验证。原生技术栈本身不构成性能保证。

## 构建

需要 JDK 17、Android SDK Platform 36（Build Tools 由 Gradle 按需安装）；最低 Android 8.0（API 26）。项目包含带校验和的 Gradle Wrapper，无需全局安装 Gradle。

1. 在 Android Studio 中打开当前目录，使用 JDK 17 并同步项目；或配置 `JAVA_HOME` 和 `ANDROID_HOME`。
2. 命令行构建（遵循 [AGENTS.md](AGENTS.md) 中的内存与并发限制）：

```sh
./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :core:test :app:lintDebug :app:assembleDebug
```

安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

测试流畅度使用开启 R8 的优化构建：

```sh
./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
```

`benchmark` 使用本机 debug 密钥签名、关闭调试并允许 shell profiling，只用于本地验证。正式发布使用 `release`，需自行配置发布签名。

## 连接已有主机

在主机启动 Paseo，确认 daemon 的监听地址能被手机访问。在应用中添加主机，例如 `192.168.1.10:6767` 或 `wss://paseo.example.com/ws`；如主机设置了密码，一并填写。新会话的工作目录指主机上的绝对路径。手机和主机可通过同一局域网或 VPN 互通。

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
./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :core:test :app:lintDebug :app:assembleDebug
```

协议测试包含地址与密码解析、认证拒绝、RPC 错误、历史和实时事件重叠、工具生命周期、长流式消息、会话加载与权限响应。

无需真实代理的 UI 验证（使用专用模拟器）：

```sh
python3 tools/mock_daemon.py --port 6768
# 另一个终端；emulator-5554 应已启动并安装 APK
python3 tools/android_smoke.py --reset
```

`--reset` 会清除模拟器中本应用的数据。模拟主机密码为 `test`，不执行真实 shell 命令。截图输出在 `app/build/smoke/`。

## 参考

- [官方协议源码](https://github.com/getpaseo/paseo/tree/abc97e0545bb92e43eb96027102d845657c0a2eb/packages/protocol)
- [官方客户端 SDK](https://github.com/getpaseo/paseo/tree/abc97e0545bb92e43eb96027102d845657c0a2eb/packages/client)
- [Jetpack Compose 项目配置](https://developer.android.com/develop/ui/compose/setup)

Apache-2.0，详见 `LICENSE` 和 `NOTICE`。
