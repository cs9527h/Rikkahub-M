<p align="center">
  <img src="docs/readme-cover.svg" width="100%" alt="Rikkahub-M · Your models. Your assistants. Your experience." />
</p>

<h1 align="center">Rikkahub-M</h1>
<p align="center">
  <strong>把 AI 的选择权，真正交还给使用者。</strong><br />
  多模型直连 · 本地助手 · 自定义提示词 · 聊天与记忆 · 加密备份
</p>

<p align="center">
  <a href="https://github.com/cs9527h/Rikkahub-M/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/cs9527h/Rikkahub-M?display_name=tag&label=LATEST%20RELEASE&color=4169e1" /></a>
  <a href="https://github.com/cs9527h/Rikkahub-M/actions/workflows/release.yml"><img alt="Release workflow" src="https://github.com/cs9527h/Rikkahub-M/actions/workflows/release.yml/badge.svg" /></a>
  <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/LICENSE-AGPL--3.0-7b61ff" /></a>
  <img alt="Android Kotlin" src="https://img.shields.io/badge/ANDROID-KOTLIN-33b79a" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-JETPACK%20COMPOSE-6484f4" />
</p>

<p align="center">
  <strong>简体中文</strong> · <a href="README_EN.md">English</a> · <a href="README_ZH_TW.md">繁體中文</a>
</p>

<p align="center">
  <a href="https://github.com/cs9527h/Rikkahub-M/releases"><strong>获取安装包</strong></a>　·　
  <a href="CHANGELOG_MATE.md">更新日志</a>　·　
  <a href="docs/MATE_USER_GUIDE.md">使用指南</a>　·　
  <a href="docs/STANDALONE_BACKUP_ONLY.md">技术与架构</a>　·　
  <a href="https://github.com/cs9527h/Rikkahub-M/issues">反馈问题</a>
</p>

---

