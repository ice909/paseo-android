# 官方移动端对齐记录

参考仓库 `getpaseo/paseo`，提交 `abc97e0545bb92e43eb96027102d845657c0a2eb`。界面参考官方 `packages/website/public/phone-{1,2,3}.png` 和移动端实现；原生界面使用 Compose 独立实现。

| 官方界面 / 行为 | 原生实现 | 官方源码 |
| --- | --- | --- |
| 全部 8 个内置主题、系统跟随、本地保存、即时预览 | `appearance/`、`Theme.kt`、`AppearanceSettingsScreen.kt` | `styles/theme.ts`、`appearance/provider.tsx`、`screens/settings/appearance/` |
| 平铺项目和工作区侧栏 | `WorkspaceSidebar.kt` | `components/left-sidebar.tsx` |
| 紧凑标题栏、代理切换菜单、独立资源面板 | `PaseoApp.kt` | `screens/workspace/workspace-screen.tsx`、`workspace-header-menu.tsx` |
| 配置表单靠近底部输入区 | `NewWorkspaceScreen.kt`、`MessageComposer.kt` | `screens/new-workspace-screen.tsx` |
| 用户消息块、助手正文、可展开工具行、停止 | `ChatScreen.kt`、`Markdown.kt` | 官方聊天和 composer 组件 |
| 单选、多选、自定义回答 | `QuestionForm.kt`（app/core） | `components/question-form-card-core.ts` |
| 改动 / 文件切换与文件编辑 | `WorkspacePanes.kt` | 官方 workspace explorer |

协议以 `packages/protocol/src/messages.ts` 与 `packages/client/src/creation/index.ts` 为准：

- `fetch_workspaces_request`、`workspace_update`：工作区目录、空项目、分页、实时修改 / 移除。
- `workspace.create.request`、`agent.create.request`：显式区分新工作区和已有工作区追加代理。支持目录和 branch-off worktree；创建配置保留模型、模式、初始提示词与幂等键。
- `creation.subscribe.request`：遇到非终态回执时恢复创建状态。支持现代创建协议的主机按同一幂等键重试；旧版兼容路径不提供相同的完整保证。
- `fetch_agents_request`、`agent.timeline.set_subscription.request`、`fetch_agent_timeline_request`：工作区内代理与按需时间线；切换代理释放原订阅。工作区归属优先使用 ID，相同目录的不同工作区不会混在一起。
- `set_agent_model_request`、`set_agent_mode_request`、`list_commands_request`：代理设置与命令建议。
- `agent_permission_response`：保留原始 input，在 `updatedInput.answers` 中按问题的 **header** 写入答案；多选用逗号空格连接标签，自定义单选答案替代标签。
- 文件和终端直接使用工作区目录 / ID；没有代理的工作区也能使用，不构造假的代理记录。

工作区阶段先接通基本编码流程；对话交互在 0.6.0 中继续实现，见下方记录。推送、归档会话恢复、日程、插件运行时、完整 VT 终端和完整 Git 管理仍未移植。

验证分为协议回归测试、隔离的官方 daemon 0.13.2 文件 / 终端集成测试，以及 Android 17 模拟器上的可重复 UI smoke。模拟服务不执行真实 AI 或 shell；真实 daemon 集成测试也不启动 AI 任务。它们不能替代用户真实主机上的代理与设备性能验证。

## 官方链接配对（0.2.0）

官方 `protocol/src/connection-offer.ts` 和 `daemon-endpoints.ts` 定义 `#offer=` v2 链接、旧版 `relay://` / `#connect=` 链接。原生 `Pairing.kt` 校验版本、端点、公钥及最低安全模式，并将中继路由与密码分开保存。

`RelayChannel.kt`、`RelayCrypto.kt` 对应官方 `relay/src/secure-channel.ts`、`protected-records.ts`、`noise/`、`crypto.ts`。使用 Bouncy Castle 轻量原语，不注册或替换系统 provider。protected 模式认证 Noise NK 握手和分块记录；只在配对策略允许的旧节点上使用 NaCl。protected 数据损坏、重放、错误主机公钥、认证参数异常与安全降级均拒绝继续连接。

