# Rikkahub-M

**独立 Android AI 客户端｜本地人格与记忆｜模型 API 直连｜可恢复云备份**

> **0.4.0 开发中，未正式发布。** 当前 GitHub 草稿 PR #16 仍存在编译问题；页面描述的是重构目标，不能当作已验证的稳定功能。

## 项目定位

Rikkahub-M 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的非官方独立 Fork，保留 Kotlin、Jetpack Compose、Material 3 Expressive、原生聊天、自然动画、搜索、MCP、Skills 和多模型等有效能力。**不再维护 AstrBot/Mate 专用聊天网关、配对码、云端人格注入和跨平台记忆系统。**

架构：
- Android 手机直接连接用户配置的 AI 模型 API；本地管理助手、提示词、聊天记录与长期记忆。
- 云服务器仅作为备份存储；当前已有 WebDAV/S3 客户端，未来优先研究 S3 兼容存储。
- 本地人格提示词支持 Markdown/TXT/JSON 导入及模式注入；需持续验证新聊天、重启和多助手隔离。

## 0.4.0 待完成事项

- 修复 PR #16 编译失败和旧网关残留引用，保证现有模型配置迁移正确。
- 完善本地长期记忆的提取、检索、增删改查和跨会话召回。
- 设置高频轻量备份、聊天数据库单独低频备份；默认不自动备份附件。
- 实现包含 API Key 等配置的客户端加密备份、密钥跨机恢复、版本保留和去重。
- 测试 WebDAV/S3 真正上传与恢复；修复长聊天卡顿、流式输出、后台通知身份、键盘与页面动画。
- 延续包名 `com.rikkahub.mate`、旧版本签名、数据兼容；最终只发布一个通用 APK。

**注意：目前的便携 ZIP 备份并非经验证的端到端加密；涉及隐私的真实备份应在加密功能验收后上传。**

## 当前开发

- [客户端独立模式 PR #16](https://github.com/cs9527h/Rikkahub-M/pull/16)（草稿、未合并）
- [旧功能 PR #15](https://github.com/cs9527h/Rikkahub-M/pull/15)（仅挑选适合新架构的通知、UI、备份代码）
- [历史问题与验收清单](docs/LEGACY_BUG_FIX_CHECKLIST.md)
- [独立架构说明](docs/STANDALONE_BACKUP_ONLY.md)
- [正式发布页面](https://github.com/cs9527h/Rikkahub-M/releases)

不上传私人聊天、真实人格原文、API Key 或服务器密钥。项目不是官方 RikkaHub，遵循现有 [AGPL-3.0 许可证](LICENSE)，保留上游著作权与第三方许可证。