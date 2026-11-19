# Mate 0.1.1 重构记录（对照 V2 执行书）

## 已落地

1. **产品身份**：`app_name` 全部语言改为 `Mate`；包名保持 `com.rikkahub.mate`，Kotlin namespace 不动。
2. **服务端聊天不再是独立模式**：新增 `MateChatProvider`（`app/src/main/java/me/rerere/rikkahub/mate/MateChatProvider.kt`），
   把 Mate 网关（协议 v3）实现成标准 `Provider`。`ProviderManager.getProviderByType` 依据
   `chatCompletionsPath == /chat/stream` 路由到它，于是服务端消息走 RikkaHub 原生 `ChatService`/`ConversationRepository`/`ChatVM`。
3. **默认助手就是李鱼**：`DEFAULT_ASSISTANTS` 名字改为「李鱼」并指向内置模型 `liyu`；
   `DEFAULT_PROVIDERS` 增加内置「Mate 服务端」提供商（`MATE_PROVIDER_ID` / `MATE_MODEL_ID`）。
   升级用户那个从未动过的空白默认助手，会在 `SettingsNormalization` 里补成李鱼，不覆盖用户已改过的助手。
4. **设置页去重**：删掉旧的重复聊天入口，只留「连接与设备」下的「连接服务器」，
   文案改走字符串资源（`mate_settings_group` / `mate_connection_title` / `mate_connection_desc`，含 values-zh）。
5. **安全**：网关地址与设备令牌仍只从 `MateGatewayStore` 的 AES-GCM 存储实时读取，
   不写进设置库、不进日志、不进 APK。

## 被替换 / 仍然存在但已非主路径

- `MateChatPage`、`MateLocalHistory`：仍在仓库里，可从未删除的路由进入，但已不是默认聊天；
  下一步应把它们的能力（断线查询、服务器状态）搬进原生会话后删除。
- `QuickStartCard`：只在「没有可用模型」时出现；默认助手绑定模型后，首次安装不再出现。

## 未完成（0.1.1 明确不声称）

- 设置没有重排成 V2 要求的七个分组，仍是原有结构 + 删重复入口。
- 全量中文化只覆盖了 Mate 新增入口；RikkaHub 原有页面在中文环境下仍依赖 `values-zh` 既有翻译。
- 聊天页未做「未连接服务器」的内联连接卡片，未配对时首条消息会报错提示去配对。
- UI 重做（顶部助手头像/状态栏、悬浮箭头遮挡修复）未做。
- 真实服务器配对、真机性能、旧 Mate 数据迁移、长期记忆读写均未验证。


## 0.1.2-preview.1（2026-11-19，PR #6）

- 修复：`MATE_PROVIDER` 之前只建了常量却没进 `DEFAULT_PROVIDERS`，导致李鱼助手解析不到模型、
  首屏仍弹"没有可用模型"。现在它排在默认服务商第一位。
- 修复：`mate_intro_summary` 未转义的撇号、`quick_start_page_setup_desc` 未转义的 `&`
  （后者就是 PR #6 里 aapt 报的 1878 行），全部资源文件用 XML 解析器逐个验过。
- 新增单测：默认助手能解析到 `liyu` 模型；升级设置会补回缺失的内置服务商；
  用户自建服务商和助手在规整后原样保留、内置项只补一次。
- 命名按最新规则：应用名 `Rikkahub-M`，内置服务商 `Mate 服务端`，模型 `Mate 网关`，
  设置入口 `Connect to server / 连接服务器`。默认助手仍叫李鱼。
- Release：https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.1.2-preview.1


## 0.1.3-preview.1（2026-11-19）

- 助手身份：`Assistant.useAssistantAvatar` 默认改为 true；默认助手带 `🐟` 头像与「李鱼」名称；
  `ChatMessageAssistantAvatar` 对 Mate 网关强制使用助手身份，不再把「Mate 网关」和 `liyu` 字母图标当 AI。
- 聊天顶部：去掉 `李鱼 / Mate 网关 (Mate 服务端)` 的拼接，第二行只显示助手名。
- 标题生成：快速模型丢失时退回当前会话模型；网关模式改为本地标题（`localTitleFrom`），
  不再向服务器发元数据请求，避免污染对话与记忆。
- 单测：`LocalTitleTest`（取首条用户消息、压空白、截断、空值兜底）+ 助手身份与默认快速模型断言。


## 0.2.0（2026-11-19，正式版）

- 版本：`versionName = "0.2.0"`、`versionCode = 6`、tag `v0.2.0`、产物 `Rikkahub-M-0.2.0.apk`
- 云服务器模式不再挂载客户端 MCP/搜索工具，`ChatService` 对网关跳过"模型未开启工具调用"提示，修掉正常回复时弹出的红色「工具不可用」
- 聊天顶部：第二行只显示助手名与服务器真实返回的峰谷/余额，取不到就不写，不再出现"峰谷未知 · 余额不可用"占位
- 峰谷与 DeepSeek 余额：`/status`（校验 `instance_id` 与 `valid_for_seconds`）、`/billing/deepseek`（校验 `provider` 与金额格式），60s 轮询，失败按未知处理，不显示 0
- 精简侧栏（移除媒体创作/图像生成入口，聊天图片功能保留）与设置页入口；关于页/更新日志统一 Rikkahub-M 品牌
- 内置 Mate 服务商/模型显示名安全迁移为「云服务器」，用户自定义名称不动
- 发布工作流：产物命名固定，新增脚本语法自检；`bash -n` 校验通过后才推 master

仍未完成：Token/耗时统计、`getQuota()`、附件传输、配对二维码失败根因、自动备份落盘验证、旧 Mate 数据迁移。