`DaemonSocket.kt` 在加密通道建立后才发送 daemon hello 和密码；中继升级请求只有路由参数，不带 daemon Authorization。`AppViewModel` 使用短连接验证配对，只有认证成功后才保存主机；默认从 server_info 读取电脑名称。手机默认只显示链接输入框，密码输入框按主机认证请求出现，自定义名称和直连配置放在更多选项中。

验证使用官方 Noise NK 向量（保留资源中的原始 source 字段）、官方 tweetnacl 已知数据、已安装的官方 relay 库互通 peer，以及隔离的 daemon 0.13.2 的真实公共中继链接。Android UI 回归覆盖链接、密码挑战、主机名称和重启恢复。

## App 主题（0.3.0）

保留官方 theme preference 键、顺序、色板与状态 / diff 语义色。默认 `auto`；系统深色使用 Paseo Dark，额外的 Zinc、Midnight、Claude、Ghostty、纯黑始终为深色。主题保存在独立的设备本地 appearance 偏好中，不写入主机配置；切换无需重建 Activity 或重新连接主机。系统状态栏、导航栏图标依据所选主题更新。

侧栏「设置」进入外观页，支持主题下拉菜单与即时预览。插件贡献主题依赖官方 JS 插件运行时，目前未移植；字体、字号与独立语法高亮主题设置仍留待后续。

## 欢迎页与配对弹层（0.4.0）

以「尚未添加主机」为范围，对齐官方 `components/welcome-screen.tsx`、`pair-link-modal.tsx`、`add-host-modal.tsx` 与 `app/pair-scan.tsx`。欢迎页没有主机工具栏或创建表单：96dp 官方标志、欢迎文案、paseo.sh 链接、三种连接方式、设置及底部版本号均按原始顺序、间距与色值实现。标志直接使用官方 SVG 路径；图标来自官方锁定的 lucide-react-native 1.50.0，保留 24 单位视口、2 单位描边与圆头 / 圆角。相关许可见 NOTICE、third_party/ 与 APK assets/licenses/。

