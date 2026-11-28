# 真机性能与长稳采集协议（本轮停止，未实测）

## 用户跳过与当前结论

- 收尾记录时间：**2026-11-27T22:11:04+08:00**（主机时钟；不是手机采样时间）。
- Lead 通知：用户在 Android Shell 连接交接后明确选择「跳过」。本轮跳过手机连接与真机性能、长稳、文件删除耗时验收；不再次调用手机，也不切换 ADB/其他通道补做。
- 本任务在只读核实 API 的步骤边界停止。**没有安装、启动或操作手机应用，没有产生任何真机样本。**
- 状态：`physical_device=skipped_by_user`；`sampling=not_started`；`benchmark_code=not_implemented`。这是用户跳过后的记录状态，不是性能通过状态。
- `docs/perf/baseline.json` 的 `measured=false` 与历史模拟器记录保持原样。本任务没有修改发布门禁；没有真机性能通过结论。
- CI/模拟器回归及发布时的未验收披露由 Lead 处理；它们不替代本次跳过的真机验收。

## 已实现 / 未实现清单

| 项目 | 本轮状态 | 证据与限制 |
|---|---|---|
| 阅读 AGENTS、技能目录 | 已完成 | catalog 没有匹配 DSH 技能；未调用目录外 skill |
| 阅读现有启动宏基准、profile 生成器、MockOpenAIServer | 已完成，只读 | 现有 StartupBenchmarks 仅测冷启动；生成器目前依赖 mock-provider.patch |
| 核实合成数据所需公共 API | 已完成，只读 | 下节列出真实 API；没有写入任何数据 |
| 本协议与待办记录 | 已落地 | 本轮唯一文件改动：本文件 |
| benchmark-only 桥接 Activity / Manifest | **未实现** | 没有创建 `app/src/benchmarkRelease`；不能当作可运行功能 |
| 独立包构建隔离 | **本任务未实现** | `.benchmark` 后缀/debug 签名由 Lead 负责，需以后核实最终产物 |
| MockOpenAIServer 共享源目录迁移 | **未执行，注册请求已撤回** | 没有移动现有文件，也没有修改源集；勿因此增加 app sourceSets |
| 文件滚动/删除、长会话/流式、悬浮球宏基准 | **未实现** | 现有启动类不等于上述场景已覆盖 |
| Android 异步长稳组件 / PSS 与帧样本 / 结果查询 | **未实现** | 没有未知运行中的长稳任务 |
| `collect_physical_acceptance.py` 与 fixture 测试 | **未实现** | 未创建解析器，也没有 synthetic 测试执行结果 |
| Kotlin/Manifest 编译、Gradle、formatter | **未执行** | 本轮没有新增待编译的 Kotlin/Manifest；本文件不是编译证明 |
| 真机冷启动/帧时/PSS/热状态/删除耗时 | **未执行** | 无 JSON、Perfetto、设备/系统/电量/温度数据；不填写数值 |
| 版本 / commit / push / 发布 | **本任务未执行** | 无版本或发布文件改动 |

## 已核实可供后续实现使用的 API

以下是只读核实结果，不表示桥接已实现：

- `SettingsStore.awaitLoaded()` 等待真实设置；`update { latest -> ... }` 返回时已落盘，不能保存独立旧快照。
- `ConversationRepository.insertConversation(Conversation(...))` 在真实 Room 事务中插入会话与消息节点；仅用于新建 benchmark 合成会话，不覆盖运行中会话。
- `FilesManager.saveManagedFromBytes(folder, bytes, displayName, mimeType)` 通过真实托管文件与数据库入口建样本；禁止直接伪造文件列表截图或 measured 数据。
- `ChatService.sendMessage(conversationId, parts)` 驱动真实生成链路；生产内存会话继续以 ChatService 为真值来源。
- `conversationIntent(context, conversationId)` 可打开真实 RouteActivity 会话；RouteActivity 当前没有已核实的文件页 Intent 入口。
- `SettingFilesPage()` 是实际生产文件管理页面；后续若用 benchmark 宿主承载，需提供真实 Koin、Settings、Toaster、Navigator 等 CompositionLocal，不能复制一个简化列表冒充生产页面。
- 悬浮窗由 `ChatFloatingTracker` 在应用后台且开启设置、具有系统浮窗权限时驱动；没有权限或没有实际可见浮窗时不得记为浮窗场景完成。

