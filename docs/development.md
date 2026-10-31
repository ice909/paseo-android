# 构建与测试

以下命令均从仓库根目录执行。实现与协议映射见 [官方功能对齐记录](official-alignment.md)。

## 环境

- JDK 27，通过 `JAVA_HOME` 或 Android Studio 的 Gradle JDK 配置。
- Android SDK Platform 37.2、Build Tools 37.0.0，通过 `ANDROID_HOME` 或 `local.properties` 配置。
- 使用仓库中的 Gradle Wrapper；Java/Kotlin 字节码目标保持 17。

依赖版本以 [根构建配置](../build.gradle.kts)、[Android 模块](../app/build.gradle.kts)、[核心模块](../core/build.gradle.kts) 和 [Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties) 为准。

## 构建

遵循 [AGENTS.md](../AGENTS.md)：构建串行执行，不启动常驻 daemon，不同时运行模拟器。以下函数集中设置必要的内存和并发限制：

```sh
run_gradle() {
  systemd-run --user --scope -p MemoryMax=3G -p MemorySwapMax=0 -- \
    ./gradlew --no-daemon --no-parallel --max-workers=1 \
    -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
}
```

没有用户级 systemd/cgroup 的环境，去掉 `systemd-run … --` 前缀，保留全部 Gradle 参数，并监控整个构建进程树的内存。JVM 堆上限不等于构建总内存上限。

调试构建与安装：

```sh
run_gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

优化构建将 Kotlin 编译、Lint 和 R8 打包分开执行；下例在完整 release Lint 通过后，跳过打包阶段重复的 Lint 检查：

```sh
run_gradle :app:compileReleaseKotlin
run_gradle :app:lintRelease
run_gradle :app:assembleRelease \
  -x :app:lintVitalRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalReportRelease
```

| 构建类型 | 用途 | APK 路径（相对于 `app/build/outputs/apk/`） |
| --- | --- | --- |
| `debug` | 调试，包含开发工具，未开启代码裁剪 | `debug/app-debug.apk` |
| `release` | 启用代码与资源裁剪，需配置签名或使用 `apksigner` 签名 | `release/app-release-unsigned.apk`（未配置签名时） |
| `benchmark` | 本地性能验证，启用裁剪和 profiling，使用本机 debug 密钥 | `benchmark/app-benchmark.apk` |

覆盖安装需要沿用相同签名。`benchmark` 同样需要将编译、Lint 与 R8 分开执行，不作为正式发布包。

## 日常验证

按改动范围选最短路径，不把下表当成必须依次执行的清单：

| 改动 | 默认检查 |
| --- | --- |
| 文档、注释 | `git diff --check`，必要时检查链接或命令语法，不启动 Gradle |
| `core/` 逻辑 | 用 `--tests` 只运行相关测试类 / 方法 |
| 普通 Compose 布局、颜色、文案 | `run_gradle :app:compileDebugKotlin` |
| Android 图片、图标、XML 资源 | `run_gradle :app:processDebugResources` |
| Android 业务逻辑 | 定向运行相关 `:app:testDebugUnitTest`，无需另跑一次编译 |
| 依赖、Manifest、构建配置 | 选择受影响变体的构建或 Lint；不要同时构建全部变体 |
| 交付 APK | 直接构建需要的类型；release 按上方流程分阶段执行 |

定向测试示例，二选一或按实际影响选择：

```sh
run_gradle :core:test --tests 'sh.paseo.android.core.UsageTest'
run_gradle :app:testDebugUnitTest --tests 'sh.paseo.android.ui.MarkdownImagesTest'
```

一批修改完成后统一验证；检查通过后不因提交或整理文档再次运行。跨模块契约变化需补充调用方检查，发现失败或未覆盖风险时再扩大范围。纯样式修改不新增测试；逻辑缺陷优先补能复现问题的针对性用例。

全量测试、Lint、R8 和以下模拟器回归均按需执行，不是日常修改的固定步骤。尤其不要为小改动重跑所有主题、完整聊天流程或真实主机集成测试。

## 模拟器回归

仅在手势、键盘、渲染或设备行为等确需运行时验证的情况下，选择受影响场景。先完成构建，再启动专用模拟器 `emulator-5554` 并安装 APK。模拟器连接开发机使用 `10.0.2.2`；主机地址示例为 `tcp://10.0.2.2:6768?password=test`。

