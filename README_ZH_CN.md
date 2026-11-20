<div align="center">
  <h1>Rikkahub-M · Mate</h1>
  <p><strong>保留 RikkaHub 的好体验，让默认助手真正融入 App。</strong></p>
  <p>原生 Android AI 客户端 · 自由模型配置 · AstrBot 私人连接</p>
  <p>简体中文 · <a href="README_ZH_TW.md">繁體中文</a> · <a href="README.md">English</a></p>
</div>

> [!IMPORTANT]
> **0.3.1 发布线。** 聊天走你自己的 AstrBot Mate 网关，长期记忆通过按设备鉴权的接口读写，附件按原始字节上传。包名保持 `com.rikkahub.mate`、沿用 0.2.4 签名，可直接覆盖升级，不会丢聊天和设置；旧 Mate（`com.dafayu.chat.preview`）是另一个 App，不会被覆盖。

## Mate 是什么？

**Mate 是 App 名称，Rikkahub-M 是开发仓库名称。** 项目以 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 Kotlin / Jetpack Compose 架构为基础，保留其原生 UI、自然动画、聊天管理、模型配置及丰富的工具能力，再逐步整合旧 Mate 的 **AstrBot 私人连接、默认人格、长期记忆、历史同步与设备管理**。

目标不是给 RikkaHub 换一个名字，而是让 **独立模型聊天** 与 **服务端聊天** 共存，互不干扰。

## 功能介绍

### 原生聊天体验（继承 RikkaHub）

- **自然、流畅的聊天：** Material 3 Expressive、深浅色主题、页面过渡和长聊天列表。
- **自由选择模型：** 配置自己的 AI 提供商、API 地址和模型；保留独立 API 模式。
- **完整消息表达：** Markdown、代码、公式、图片、文件与多轮会话。
- **实用工具：** 搜索、模型助手、MCP、Skills、语音、工作区与备份等上游功能，具体以已配置能力为准。

### 服务端与 Mate（逐步迁移）

| 功能 | 0.3.1 状态 |
|---|---|
| 设置 → **连接服务器** | 已实现 |
| 一次性配对、协议 3 验证、设备凭据加密保存、二维码配对 | 已实现 |
| 独立服务端聊天页、SSE 流式对话、停止与取消 | 已实现 |
| 图片 / PDF / 文档附件（原始字节上传，8MB × 4） | 已实现 |
| 记忆中心：列表、搜索、筛选、新增、编辑、删除、回收站、恢复、批量删除、操作审计 | 已实现，需网关 `APP_MEMORY_WRITE_ENABLED=true` |
| 把聊天内容手动保存为长期记忆 | 已实现 |
| 峰谷状态与 DeepSeek 余额 | 已实现，读取网关 `/status` 与 `/billing/deepseek` |
| 授权式诊断上传（只发事件名，不含聊天正文与 Token） | 已实现 |
| 设置、聊天、头像昵称备份（WebDAV / S3） | 已实现；密钥类需恢复后重新填写 |
| 签名通用 APK `Rikkahub-M-0.3.1.apk` | 由 GitHub Actions 发布工作流构建 |

**两套记忆不能混为一谈：** RikkaHub 的本地文件记忆与AstrBot 服务器记忆属于不同系统，迁移时必须验证归属、权限和同步边界。

## 第一次使用

1. **想使用自己的 API：** 打开「快速开始 → 独立 API」，先配置提供商和默认模型。
2. **想连接服务器：** 打开「快速开始 → Mate 服务端」或「设置 → 连接服务器」，扫码或输入服务端生成的一次性配对码；聊天、附件和记忆中心都走这个网关。
3. **下载安装：** 到 [Releases](https://github.com/cs9527h/Rikkahub-M/releases) 下载签名 APK `Rikkahub-M-0.3.1.apk`，可直接覆盖 0.3.0。旧 Mate 属于另一个包名，两者并存。

## 我们坚持的体验

**不靠删除动画换流畅，不靠堆叠设置充功能。** 页面简洁，消息清晰，功能好找；不添加广告、赞助名单、返佣推荐或捐赠弹窗。所有重大更新需要有源码记录、构建测试、数据兼容性验证与更新日志。

## 开发与版权

项目采用 Kotlin、Jetpack Compose、Room、Koin、Navigation 3。当前仍使用上游构建体系；如需构建，应配置自己的 `app/google-services.json`，不要复用不属于本项目的生产配置。

- [Mate 集成与迁移记录](docs/MATE_FORK_MIGRATION.md)
- [Mate 使用指南与开发状态](docs/MATE_USER_GUIDE.md)
- [仓库源码](https://github.com/cs9527h/Rikkahub-M)

本项目为 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**非官方独立 Fork**，遵循 **AGPL-3.0**。保留原作者署名及 [许可证](LICENSE)，不冒充官方版本。