## 后续恢复时的隔离不变量（尚未执行）

1. 只有用户重新明确开启手机采集后才恢复设备操作；本次「跳过」不授权其他通道重试。
2. 安装前核验最终 APK applicationId 以 `.benchmark` 结尾；不匹配立即停止。baseline 1.1.6 与 candidate 都重建成同一独立包，不覆盖 `com.rikkahub.mate` 或其聊天数据。
3. 合成数据仅在独立包私有目录及其真实 Room 中产生；固定 fixture 版本、合成助手/会话 ID、文件数量与大小、消息条数和 mock 响应节奏。使用前后均记录 fixture 标识。
4. 禁止从生产包导入用户聊天、密钥、附件；删除仅针对本次独立包中带合成标记的文件。
5. 需要新增依赖、共享源集或生产注册时先交 Lead 审核。原先提出的 `baselineprofile/shared` 源目录方案本轮已经撤回，不能视为已配置。

## 待采集场景与完成条件（均为计划）

| 场景 | 后续真实操作 | 必须保留的证据 |
|---|---|---|
| 冷启动 | 同 compilationMode、固定迭代次数，启动实际 RouteActivity 并等待真实页面可交互 | AndroidX StartupTimingMetric 原始样本、JSON 与对应 Perfetto |
| 文件滚动 | 固定数量图片/文本/PDF，实际生产文件页连续双向滚动；包含助手/类型筛选 | FrameTimingMetric 原始样本、trace、fixture 清单与实际可见状态 |
| 文件删除 | 使用实际 UI 删除指定合成文件，等待真实列表消失与磁盘/Room 结果一致 | UI 事件起止时间、目标合成 ID、操作结果、trace；函数执行耗时不能冒充 UI 删除耗时 |
| 图片缩放 / PDF 翻页 | 在实际预览组件中执行固定缩放/翻页手势 | 实际页码/缩放操作、UI 帧时与 trace |
| 长会话流式 | 固定长会话，经过真实 ChatService/OpenAI 兼容 SSE/mock 链路生成并滚动 | 请求完成/错误状态、帧原始样本、PSS 样本、trace |
| 悬浮球 / 面板 | 后台持续真实生成，确认浮窗实际可见，再展开/收起 | 权限及可见状态、目标进程/窗口 trace、帧样本；不以 Activity 自己的空闲帧替代浮窗帧 |
| 长稳 | Android 组件异步运行固定时长，周期执行真实合成生成/界面切换，采样 PSS/热状态/错误 | 稳定 runId、起止时间、连续样本、明确 complete 标记和错误记录 |

Android Shell 单条调用最多 60 秒，且不支持持久 shell 后台任务。未来长稳应由 Android 前台组件异步执行并提供可查询状态与完整报告：同 runId 重复请求只查询，不重新启动；未知结果不得自动重试；进程死亡/报告截断必须标记 incomplete，而不是通过。

## 未来证据解析器的最低契约

- 收集实际 device/model/fingerprint/SDK、物理设备判据、installed APK 包名/versionCode/versionName/SHA256、声明的 source SHA、fixture 版本、实际 compilationMode 与起止时钟。
- 记录充电、电量、温度/thermal 状态及协议条件；缺失值保留缺失，不能使用编造默认值。
- 保留原始 AndroidX JSON、Perfetto、逐次帧/PSS 样本和文件哈希；汇总值必须可追溯到原始样本。
- 拒绝模拟器、非 `.benchmark` 包、缺少原始文件、非有限数、样本空洞、未结束 run、版本/fixture 不匹配。帧 overrun 允许合法负值，不能简单以大于零过滤。
- baseline/candidate 必须同设备、同 fixture、同测量协议与构建条件；不自动把解析成功等同于性能通过。
- fixture 测试的输出必须标为 `synthetic-fixture`，不写入实际基线。只有完整真机证据且经人工验收后，才另行更新 measured；本轮不执行。

## 本轮交接

本文件已记录中止时点、API 调研结果、未落地项和恢复约束。任务以**用户选择跳过后的文档收尾**结束，而非以物理性能验收通过结束。没有任何需要收集或终止的本任务设备/后台进程。
