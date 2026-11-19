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
