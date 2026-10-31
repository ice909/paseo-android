# 项目协作约定

适用于整个仓库。项目使用 Kotlin + Jetpack Compose；`app/` 是 Android 界面，`core/` 是协议与网络层。

## 构建约束

- 使用项目的 `./gradlew` 和 JDK 27，编译、测试使用同一 JDK；Java/Kotlin 字节码目标保持 17。
- **同一时间只运行一个构建进程**，启动前检查可用内存和已有进程。需要执行的编译、测试、Lint、R8 阶段串行执行；前一进程退出后再开始下一阶段，不与模拟器或其他重型构建并行。
- 所有 Gradle 调用及文档示例必须显式保留下列参数，不依赖 `gradle.properties` 默认值。禁止常驻 Gradle / Kotlin daemon；允许随本次命令结束退出的一次性 Gradle JVM。

```sh
systemd-run --user --scope -p MemoryMax=3G -p MemorySwapMax=0 -- \
  ./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :core:test
```

- 按需替换末尾任务，具体流程见 [构建与测试](docs/development.md)。无用户级 systemd/cgroup 时仅去掉 `systemd-run … --` 前缀，保留全部 Gradle 参数并监控总内存；`-Xmx` 不限制整个进程树的 RSS。
- Lint 独立 JVM 堆上限保持 `android.experimental.lint.heapSize=512m`；测试 JVM 堆不超过 `512m`，`maxParallelForks = 1`。
- 内存不足时缩小任务范围、清理本项目已确认空闲的遗留进程；仍无法完成则报告限制。不得提高上述上限、开启并行或额外启动 daemon。

## 验证与清理

- **默认做最小必要验证**：文档或注释只检查差异；`core/` 只跑受影响的测试类 / 方法；普通 Compose 改动做 Debug 编译；资源改动做资源编译。具体任务见开发文档。
- 不默认串跑全量单测、Lint、APK 打包、R8 和模拟器。仅在交付安装包、相关配置 / 依赖变更、发现风险或用户要求时增加对应检查；跨模块改动补充受影响的调用方检查。
- 集中完成一批修改后验证一次。没有新改动或失败，不重复已通过的检查；不无故 `clean`，不为单纯样式、图标或文案改动新增测试。
- 模拟器仅验证无法通过编译或单测确认的相关交互，选择最小路径；不默认跑完整流程、多主题截图或反复清空数据。交付 APK 直接构建所需类型，不先轮流打 Debug、benchmark、release。如实说明验证范围。
- 任务结束或中断后，确认本次启动的 Gradle / Kotlin 编译进程已退出。
- 只按确认过的 PID 清理本项目空闲进程，禁止 `killall java`、宽泛的 `pkill` 或影响其他项目。使用 Gradle `--stop` 前确认不会中断同用户、同版本的其他项目 daemon。

## 界面与图标

- 图标统一使用 `PaseoIcons` / `PaseoGlyph`，按官方对应功能选图，不引入 Material Icons 或近似图标库。
- 文件图标使用 `FileGlyph`，保留官方多色填充；代理标志使用 `PaseoIcons.provider`。
- 资源通过 `python3 tools/sync_official_icons.py --paseo ../paseo` 同步，保留官方锁定版本、完整性校验和 OKLab 调色；不手工重画 SVG 路径。

## 文档分工

README 只保留项目介绍、核心功能、使用方式和开发入口。构建与测试步骤放 [开发文档](docs/development.md)，协议和界面对齐细节放 [对齐记录](docs/official-alignment.md)，不要将过程日志、测试清单和逐项实现记录追加到 README。