> [!IMPORTANT]
> **版本状态请区分「源码」与「已发布安装包」。** 当前主分支源码版本为 **v0.4.5**；截至本次文档更新，GitHub Releases 中最新公开的正式 APK 为 **v0.4.4**。v0.4.5 的源码变更不等于其安装包已经正式发布，请以 [Releases](https://github.com/cs9527h/Rikkahub-M/releases) 实际列表为准。

## ✦ 这是什么？

**Rikkahub-M** 是基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 开发的 **非官方、独立维护的开源 Android AI 客户端**。它以 Kotlin、Jetpack Compose 与 Material 3 Expressive 为基础，在保留适用的上游原生能力的同时，持续打磨更清晰的模型接入、更可控的本地助手、更可靠的数据管理与更顺手的日常交互。

这不是「给旧 Mate 网关套一个新皮肤」。当前架构的重心已经转向 **原生模型 API 直连 + 本地数据与配置管理**，不再要求使用 Mate/AstrBot 专用聊天网关、设备配对或旧式云端人格注入才能开始聊天。

<table>
<tr>
  <td width="33%" align="center"><strong>MODEL FREEDOM</strong><br />模型由你选择<br /><sub>按需配置供应商、接口与模型</sub></td>
  <td width="33%" align="center"><strong>LOCAL CONTROL</strong><br />助手由你定义<br /><sub>本地配置、提示词与聊天历史</sub></td>
  <td width="33%" align="center"><strong>DATA OWNERSHIP</strong><br />数据由你掌控<br /><sub>备份、恢复与更新安全优先</sub></td>
</tr>
</table>

## ✦ 功能矩阵

> 说明：**✅ 已有实现** / **🧪 继续测试或完善** / **— 不属于当前客户端架构**。有代码不等于所有 Android 设备、服务商和边界情况都已经通过验收。

| 模块 | 主要能力 | 当前状态 |
| :--- | :--- | :---: |
| **AI 多模型聊天** | 基于原生供应商能力，连接用户自行配置的模型 API；实际工具能力随模型与设置变化 | ✅ |
| **本地助手系统** | 自定义助手、名称、提示词及相关配置 | ✅ |
| **提示词注入** | 本地规则管理，Markdown / TXT / JSON 导入与优先级、新会话一致性持续核对 | 🧪 |
| **会话与历史** | 聊天记录、对话操作和原生消息呈现；持续优化长聊天体验 | ✅ / 🧪 |
| **本地长期记忆** | 本地记忆与检索管理路线，提取、编辑、删除、召回等持续验证 | 🧪 |
| **备份与恢复** | 本地文件、WebDAV、S3；0.4.4 重构单页流程与免口令加密备份 | ✅ / 🧪 |
| **应用内更新** | 版本检查、更新日志、失败回退、安装包校验；0.4.5 源码补安装权限引导 | ✅ / 🧪 |
| **UI / 性能** | 深浅色体验、动画、长列表、流式更新和启动性能持续优化 | 🧪 |
| **旧 Mate / AstrBot 网关聊天** | 专用配对、云端聊天、服务器人格与跨端记忆强依赖 | — |

**关于功能真实性：** 本项目不会用 UI 占位或尚未通过的演示流程冒充完整功能。部分能力来自上游，部分是 M 分支独立改造；某项功能可用与否，请优先参考已安装版本的行为和对应发布说明。

## ✦ 产品架构

~~~mermaid
flowchart TB
    U["📱 Rikkahub-M<br/>Android / Kotlin / Compose"]
    M["模型供应商与 API 配置"]
    P["本地助手 · 提示词规则"]
    C["聊天历史 · 本地记忆"]
    B["备份与恢复"]
    API["用户选择的模型服务"]
    LOCAL["本机备份"]
    REMOTE["WebDAV / S3"]

    U --> M
    U --> P
    U --> C
    U --> B
    M -->|"用户配置的 API 请求"| API
    B --> LOCAL
    B --> REMOTE

    classDef primary fill:#244578,stroke:#75A8F8,color:#FFFFFF,stroke-width:2px;
    classDef internal fill:#142B43,stroke:#517FAF,color:#FFFFFF;
    classDef external fill:#18534E,stroke:#62B5A3,color:#FFFFFF;
    class U primary;
    class M,P,C,B internal;
    class API,LOCAL,REMOTE external;
~~~

**设计原则**

1. **独立而不是堆叠：** 采用客户端原生聊天与模型供应商体系，不再长期保留重复的专用聊天系统。
2. **本地优先：** 助手、提示词、聊天与本地记忆尽量由客户端管理；模型推理仍会向所配置的模型服务发送必要请求。
3. **迁移优先保护数据：** 保持包名和签名连续，尽可能支持覆盖升级；旧数据迁移、恢复和回滚必须有验证依据。
4. **功能和承诺分开：** 新能力先在源码、CI、模拟器中验证，再通过真机使用确认，不用宣传词替代测试结果。

架构演进说明：[独立客户端与备份架构](docs/STANDALONE_BACKUP_ONLY.md) · [历史问题与修复记录](docs/LEGACY_BUG_FIX_CHECKLIST.md)

## ✦ 版本进化 / 0.4.x

| 版本 | 核心变化 | 状态 |
| :--- | :--- | :--- |
| **v0.4.5** | 更新安装授权修复：声明安装权限、引导授权、返回后复用已校验 APK 继续安装 | **主分支源码；尚未在 Releases 发现正式 APK** |
| **v0.4.4** | 备份与恢复合为单页；新备份不再输入口令，采用 Keystore 与独立恢复密钥文件 | **已公开正式版** |
| **v0.4.3** | GitHub 403 回退处理、更新信息解析、更新日志与安装包校验改进 | **已公开正式版** |
| **v0.4.0** | 架构迁移中的测试预览版；存在已记录的旧数据误删风险 | **不建议安装** |

[查看全部 Releases](https://github.com/cs9527h/Rikkahub-M/releases) · [v0.4.5 源码说明](docs/releases/0.4.5.md) · [v0.4.4 发布说明](docs/releases/0.4.4.md) · [完整更新日志](CHANGELOG_MATE.md)

> [!CAUTION]
> **不要安装 v0.4.0 测试预览包。** 该版本存在旧聊天数据库及本地备份目录被意外删除的已记录风险。详见 [风险说明](docs/releases/0.4.0.md)。覆盖升级之前请独立备份重要聊天与设置；**不要为了更新而卸载旧版或清除数据**。

## ✦ 下载与安装

**推荐下载入口：[GitHub Releases](https://github.com/cs9527h/Rikkahub-M/releases)**

1. 打开 Releases，选择真正标记为正式发布的目标版本，不要仅根据仓库 README 或源码版本号推断有对应 APK。
2. 下载该版本的 **Rikkahub-M-版本号.apk**；按需核对随发布附带的 SHA-256 校验文件与签名信息。
3. 在 Android 系统安装界面确认安装。同包名、同签名、版本号符合要求时，可覆盖旧版保留数据；具体兼容性以对应版本说明及实际校验为准。
4. 首次使用时配置自己的模型供应商、API 地址与密钥，然后选择模型与本地助手开始聊天。

| 构建信息 | 说明 |
| :--- | :--- |
| 应用名 | **Rikkahub-M** |
| Android 包名 | **com.rikkahub.mate** |
| 安装包策略 | **单一 Universal APK**，覆盖 arm64-v8a 与 x86_64 |
| 主分支 | **master** |
| 核心技术 | **Kotlin · Jetpack Compose · Material 3 Expressive** |
| 授权协议 | [**AGPL-3.0**](LICENSE) |

**没有内置赠送的模型额度。** 使用 API 可能产生第三方服务商费用，模型能力、区域可用性、联网搜索、图片及工具调用等均取决于实际供应商与配置。

## ✦ 数据保护，不止一个“备份按钮”

0.4.4 已在源码中引入更简洁的备份体验：

- **日常免口令：** 新备份由系统生成恢复密钥，使用 Android Keystore 保护本机密钥材料；备份归档仍采用 AES-256-GCM 加密。
- **多种存储位置：** 本地文件、WebDAV、S3 统一入口，不再让相同操作分散到多个页面。
- **跨设备恢复：** 在高级设置中导出独立恢复密钥文件，在新设备导入后用于解密相应备份。
- **敏感信息边界：** API Key、OAuth 等凭据默认排除于常规备份范围，恢复后可能需要重新配置。
- **恢复前校验：** 源码包含归档校验与失败回滚逻辑；真实设备和真实远端存储的端到端验证仍在进行。

> [!WARNING]
> **请将恢复密钥文件与备份文件分开保存。** 如果旧手机不可用、恢复密钥文件也已丢失，可能无法解密旧备份。0.4.4 的跨设备恢复及 WebDAV/S3 实际恢复尚未完成真机端到端验收，重要数据应另留可验证的备份。

## ✦ 工程质量与发布门禁

本项目不把「编译通过」等同于「用户数据安全」。当前发布工作流包括：编译、单元测试、模拟器 instrumented 测试、Android Lint、签名与包名检查、ABI/通用包验证，以及从既有安装包进行覆盖升级的数据契约检查。

- **签名连续性：** 避免后续版本无法正常覆盖升级。
- **数据升级验证：** 关注数据库、应用文件和既有配置的兼容。
- **静态与自动化检查：** 不以关闭 Lint、跳过失败或伪造报告换取绿色构建。
- **可追溯发布：** Release 保留版本号、安装包与校验信息。
- **实机验证透明：** 暂未采集的性能指标和未通过的跨设备恢复，不会写成已完成。

[查看 Actions](https://github.com/cs9527h/Rikkahub-M/actions) · [发布工作流](.github/workflows/release.yml) · [性能基线记录](docs/perf/baseline.json)

## ✦ 接下来要做什么？

| 优先级 | 方向 | 验收重点 |
| :---: | :--- | :--- |
| P0 | **可安装、可升级、可恢复** | 发布一致性、安装权限、旧版本数据保护与真机备份恢复 |
| P1 | **流畅与稳定** | 冷启动、长聊天、流式输出、输入法和动画性能 |
| P1 | **本地助手与长期记忆** | 提示词导入与持久生效、记忆完整 CRUD、边界状态 |
| P2 | **交互品质** | 降低设置复杂度、文案精简、深浅色视觉一致性 |
| P2 | **持续发布体系** | 回归测试、变更说明、可验证的版本与安全记录 |

此处是**开发方向与验收目标**，不是完成承诺；实际状态请参考提交、CI 与发布说明。

## ✦ 常见问题

<details>
<summary><strong>Rikkahub-M 是 RikkaHub 官方版本吗？</strong></summary>

不是。本项目基于 RikkaHub 的非官方独立分支开发，保留适用的原作者版权、上游许可和第三方声明；与上游官方无隶属关系。
</details>

<details>
<summary><strong>还需要 Mate / AstrBot 网关或者配对码吗？</strong></summary>

不需要。当前客户端的主聊天路径使用原生模型供应商直连，不再以旧 Mate/AstrBot 网关配对作为前置条件。仓库中的历史文档可能仍记载旧架构，请以当前发布说明为准。
</details>

<details>
<summary><strong>会自动同步 RikkaHub、DeepSeek 官方账号或其他聊天平台的记录吗？</strong></summary>

不会。应用不等于官方 DeepSeek 账号客户端，也不自动获得其他平台的聊天记录。备份、导入与跨端记忆是不同能力，不能混为一谈。
</details>

<details>
<summary><strong>为什么源码是 0.4.5，下载页却还是 0.4.4？</strong></summary>

源码中的版本号只表示开发进度；只有发布工作流完成并将 APK 发布到 GitHub Releases，用户才有相应的公开安装包。不要从源码版本号推断正式发布已经完成。
</details>

## ✦ 项目导航

| 资源 | 内容 |
| :--- | :--- |
| [使用指南](docs/MATE_USER_GUIDE.md) | 模型配置、助手与基本操作 |
| [架构与迁移](docs/STANDALONE_BACKUP_ONLY.md) | 从旧网关向独立客户端的迁移逻辑 |
| [版本与更新](CHANGELOG_MATE.md) | 历史变更、版本说明 |
| [问题与验收](docs/LEGACY_BUG_FIX_CHECKLIST.md) | 历史遗留问题及核查 |
| [源码与 CI](.github/workflows/release.yml) | 发布检查、签名与打包流程 |
| [问题反馈](https://github.com/cs9527h/Rikkahub-M/issues) | Bug 报告、改进建议 |
| [上游项目](https://github.com/rikkahub/rikkahub) | RikkaHub |

## ✦ 开源与隐私声明

Rikkahub-M 遵循仓库中的 [AGPL-3.0 许可](LICENSE)。这是基于开源项目开发的**非官方独立客户端**，不是 RikkaHub 或任何模型供应商的官方产品。

**安全原则：** 不要在公开 Issue、日志、提交和 PR 中上传 API Key、OAuth 凭据、个人聊天内容、私人助手提示词、恢复密钥或服务器访问凭据。

<p align="center">
  <sub>Built on open source · Made for Android · Shaped by real-world use</sub><br />
  <strong>Rikkahub-M — More control, less friction.</strong>
</p>
