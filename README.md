# Rikkahub-M

**独立开源 Android AI 客户端**

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的非官方独立分支，采用 Kotlin、Jetpack Compose 和 Material 3 Expressive 开发。

**语言：简体中文（默认） · [English](README_EN.md) · [繁體中文](README_ZH_TW.md)**

## 下载与更新

- [GitHub 正式版及历史版本](https://github.com/cs9527h/Rikkahub-M/releases)
- [更新日志](CHANGELOG_MATE.md)
- [v0.4.4 更新说明](docs/releases/0.4.4.md)

当前版本：**0.4.4**，包名 `com.rikkahub.mate`。使用原签名制作单一 universal APK（包含 arm64-v8a 和 x86_64），支持从同签名旧版本覆盖安装。发布状态以 GitHub Releases 页面为准。

**升级前建议先备份重要聊天与设置，勿卸载旧版或清除应用数据。** 旧版 `v0.4.0` 存在已记录的数据误删风险，不建议下载安装；详情见[历史风险说明](docs/releases/0.4.0.md)。

## 主要功能

- **模型直连**：使用自行配置的模型 API，保留原生聊天与部分上游工具能力。
- **本地助手**：支持助手配置及提示词相关功能；具体导入格式、规则优先级以已安装版本为准。
- **聊天与记忆**：本地保存聊天记录，继续优化记忆提取、检索和管理。
- **备份与恢复**：单页管理本地 / WebDAV / S3 备份，新备份自动加密且日常无需口令；换机时导出/导入独立恢复密钥文件即可恢复。
- **更新系统**：0.4.3 修复 GitHub 403 与发布信息解析，提供离线更新日志、失败回退和下载后 APK 完整性及签名校验。

这是**独立客户端**，不再依赖旧 Mate/AstrBot 专用聊天网关；不应把原网关功能或旧的跨平台同步方案理解为现版内置能力。某些功能仍在完善，请以版本说明和实际使用效果为准。

## 发布规则

- **正式发布**要求通过 GitHub Actions 的编译、单元测试、模拟器验证、Android Lint、通用包签名校验和覆盖升级检查。
- **不再强制提交真机验收 JSON 或真机性能指标**；真实设备体验和备份恢复验证仍然建议完成，未验证的功能会如实列明。
- 正式版优先供应用内自动更新；历史版本按原有版本号和包体保留，不覆盖旧安装包。
- 不通过关闭 Lint、跳过失败检查、替换签名或虚构测试结果实现发布。

## 项目与许可

- [项目文档](docs/STANDALONE_BACKUP_ONLY.md)
- [历史问题记录](docs/LEGACY_BUG_FIX_CHECKLIST.md)
- [RikkaHub 上游项目](https://github.com/rikkahub/rikkahub)
- [AGPL-3.0 开源许可](LICENSE)

本项目与 RikkaHub 官方无隶属关系，保留适用的上游版权与第三方许可证信息。请勿在公开仓库提交 API Key、私人聊天、人格提示词或服务器凭据。