链接输入、按需密码及直连字段在支持 65% / 90% 高度切换的原生底部弹层中显示，不使用 Material 浮动标签输入框。欢迎页设置入口保留主题切换。二维码用 CameraX 和 ZXing 在设备本地解码，释放页面即释放相机；权限拒绝后仍可使用链接与直连。依赖版本依据 [AndroidX Camera 发布记录](https://developer.android.com/jetpack/androidx/releases/camera) 与 [ZXing 发布记录](https://github.com/zxing/zxing/releases)。

## 全应用图标（0.5.0）

`PaseoIcons.kt` / `Iconography.kt` 统一侧栏、聊天、composer、工具调用、审批、文件、diff、终端、主题设置与旧主机编辑弹窗。所有显式 Material Icons 引用与 `material-icons-extended` 依赖均已移除；手机侧栏按钮保留官方 16 / 16 / 8 长度和 1.5 描边，资源面板按钮使用官方 PanelRight（1.5 描边），Git 改动面板沿用官方 SourceControlPanelIcon。

资源由 `tools/sync_official_icons.py` 从同一参考提交生成：52 个 lucide-react-native 1.50.0 图标、9 个内置 provider 标志、54 个官方文件图标及自定义图形。品牌标志保留原始填充、透明度、evenodd 与视口比例；文件 SVG 使用官方 `utils/color.ts` 的 0.65 OKLab chroma，再转换为 Android vector（含 Kotlin 渐变），不叠加单色 tint。用户主机提供的运行时自定义 provider SVG 尚未增加到协议模型，未知 provider 与官方默认规则一样使用 Bot。

工具调用根据官方 `utils/tool-call-icon-name.ts` 选择 Eye、Pencil、Search、SquareTerminal、Bot、Brain、Wrench、MicVocal 或 Paseo 标志；未知工具与命名空间有回归测试。资源文件的许可见 `third_party/`、`NOTICE` 与 APK `assets/licenses/`。


## 对话交互（0.6.0）

对照同一官方提交的 `composer/{submit,actions,input/state}.ts`、`runtime/host-runtime.ts`、`components/message.tsx`、`components/rewind/` 和 `voice/voice-runtime.ts`：

- `ConversationController` 以主机 ID 和代理 ID 为键管理草稿、发送、取消、错误、乐观消息和 FIFO 队列。默认发送 `activeTurnBehavior: steer`；可选择 interrupt 或 queue，运行中长按发送执行相反行为。queue 偏好遇到待审批请求时改为 interrupt，避免把回答排在被审批阻塞的任务后面。
- 发送立即清空草稿。失败恢复文本与附件，保留等待回执期间新输入的内容；重试使用同一 messageId。已收到 canonical echo 时，以时间线确认收件，避免丢回执导致重复恢复。队列可编辑、立即发送，代理转为空闲时只发送下一项；失败退回队首，不循环重试。队列与官方一样保存在运行时内存中。
- `ChatStore` 用独立 Keystore 密钥加密保存草稿、未确认发送记录及本机已发送附件引用；发出网络请求前先落盘 outbox，进程重启后先与 canonical 历史对账，仍未确认的记录可以编辑或用原 messageId 重试。切换代理和主机不串草稿。编辑器展开保留输入，手机回车换行，支持 / 命令与 @ 文件建议（选择后使用官方的双引号路径格式）。
- 模型、模式、思考强度及 daemon 声明的 toggle/select 特性使用对应 RPC，并在请求期间禁用重复切换。停止按钮在回合结束前保持取消状态；审批提交后等待主机解决事件，避免重复提交。
- 用户消息支持复制、长消息展开和由 capability flags 控制的 conversation/files/both 回退；回退会话后将文本恢复到草稿。工具与思考展开、任务列表、错误状态继续使用原生组件；已完成回合按官方默认折叠早期步骤并保留最后答复。Markdown 链接可点击，代码块保留独立复制。
- 图片通过 `images` 发送；普通文件使用 `file.upload.request` 和官方 0x10/0x11/0x12 二进制帧上传，再作为 `uploaded_file` 发送。上传限定 128 KiB 分块与发送缓冲上限，绑定连接 generation，换主机时中止。单文件不超过 50 MiB；大于 4 MiB 的图片先降采样再编码以限制手机内存。保留本机图片缩放预览、附件移除和失败后恢复。Issue / PR 附件按 `forgeSearch` 特性选择新旧 schema。
- `ConversationAudio` 用 AudioRecord 捕获 16 kHz、16-bit、单声道 PCM，使用官方 dictation_stream 与 voice_audio_chunk 协议；处理部分 / 最终转写、取消、转写重试、静音、打断及 audio_played。退出会话或应用进入后台释放录音，后台播放在取消后中止。AEC 可用时启用回声消除，不可用时播放期间抑制录音回传。主机能力不可用时显示其原因。
- 图标同步新增 composer、附件、回退、全屏和语音对应的官方 Lucide 图形，目前共 68 个；不引入近似图标库。

语音服务的模型、密钥与提供商仍由主机配置；真实识别、TTS 音质与硬件回声消除需要真机验证。第三方 JS 插件 UI 和资源搜索、完整 Markdown 表格 / 公式及完整 PR 管理未在这一轮移植，不能将本次实现视为整个官方 app 的完整复刻。


本轮回归包括 44 项核心测试（另有 1 项需要真实 daemon 环境的集成测试未在本轮运行）、16 项 Android 单元测试、Lint 和压缩 APK 构建。核心测试使用官方 relay 库验证 protected / legacy 二进制上传互通。Android 17 模拟器验证普通发送、steer、长按排队、队列编辑与自动续发、失败后的原 ID 重试、审批阻塞时的 interrupt、补全、编辑器展开、Issue 附件、麦克风权限、转写、语音静音 / 播放确认、跨代理草稿和进程死亡后的 outbox 恢复。文件与图片选择器、预览和发送内容使用 SHA-256 对照原始字节。模拟服务不执行真实 AI 或 shell。

## 聊天标题栏与模型设置

对照 `components/headers/compact-header.tsx`、`screens/workspace/compact-workspace-header/`、`components/floating-header/index.tsx` 与 `components/ui/edge-fade.tsx`：标题栏保留工作区标题和项目副标题，标签数量按钮打开代理 / 终端列表，工作区菜单和文件入口位于同一行。聊天时间线从标题栏背后滚动，渐变使用 `surfaceWorkspace` 同色的透明度变化，前 57% 实色、底部 48dp 延伸区域渐隐；不是对文字或整个页面做 blur。展开编辑器时隐藏标题栏，保留系统安全区域。

对照 `composer/agent-controls/utils.ts`、`index.tsx` 和 `model-sheet.tsx`：手机 composer 用单个“模型＋思考强度”入口打开设置面板，面板包含模型、思考强度、权限模式以及主机声明的特性。模型解析保留别名、默认模型和运行时配置；未知模型不借用其他模型的推理能力，不支持思考强度时不显示该选项。更改使用对应 RPC，检查 `accepted` 回执，失败在面板内显示错误，最终值以主机快照为准。

## 回复复制操作

对照 `agent-stream/layout.ts`、`turn-footer.tsx`、`strategy.ts` 和 `components/message.tsx`：助手每轮仅在末尾显示一个复制入口，后续还有工具时放在工具之后；运行中的最新回合使用工作指示，历史已完成回合保留复制。复制范围按 canonical turn ID（旧历史退回触发消息边界）计算，包含折叠的助手 Markdown，以两个换行连接，不包含用户消息、工具输出或 reasoning。停止后使用已有回复恢复操作区，只有工具的回合显示时间而没有回复复制。

复制图标为官方 14dp，按钮内边距 4dp；助手按钮与正文左沿对齐，用户按钮在气泡下方与时间、回退组成 24dp 高的操作行。点击复制后的勾选反馈为 1.5 秒，再次点击重新计时。完成回合默认显示耗时，点击可显示结束时间 3 秒；缺少开始时间时仅显示结束时间。Android 剪贴板与官方 native 分支一致，写入原始 Markdown 文本。

## 侧栏与账户用量

对照 `left-sidebar.tsx`、`sidebar-nav-rows.tsx`、`sidebar-workspace-row-content.tsx`、`project-icon-view.tsx`、`styles/identity-colors.ts` 和 `usage/`：保留原生侧栏 340dp 最大宽度，顶部使用新建 / 关闭 / 历史，项目标题使用官方色板与哈希生成首字母图标，支持 `project.list` 的主机优先使用共享 projectKey，旧主机使用 serverId / projectId 组合，工作区左侧显示状态点、运行圆环或等待输入标记，右侧显示上下文菜单。移除品牌标题和大面积主机状态行；底部为添加项目、用量、主机、帮助、设置入口。配色随当前主题，未强制切换成截图的 Dark 主题。

用量默认显示已用百分比，按官方 `summary` 窗口选择；缺少百分比不按零处理。新版请求收集所有 `usage.list_reports.update` 后等待最终 response，按报告 ID 合并；旧主机回退 `provider.usage.list`。仅侧栏 / 用量面板打开时每分钟读取缓存，手动刷新才发送 forceRefresh；主机切换或断线清空上一主机额度，错误或未登录显示明确状态。详情可切换已用 / 剩余并查看窗口、计划和重置时间。历史入口使用真实 `fetch_agent_history_request` 搜索和分页，活动会话进入聊天，归档会话只读预览最近 200 条记录。

本轮验证：73 项核心单元测试通过，4 项环境依赖测试跳过；20 项 Android 单元测试通过；Debug APK 编译与 Lint 通过。模拟主机覆盖新版分批用量、旧版接口、未登录状态、已用 / 剩余切换与刷新、项目元数据、侧栏宽度和状态位置、项目菜单、历史搜索 / 归档预览及 Dark / Light / Ghostty 截图。未连接用户的真实 Codex 账户验证额度数值。
