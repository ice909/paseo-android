# 项目协作约定

本文件适用于整个仓库。项目使用 Kotlin、Jetpack Compose 和 Gradle Wrapper；Android 界面位于 `app/`，协议与网络层位于 `core/`。

## Gradle 与内存限制（必须遵守）

- 使用项目的 `./gradlew` 和 JDK 27；Gradle、编译和测试使用同一 JDK。Android 的 Java/Kotlin 字节码目标保持 17，不要将其误改为运行时 JDK 版本。
- **每次 Gradle 调用必须带上 `--no-daemon`**，不启动长期驻留的 Gradle daemon。
- **同一时间只允许一个构建进程**。不得在多个终端、后台任务或工具调用中同时运行 Gradle；上一条命令确实退出后，才能执行下一条。
- 必须使用 `--no-parallel --max-workers=1`，关闭项目并行并限制 worker 数量。
- 必须使用 `-Pkotlin.compiler.execution.strategy=in-process`，让 Kotlin 编译器在 Gradle JVM 内执行，避免另起 Kotlin daemon 叠加内存。
- Lint 独立 JVM 的最大堆为 **512 MiB**，由 `android.experimental.lint.heapSize=512m` 限制。编译、单元测试、Lint、R8 优化应拆成不同的串行 Gradle 调用，避免单次 JVM 累积过多编译器与分析器状态。
- Gradle JVM 的最大堆为 **1536 MiB**，Metaspace 为 **384 MiB**，代码缓存为 **128 MiB**，直接内存为 **256 MiB**；使用 `-XX:ActiveProcessorCount=2` 限制内部线程扩张。
- 不得为解决 OOM 自行提高这些上限、开启并行或额外启动 daemon。先缩小任务范围、清理已确认属于本项目的遗留构建进程；仍无法完成时，如实报告资源限制。

`gradle.properties` 已包含相同的内存与并发限制。所有实际构建仍须显式使用下面的命令行参数，避免用户级配置覆盖项目默认值，包括 README 中的构建示例。

### 标准调用方式

```sh
./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :core:test
```

按需将最后的任务替换为 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 或 `:app:assembleBenchmark`。各阶段等待前一个进程退出后再执行，不要将完整验证链放入同一次 JVM。

`--no-daemon` 在 JVM 参数不匹配时仍可能启动一次性的 Gradle JVM；这是允许的，但它必须在本次命令结束后退出，不能作为常驻 daemon 留存。

### 整个构建的内存预算

上述参数限制 JVM 的各类内存，并非整个构建进程树的 RSS 硬上限。测试 JVM、AAPT2 和其他原生工具仍可能额外占用内存。

- 启动前检查可用内存和已有构建进程；不要在明显内存不足时启动 APK、Lint 或 R8 大任务。
- 编译期间不要同时启动模拟器或其他重型构建；需要 UI 验证时，先完成编译并等待构建进程退出。
- 若修改测试任务的执行配置，保持 `maxParallelForks = 1`，测试 JVM 最大堆不超过 `512m`。
- 环境支持用户级 systemd/cgroup 时，使用下列作用域把构建及其子进程的总内存硬限制为 **3 GiB**，并禁止该作用域使用 swap：

```sh
systemd-run --user --scope -p MemoryMax=3G -p MemorySwapMax=0 -- \
  ./gradlew --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug
```

若 systemd/cgroup 不可用，保留全部 JVM 与并发限制，并监控实际内存占用；不得声称仅凭 `-Xmx` 就限制了整个构建的总内存。

## 验证与清理

- 仅修改文档或注释时，不运行 Gradle。
- 修改 `core/` 时先运行相关单元测试；涉及 Android 实现或构建配置时再运行所需的 Android 检查。不要无理由执行 `clean` 或重复全量构建。
- 任务结束或构建中断后，确认没有留下本次启动的 Gradle / Kotlin 编译进程。
- 对已确认空闲且属于本项目的 Gradle daemon，可使用 `./gradlew --no-daemon --stop` 清理；此命令会影响同一用户、同一 Gradle 版本的 daemon，执行前确认不会中断其他项目。
- 仅按确认过的 PID 清理本项目遗留的 Kotlin daemon；禁止使用 `killall java`、宽泛的 `pkill -f java` 或清理其他项目进程。

## 参考

- [Gradle daemon 与一次性 JVM](https://docs.gradle.org/current/userguide/gradle_daemon.html)
- [Kotlin 编译器执行策略](https://kotlinlang.org/docs/gradle-compilation-and-caches.html#defining-kotlin-compiler-execution-strategy)

## 图标约定

- 应用图标统一使用 `PaseoIcons` / `PaseoGlyph`，对照官方对应功能选择图形；不要重新引入 Material Icons 或其他近似图标库。
- 文件类型图标通过 `FileGlyph` 渲染并保留官方调色后的多色填充；代理标志通过 `PaseoIcons.provider` 选择。
- 同步资源使用 `python3 tools/sync_official_icons.py --paseo ../paseo`。该工具读取官方锁定版本、校验 npm 完整性，并使用官方 OKLab 函数处理文件图标；不要手动重画 SVG 路径。
