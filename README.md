# Rikkahub-M

**面向 Android 的开源 AI 客户端，自由选择模型，灵活配置助手，掌控自己的数据。**

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 独立维护，采用 Kotlin、Jetpack Compose 与 Material 3 Expressive。

简体中文 · [English](README_EN.md) · [繁體中文](README_ZH_TW.md)

[下载安装](https://github.com/cs9527h/Rikkahub-M/releases) · [更新日志](CHANGELOG_MATE.md) · [使用指南](docs/MATE_USER_GUIDE.md) · [问题反馈](https://github.com/cs9527h/Rikkahub-M/issues)

<!-- RELEASE-BLOCK:BEGIN -->
> **最新正式版：v1.1.6**（2026-11-27，commit `cddb5bee`）
>
> 安装包：[Rikkahub-M-1.1.6.apk](https://github.com/cs9527h/Rikkahub-M/releases/download/v1.1.6/Rikkahub-M-1.1.6.apk) · 18.0 MB · universal（arm64-v8a + x86_64）
>
> SHA-256：`a845185bc145efb777c1b8717ce557a25d1cff702e3fc19cbf37b4a8cf0b5813`
>
> 本版摘要：头像独立管理，修复图片归属延迟、头像替换与备份缺图。
>
> 完整说明见 [v1.1.6 更新日志](docs/releases/1.1.6.md)。
<!-- RELEASE-BLOCK:END -->

## 功能

| 模块 | 体验 |
| --- | --- |
| 模型接入 | 直连 OpenAI、Gemini、Claude 兼容接口、DeepSeek、NewAPI 及自定义 API 地址，无需额外网关 |
| AI 助手 | 自定义模型、头像、人格与提示词，支持 Markdown、TXT、JSON 规则导入及注入顺序设置 |
| 聊天体验 | 会话搜索、消息分支、收藏、翻译、朗读、语音识别及个性化背景 |
| 长期记忆 | 本地记忆提取、整理、编辑与召回，支持独立管理其他助手的记忆设置 |
| 文件管理 | 按助手归类附件，搜索、筛选、预览、导出及关联保护清理；头像独立管理 |
| 文档预览 | 图片缩放、PDF 翻页、文本与 Markdown，以及 DOCX、PPTX、EPUB 文字预览 |
| 备份迁移 | 本机、WebDAV、S3 与自动备份，支持导入原版 RikkaHub、Chatbox、DeepSeek、Cherry Studio |
| 应用更新 | 多源回退下载，安装前校验 SHA-256、包名、版本码与签名 |

## 数据与迁移

完整备份包含设置、聊天记录、附件与 API 凭据，采用普通 ZIP 格式，无需口令或恢复密钥。备份未加密，请妥善保管，不要公开分享。旧版加密备份已不受支持；本项目的完整备份不保证能恢复至原版 RikkaHub。

## 项目说明

- 保留上游设计语言，不引入赞助、捐赠或推广服务商入口。
- 独立包名 `com.rikkahub.mate`、签名与发布流程，提供 arm64-v8a + x86_64 通用 APK。
- 正式发布经过自动化测试、Lint、签名及覆盖升级检查。真机验收与性能测量的边界见各版本说明。

## 更多文档

[历史记忆](docs/HISTORY_MEMORY.md) · [与上游的差异](docs/RM_VS_RB.md) · [逐文件对照](docs/RM_VS_RB_FILE_DIFF.md) · [版本说明](docs/releases/) · [质量台账](docs/QUALITY_LEDGER.md) · [备份架构](docs/STANDALONE_BACKUP_ONLY.md)

## 开源许可

采用 [AGPL-3.0](LICENSE)，保留上游 RikkaHub 版权与署名。修改与分发须遵守相同许可。