```sh
# 终端一：模拟服务不执行真实 AI 或 shell
python3 tools/mock_daemon.py --port 6768

# 终端二：基础流程回归
python3 tools/android_smoke.py --reset --deny-lan-once
```

`--reset` 会清除该模拟器中本应用的数据。测试脚本支持 `--adb` 指定 adb 路径；截图输出在 `app/build/` 下各自的 smoke 目录。

专题回归前先停止旧模拟服务，再按表中参数启动 `tools/mock_daemon.py --port 6768`。脚本路径均相对于 `tools/`：

| 范围 | 模拟服务附加参数 | 测试脚本及参数 |
| --- | --- | --- |
| 主题 | 无 | `android_theme_smoke.py --reset --with-host` |
| 欢迎页与配对入口 | 无 | `android_welcome_smoke.py --reset` |
| 图标 | `--icon-gallery` | 先运行基础流程，再运行 `android_icon_smoke.py` |
| 工具展示与工作状态 | `--tool-presentation` | `android_tool_presentation_smoke.py --reset` |
| 模型与思考强度 | `--agent-controls --events-file /tmp/paseo-controls-events.jsonl` | `android_agent_controls_smoke.py --reset --events /tmp/paseo-controls-events.jsonl` |
| 回复复制 | `--copy-actions --tool-presentation` | `android_copy_smoke.py --reset` |
| 重启恢复会话 | `--events-file /tmp/paseo-restoration-events.jsonl` | 已保存模拟主机连接后运行 `android_restoration_smoke.py --events /tmp/paseo-restoration-events.jsonl` |
| 侧栏与用量 | `--sidebar --events-file /tmp/paseo-sidebar-events.jsonl` | `android_sidebar_smoke.py --reset --events-file /tmp/paseo-sidebar-events.jsonl` |

欢迎页脚本的 `--layout-only` 单独检查窄屏与横屏。侧栏模拟服务的 `--legacy-usage` / `--usage-unavailable` 分别配合 UI 脚本的 `--usage-only` / `--unavailable` 检查旧接口和未登录状态。其他专项脚本及参数见 [tools/](../tools/) 中各脚本的 `--help`。

## 加密配对互通

需要安装官方 Paseo CLI，fixture 使用其依赖，不启动真实 AI：

```sh
node tools/relay_pairing_fixture.mjs /tmp/paseo-relay-fixture.json 6770
# 另一终端；此时停止模拟器
PASEO_RELAY_FIXTURE_FILE=/tmp/paseo-relay-fixture.json run_gradle :core:test
# 构建退出后启动模拟器并安装 APK，再运行 UI 验证
python3 tools/android_pairing_smoke.py --fixture /tmp/paseo-relay-fixture.json
```

## 真实主机集成测试

只连接专用的临时 daemon 实例：创建名为 `Real daemon workspace` 的无代理工作区，目录内放置含 `Native integration` 的 `README.md`。启动该实例时设置 `PASEO_DICTATION_ENABLED=false PASEO_VOICE_MODE_ENABLED=false PASEO_RELAY_ENABLED=false`。

```sh
PASEO_SMOKE_URL=ws://127.0.0.1:6769/ws run_gradle :core:test --tests '*RealDaemonIntegrationTest'
```

此测试会修改测试目录的 README，并创建、关闭真实终端。也可通过 `PASEO_SMOKE_LINK_FILE` 和 `PASEO_SMOKE_PASSWORD_FILE` 指向测试配对链接和密码文件，验证真实中继。未配置变量时跳过。
