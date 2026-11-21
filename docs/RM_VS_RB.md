# Rikkahub-M（RM）与 RikkaHub（RB）完整差异说明

> 核对时间：2026-11-22。**RM** = [cs9527h/Rikkahub-M](https://github.com/cs9527h/Rikkahub-M)，**RB** = [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)。比对的是 RM `4a5896a65149`（源码 0.4.5）与 RB `2435dbdf4690`（源码 3.3.1），均为 `master`。实际安装包/真机状态不等同于源码状态。本页采用实际代码、Gradle 配置、README、提交与文档作为证据，不把历史宣传文本当作当下功能保证。

完整路径和 blob 哈希见：[**所有 235 个不同文件路径**](RM_VS_RB_FILE_DIFF.md)（RM 独有 106、RB 独有 16、双方均有但内容不同 113；另有 1,472 个内容相同的文件）。在 GitHub 跨分支比较中，RM 较共同基线多 320 提交，RB 另有 10 个上游提交尚未合入。**“差异”可能是新功能、修复、删除、配置、测试或文档，不应简单理解为谁更先进。**

## 一、可确认的产品/配置差异

| 范围 | 上游 RB 3.3.1 | RM 0.4.5 源码 | 证据与判断 |
|---|---|---|---|
| 应用身份 | RikkaHub，`me.rerere.rikkahub`，`versionCode=198` | Rikkahub-M，`com.rikkahub.mate`，`versionCode=20` | 两仓 `app/build.gradle.kts`；可并存，不可用 RB 官方 APK 覆盖 RM；版本代码不可跨不同包名比较大小 |
| 当前版本与发布 | 上游源码 3.3.1 | RM 源码 0.4.5；最近一次 RM README 记载公开正式 APK 0.4.4 | 必须以各自 Releases 真实资产为准 |
| 包架构 | RB Gradle 启用 ABI splits 且输出 universal | RM 删除 ABI split 配置，计划只发布单一 universal APK，含 arm64-v8a / x86_64 | 两方 Gradle + RM 发布工作流；不是两套包合并 |
| 业务主线 | 原生 AI 供应商、助手和聊天 | 延续 RB 原生多模型聊天；移除 RM 历史 Mate/AstrBot 专用网关、配对/云聊天的强依赖 | RM `docs/STANDALONE_BACKUP_ONLY.md` + PR #16；历史 CHANGELOG 中的云记忆描述可能已经过时 |
| 供应商推荐 | RB 存在 `RecommendedProviders.kt`、赞助商、捐赠入口 | RM 删除推广供应商、SponsorAPI/Donate 等，保留用户自行配置的供应商 | RM `docs/NO_ADS_POLICY.md` + 文件差异；**不可删除开源版权与 AGPL 署名** |
| 仓库主页 | RB 默认英文 README，提供中文跳转与官网、Play 下载 | RM 默认简体中文、英文与繁体入口、自己设计的封面、独立 Release | 双方 README；RM 不是 RB 官方版本 |
| 更新系统 | RB 自身官方发布/更新链 | RM 自己的版本检查、GitHub 回退到 Atom/备用源、本地更新历史；0.4.5 新增安装未知应用权限引导 | RM `UpdateChecker.kt`、`UpdateInstaller.kt`、`UpdateParsing.kt`；手机授权流程尚未完全真机验收 |
| 备份页 UI | RB 保留本机/WebDAV/S3/提醒分标签页面 | RM 0.4.4 合并为一个“备份与恢复”页，入口统一、细节收进高级设置 | RM `BackupPage.kt` 与 4 个删除的 tabs 文件 |
| 备份加密 | RB 原生备份功能 | RM 新增 manifest/加密归档/指纹/恢复密钥/自动备份策略等，日常新备份免输入密码；跨设备恢复要单独导出密钥 | RM `BackupCrypto.kt`、`BackupRecoveryKey.kt`、`BackupPassphraseStore.kt` 等；**0.4.4 真机远程恢复未验收** |
| 敏感信息 | RB 有自己的设置/导出逻辑 | RM 备份默认排除 API Key、OAuth、WebDAV/S3 密码等敏感凭据，恢复后可能需重新配置 | RM 0.4.4 发布说明；不承诺“任何东西都无损自动同步” |
| 本地记忆 | RB 公开描述文件记忆能力 | RM 在继承上游基础上加入 Room 34→35 迁移、最近删除/恢复、部分恢复/本地记忆逻辑 | RM `Migration_34_35.kt`、`MemoryDAO.kt`、`MemoryRepository.kt`，完整 CRUD/自动提炼/召回仍须真机验收 |
| 提示词/角色文件 | RB 原生助手、提示词、角色卡导入 | RM 修改 `PromptInjectionTransformer`、导入、助手管理，增加 `.md/.txt/.json` 导入回归测试 | RM `ModeInjectionFileTest.kt`、`PromptInjectionTransformerTest.kt`；跨新会话/切模型/重启持续生效须端到端测试 |
| 通知身份 | RB 原生前台消息通知 | RM 增补当前助手身份/头像匹配与通知去重等修复 | RM `ChatNotificationManager.kt`、`NotificationAvatar.kt` |
| 余额查询 | RB 的 ProviderQuota | RM 扩展手动除数、币种符号、NewAPI 类响应展示与“余额不可用”提示 | RM `QuotaQueryRunner.kt`、`QuotaQueryOption.kt`；仅显示换算，不保证对所有 API 服务商自动识别余额 |
| UI/消息 | RB Material 3 Expressive，长消息、流式、Markdown 等 | RM 改了聊天列表、抽屉、关于、部分模型列表、图标与文案；大部分框架与文件仍与 RB 一致 | 代码差异；不应宣称 RM 已在流畅度上超越 RB，真机性能基线仍未采集 |
| CI/发布验收 | RB 自己的 tag/workflow 发布方式 | RM 自己的 release/workflow、签名指纹、升级数据契约、备份与更新测试、性能台账 | RM `release.yml`、`scripts/upgrade_data_contract.py`、`docs/perf/baseline.json` |
| 旧网关数据 | RB 没有 RM 历史 Mate 网关强制接入 | RM 旧版的专用云聊天功能已退出当前架构；旧资料和日志仍留存用于迁移追踪 | 不应擅自删旧聊天库、旧备份或真实生产服务器 |
| 商标与版权 | RB 官方品牌及原始 AGPL 项目 | RM 独立品牌、独立包名、保留原项目归属与许可 | 独立维护不等于官方授权/官方更新渠道 |

## 二、RB 有而 RM 暂未合入的十个上游提交

比较来源：GitHub `master...rikkahub:master`，截至 RB `2435dbdf`。这些是**提交级缺口**；对单个目标文件应检查 SHA、提交内容与 RM 本地实现，再选 cherry-pick、移植或标记已等价实现，不要盲目覆盖差异文件。

| 上游 SHA | 内容 | 下一版处理建议 |
|---|---|---|
| `2435dbdf` | 上游版本升至 3.3.1 | **不移植版本号、应用包名**；仅跟踪 |
| `2d93676e` | 安全模式备份导入/导出，数据库迁移失败时只读快照 | P0 优先移植并适配 RM 加密备份、恢复密钥与原有数据 |
| `fbfb9926` | Google 模型列表缺失 displayName 时回退 modelId | **0.5.0 已移植**（`9f328b1d`） |
| `48a68f87` | 模型列表长 ID 显示两行 | **0.5.0 已移植**（`44ec014b`） |
| `b7cf74dd` | 安卓无障碍授权已开启但服务被系统停用的提示与重连 | **0.5.0 已移植**（`060e7701`） |
| `e4646a77` | Markdown 聊天导出带消息时间 | **0.5.0 已移植**（`18d0e0b4`），既有导出格式不变 |
| `a2479770` | 上游自动 tag 发布工作流 | **不直接合入**，保留 RM 自己的签名/通用 APK/更新源门禁 |
| `358e2931` | bettertext 依赖升级 v0.1.6 | **0.5.0 已移植**（`d7f23e8c`），随 CI 回归 |
| `1ed0a77c` | SenseNova 图标、NVIDIA 匹配规则 | 可只补必要图标匹配，不恢复赞助/推广 |
| `a9e9e53a` | 删除上游自动关闭空白 Issue 工作流 | P2 按 RM 自己的社区维护策略决定 |

**禁止**把这些十个提交一键全 cherry-pick 后就声称完成；先比较代码和产物影响。也不能自动同步 RB 的 `applicationId`、签名、下载域名、赞助入口、历史云网关配置。

## 三、目前两边相同的基础能力（不应重复造轮子）

当前文件树有 1,472 个 blob 完全相同，包含大部分原生 Android/Compose/AI 框架。RB README 描述 Material 3 Expressive、Workspace（proot）、Phone Use、图像和视频生成、多个模型提供商、Web 访问、MCP/Skills、Markdown/LaTeX/Mermaid、联网搜索、角色卡、翻译、HTTP 自定义头等。RM 源码在相应模块仍保留大量共同文件，**但是否在当前 APK 可正常使用须分别验证**。下一版应保护原生这些功能，不要因为“精简”误删它们，不要复制一套并行 AI/聊天/存储系统。

## 四、RM 已提交但仍存在的重点风险

- **P0 数据与恢复**：0.4.4 零口令加密备份已有源码与自动化测试，仍欠同机、换机、WebDAV/S3 实际端到端恢复；密钥文件与加密备份文件必须分开保管。
- **P0 更新链**：0.4.5 源码补了 `REQUEST_INSTALL_PACKAGES`、用户授权与返回恢复安装；真机流程及最新 0.4.5 Release APK 状态应重新核验。
- **P0 安全模式**：上游新增安全模式备份能力未合入；须确保数据库迁移失败时也能抢救聊天与附件，恢复不会破坏现有数据。
- **P1 性能**：`docs/perf/baseline.json` 标注 `measured=false`。真实冷启动、长列表、流式重组、IME 动画、内存与掉帧无有效真机基准；不能说已优化完成。
- **P1 长期记忆与提示词**：长期记忆 CRUD/删除恢复/召回、规则导入与新会话稳定性需实际回归；不要把历史云端记忆当作现行本地能力。
- **P1 UI**：设置冗余、导航、搜索/消息管理、动画、深浅色一致性需操作录屏与性能指标验证，不得删除所有动画“解决卡顿”。
- **P1 兼容**：用户已有模型、助手/名称/头像、提示词、旧聊天、附件和旧备份都要原位保留。不要自行卸载/清空。
- **P2 文档**：历史 `CHANGELOG_MATE.md`、老 `docs/MATE_*` 包含过时服务端架构；只应作为历史，当前功能说明以此文和当前发布说明为准。
- **维护责任**：保留 AGPL-3.0、上游版权、许可证及归属，RM 是非官方 fork；不复制 RB 的赞助推广，也不删除法定署名。

## 五、永久同步差异的规则

1. 每次更新先固定 **RM master SHA / RB master SHA / 当前 Release tag**，生成 [路径清单](RM_VS_RB_FILE_DIFF.md)，不可拿旧统计冒充最新。
2. 对路径差异附类别、两边 blob SHA、业务影响、测试证据。**全部路径都可追溯**，不等于已经对每个文件执行语义审计。
3. 每个有价值的上游新提交建追踪项，状态为 `待评估 → 处理中 → 已移植 → 已测试`，附实际提交；不适合的写清“明确拒绝”和理由。
4. 永远保留 RM 产品身份、独立包名/签名、无广告赞助、单一 universal APK、简体中文默认、数据迁移安全和当前独立架构。
5. 每个未来版本至少更新本页、文件级清单、[下一版路线图](ROADMAP_0_5_0.md)、CHANGELOG、README 的相关入口，拒绝只修改版本号或文档宣称功能完成。

## 六、直接证据入口

- [RM 配置](https://github.com/cs9527h/Rikkahub-M/blob/4a5896a/app/build.gradle.kts) / [RB 配置](https://github.com/rikkahub/rikkahub/blob/2435dbd/app/build.gradle.kts)
- [RM 0.4.4 备份说明](releases/0.4.4.md) / [RM 0.4.5 更新说明](releases/0.4.5.md)
- [RM 独立架构](STANDALONE_BACKUP_ONLY.md) / [RM 历史缺陷清单](LEGACY_BUG_FIX_CHECKLIST.md)
- [RM 无赞助原则](NO_ADS_POLICY.md) / [性能证据](perf/baseline.json)
- [RB README](https://github.com/rikkahub/rikkahub/blob/2435dbd/README.md)
- [完整差异路径、SHA 与统计](RM_VS_RB_FILE_DIFF.md)

> 本文目的是**全面记录目前能验证的差异和未验收边界**，不是声称每个不同文件都已审过实现逻辑。要确认“全部运行时差异”，下一版还必须执行逐文件语义检查、两边 APK 操作对照、自动化与真机回归。
