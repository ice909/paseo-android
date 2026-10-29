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

这一阶段的目标是接通基本编码流程。二维码扫描、语音、推送、附件、归档历史入口、日程、插件、完整 VT 终端、PR 和完整 Git 管理留待后续；未显示没有实现的附件 / 语音按钮。

验证分为协议回归测试、隔离的官方 daemon 0.13.2 文件 / 终端集成测试，以及 Android 17 模拟器上的可重复 UI smoke。模拟服务不执行真实 AI 或 shell；真实 daemon 集成测试也不启动 AI 任务。它们不能替代用户真实主机上的代理与设备性能验证。

## 官方链接配对（0.2.0）

官方 `protocol/src/connection-offer.ts` 和 `daemon-endpoints.ts` 定义 `#offer=` v2 链接、旧版 `relay://` / `#connect=` 链接。原生 `Pairing.kt` 校验版本、端点、公钥及最低安全模式，并将中继路由与密码分开保存。

`RelayChannel.kt`、`RelayCrypto.kt` 对应官方 `relay/src/secure-channel.ts`、`protected-records.ts`、`noise/`、`crypto.ts`。使用 Bouncy Castle 轻量原语，不注册或替换系统 provider。protected 模式认证 Noise NK 握手和分块记录；只在配对策略允许的旧节点上使用 NaCl。protected 数据损坏、重放、错误主机公钥、认证参数异常与安全降级均拒绝继续连接。

`DaemonSocket.kt` 在加密通道建立后才发送 daemon hello 和密码；中继升级请求只有路由参数，不带 daemon Authorization。`AppViewModel` 使用短连接验证配对，只有认证成功后才保存主机；默认从 server_info 读取电脑名称。手机默认只显示链接输入框，密码输入框按主机认证请求出现，自定义名称和直连配置放在更多选项中。

验证使用官方 Noise NK 向量（保留资源中的原始 source 字段）、官方 tweetnacl 已知数据、已安装的官方 relay 库互通 peer，以及隔离的 daemon 0.13.2 的真实公共中继链接。Android UI 回归覆盖链接、密码挑战、主机名称和重启恢复。

## App 主题（0.3.0）

保留官方 theme preference 键、顺序、色板与状态 / diff 语义色。默认 `auto`；系统深色使用 Paseo Dark，额外的 Zinc、Midnight、Claude、Ghostty、纯黑始终为深色。主题保存在独立的设备本地 appearance 偏好中，不写入主机配置；切换无需重建 Activity 或重新连接主机。系统状态栏、导航栏图标依据所选主题更新。

侧栏「设置」进入外观页，支持主题下拉菜单与即时预览。插件贡献主题依赖官方 JS 插件运行时，目前未移植；字体、字号与独立语法高亮主题设置仍留待后续。
