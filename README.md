# Paseo Native for Android

基于 Kotlin + Jetpack Compose 的非官方 Paseo Android 客户端，连接已有的 Paseo 主机。

支持 Android 8.0 及以上。包名为 `sh.paseo.android`，可与官方客户端共存。

## 功能

- 多主机连接、官方配对链接和加密中继。
- 项目与工作区管理，多代理聊天、模型及思考强度选择、工具审批。
- 图片与文件附件、文件浏览和编辑、Git 改动查看、文本终端。
- 官方主题、历史会话搜索和 Codex 用量显示。

## 使用

1. 在电脑上运行 Paseo，通过「配对设备」或 `paseo daemon pair` 获取配对链接。
2. 在应用中选择「添加主机」，粘贴链接并连接；主机需要密码时会提示输入。
3. 选择已有工作区，或新建工作区开始任务。

也支持在「更多选项」中手动输入主机地址，通过局域网或 VPN 直连。

## 开发

需要 JDK 27、Android SDK 37.2 和 Build Tools 37.0.0。

- [构建与测试](docs/development.md)
- [官方功能对齐记录](docs/official-alignment.md)
- [项目协作与构建内存约束](AGENTS.md)

`app/` 为 Android 界面，`core/` 为协议与网络层，`tools/` 为开发和验证工具。

仍在持续对齐官方客户端；完整 VT 终端、推送通知和插件 UI 等功能尚未实现。

## 许可

[Apache-2.0](LICENSE)。第三方资源说明见 [NOTICE](NOTICE)，上游项目为 [Paseo](https://github.com/getpaseo/paseo)。
