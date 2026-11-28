# App service / utils 异常审查台账（task-17）

## 范围与当前阶段

- Owner：speech-fixes；只负责 `app/service`（排除 `ChatService.kt`、`ChatResilienceTest.kt`）与 `app/utils` 及对应回归。
- 已读仓库 AGENTS、最新技能目录；无匹配普通 Kotlin 技能，使用真实 read/grep/glob。
- 当前阶段：Lead 已解除首次 Gradle 冻结，并明确收敛为 Media 输入取消清理、Updater 真实 Call 取消与 CE/Error、Context 图库资源/回滚/泄漏诊断；这些限定修复与回归已写入，现冻结等待 Lead 统一编译执行。
- 不改 speech 库（network-fixes 所有）、DI、版本或构建；不运行 Gradle/formatter/手机，不 commit/push。
- 公共 helper 已读：`formatExceptionForLog` 不输出 message/用户文件名，仅常量操作名、类型及有界代码帧；`runCatchingCancellable` 只捕获恢复性 Exception，CE/Error 直接抛出。

## 已逐项读上下文

### MediaCreationService.kt（完整主流程 1–550；尾部工具待续读）

| 位置 | 分类 | 证据与处理理由 |
|---|---|---|
| submit 131–135 | 待修/取消清理 | catch Throwable 做清理后重抛本身合理；但取消后 `withContext(IO)` 在入口检查取消，会跳过目录清理，还可能替换原异常。解冻后将最小文件清理窗口置于 NonCancellable + IO，并测试取消/原异常保持。尚需核对 insert 已成功、updateSession 失败时持久化记录与文件的关系。 |
| resumePending 215–231 | 保留已移交改动 | cancellable wrapper + 常量脱敏日志；取消直接传播，不将恢复中断静默当成功。 |
| run 258–267 | 保留已移交改动 | CE 独立重抛；仅 catch Exception，且 ensureActive，避免取消后标 FAILED；Error 不吞。 |
| generate 286–290 | 保留 | NonCancellable 只用于已创建远端任务 ID 的持久化，保护远端任务可恢复性；不扩大到整段网络。 |
| awaitTask 358–369 | 保留已移交改动/待核对 | query 返回失败 Result 时显式 CE 传播与脱敏日志；需核对 Error 是否可能被上游 Result 包装。 |
| copyInputs 379–394 | 清理保留/待修边界 | IO 内 catch Throwable 删除半成品后重抛合理；但成功返回时 withContext 的 prompt cancellation 可能丢失结果而不进入块内 catch，留下无记录目录。解冻后补实际临时目录取消回归，避免仅源码断言。 |
| downloadOutputs 425–436 | 保留已移交改动 | 尾帧为可选能力，可恢复下载失败降级；CE/Error 通过 cancellable wrapper 直抛，不继续误报完成。 |
| download 474–479 | 保留已移交改动 | CE 先重抛、ensureActive、日志使用常量脱敏；不取消重试整个工作流。 |
| downloadOnce 488–518 | 保留 | 独立 canceller 关闭真实 Call，read 阻塞时也能退出；response/input/output use 关闭，finally 删除 partial。 |

### ContextUtil.kt（完整 1–255）

| 位置 | 分类 | 证据与处理理由 |
|---|---|---|
| writeClipboardText 47–52 | 待修 | 成功/失败日志直接包含完整剪贴板文本；错误 throwable message 同样可能含内容。改常量操作诊断，保留用户 toast，不改变复制功能。 |
| openUsageAccessSettings 84–90 / openAccessibilitySettings 94–98 | 保留行为/改诊断 | 同步系统 Activity 启动失败时合理 fallback；使用只 catch Exception wrapper 保留 CE/Error，并脱敏 throwable。 |
| openUrl 105–113 | 待修 | 日志输出完整 URL（含 query/token）及 printStackTrace；操作日志应为常量，用户可见提示与日志分离。 |
| writeImageToGallery 190–195 | 保留清理/改诊断 | 失败删除未完成 MediaStore 项是必要回滚；需让 CE 传播，清理后 Error 原样抛出，不改既有成功判定。 |
| exportImageFile 233–253 | 待修资源 | `file.inputStream().copyTo(...)` 没有 use 关闭源，异常时可能保留未完成 MediaStore 项，finally close 可覆盖原失败。待解冻用 use 生命周期和失败回滚修复，保留旧系统权限分支。 |

## Checkpoint 2：进一步已读与分类

以下列出的文件均已完整读取；行号按冻结时源码。

| 文件/站点 | 保留/待修 | 真实理由 |
|---|---|---|
| BackgroundShellManager 167–169 | 保留 fallback、改日志 | 守护线程 waitFor 失败仍须发布退出状态；Exception 范围合理，异常诊断不得含外部进程路径/命令。 |
| BackgroundShellManager 244–246 | 保留最小不可取消清理、改 wrapper/日志 | kill 必须在取消后继续执行，不能扩大 NonCancellable 到整个命令；runCatching 会吞 Error，应只捕获 Exception。 |
| BackgroundShellManager 259–261 | 保留可选尾日志 fallback、改 wrapper/日志 | 读尾日志失败不应阻止 exited.complete / foreground.release；不记录日志内容或原始 throwable。 |
| ConversationSession 387–394 | 保留取消/失败策略、改日志 | CE 已直抛；保存失败保留会话不回收是正确的数据保护，当前 throwable/message 可能泄漏会话数据。 |
| ConversationSession 249–261、265–275、424–431 | 保留 | 门禁等待旧任务、最终已生成内容落库、审批链取消 join 均有明确一致性语义，不机械缩减 NonCancellable。 |
| ConversationSessionManager 全文 | 保留 | 无宽泛 catch/runCatching；withSession 用 finally 释放引用，注册表移除后锁外 cleanup，保留全部既有并发修复。 |
| BackgroundShellForegroundService 41/54/128 | 保留 Android 启动降级/释放语义，改 wrapper/日志 | 系统拒绝 FGS 时布尔失败、不强行阻断任务；recoverable Exception 降级合理，Error/CE 不应被 runCatching 包装。 |
| MediaCreationForegroundService 42/55/138 | 同上 | 保留任务持有与通知职责；另记录 release 使用无 startId stopSelf 的既存竞态候选，暂不扩当前异常修复范围。 |
| MemoryDreamForegroundService 41/55/120 | 同上 | 后台启动受限时不保活是明确设计；release 使用 startId 保护后续 acquire，保留。 |
| ChatGenerationForegroundService 58/71/161 | 同上 | 不碰 ChatService；acquire/release/通知失败保持可恢复 fallback，脱敏诊断。 |
| ChatGenerationForegroundService 114 | 保留纯解析 fallback | Uuid.parse 的无效缓存 ID 不应阻止 onTimeout 立即 stopService；改 Exception-only wrapper 但保留 getOrNull 语义。 |
| WebServerService 106–111 | 保留 OEM 兼容 fallback，改日志 | foreground 被 ROM 拒绝时报告错误并返回 false 正确；用户可见 reportError 与脱敏日志分离。 |
| ImageUtils 39/65、109/135、146/160、206/225、236 | 保留功能 fallback，改 wrapper/日志 | 加载/EXIF/识码/信息/卡片失败可恢复；runCatching 吞 CE/Error 且 printStackTrace 可包含 URI。EXIF 110–112 在构造抛错时源流未关闭，待改 use。 |
| UpdateChecker 171/183/333/373 | 保留文件缓存降级，改 Exception-only wrapper | 缓存缺失/写失败/旧文件清理是可选能力；不影响正常更新检查，日志需固定操作名。 |
| UpdateChecker 175、285、313–324 | 待修取消传播 | init 的 refresh、cache load 的宽 catch、SHA256 可选下载的 runCatching 包含挂起调用；CE 应直抛而非触发替代数据/校验降级。 |
| UpdateChecker 261/269/277 | 保留 | 多源读取的 CE 分支已独立抛出，其他 Exception 继续下一源合理。 |
| UpdateChecker 413–417 | 保留边界取消处理 | 下载最外层 CE 转 null 后在 Main 发布 UI 清理，属于明确入口终态，不应无脑改成抛出导致 downloadJob 永久占槽。 |
| UpdateChecker 473–477 | 待修日志 | 输出 raw error.message 可能含完整 URL/路径，改固定操作名+helper；用户展示的故障分类另保留。 |
| UpdateChecker httpGet 618–630 | 待修真实取消链 | withContext(IO) 不取消同步 execute/body.string；需要保留 IO 读取同时绑定 Call.cancel，不退回 Main 读 body。 |
| UpdateDownloadEngine 206–246 | 待修 CE/Error 与调用取消 | runCatching 把 transport 回调的 CE/Error 当作网络失败，重试/切源；改 Exception-only wrapper，真实 Call 与父协程取消绑定。 |
| UpdateDownloadEngine 282–344 | 待修取消 | 仅检查 isCancelled 布尔，read/execute 阻塞时用户取消最多仍占用 45s；需要真实 socket/Call 的取消回归。 |
| UpdateDownloadEngine 286/355/368/402 | 保留恢复性校验/元数据/删除 fallback，改 Exception-only范围 | 416 验证失败重置、缺失 meta 禁续传、可选元数据与清理不阻断下载均有合理语义；不吞 Error。 |
| UpdateInstaller 54–77 | 待修 | suspend download 的 runCatching 会包装 CE/Error；保留 Result<recoverable Exception>，取消/Error直抛。 |
| UpdateInstaller 115–122 | 保留 OEM 设置页降级 | 第一个设置 Intent 失败尝试无 package 页，第二次失败可保留 no-op但需可诊断；限定 Exception。 |
| UpdateDownloadService 60/126/245/252/263 | 保留系统权限兼容降级，改诊断和wrapper | 当前返回值/UI 已如实报通知失败；不改仅主线程 binder 的边界，不把耗时 IO 移回。 |
| NotificationAvatar 30/58 | 保留可选图标 fallback，改 Exception-only范围 | 通知头像失败可回落小图标；原始 URI 不进日志。当前 decode 非本轮重构，记录固定 inSampleSize 的峰值风险。 |
| PngTextChunkReader 127 | 保留 skip 兼容 fallback，改 Exception-only范围 | 某些 InputStream 不支持 skip 时通过 read 跳过是合理设计；Fatal Error 不应视作 skip=0。 |
| DatabaseUtil 16–18 | 保留反射兼容 fallback，改诊断 | Android 私有字段可能被移除；不应导致启动失败；printStackTrace 换有界 helper。 |
| CrashHandler 全文 | 待修日志及持久报告脱敏 | 默认崩溃 handler 继续接管应保留；当前 thread.name 与 stackTraceToString 保存原始 message，可能包含 key/prompt/个人路径，应保存常量操作名生成的有界诊断。 |
| SoundEffectPlayer 全文 | 保留/范围外风险 | 无 catch；SoundPool 的 load/播放/释放逻辑已读，回调集合线程假设需实测，不凭本轮异常扫描断言故障。 |

## Checkpoint 3：无宽泛异常站点的完整阅读

- service 已完整阅读：`ChatFloatingTracker`、`ConversationInbox`、`ConversationSessionManager`、`RuntimeAttachmentReferences`、`CompressionInput`、`ChatNotificationManager`、`WorkspaceDocumentsProvider`；本轮无新增 catch/runCatching 更改需求。尤其保留 finally 引用释放、队列完成通知、运行中附件 fail-closed 与有界扫描、DocumentsProvider 的异常交由框架上报。
- utils 已完整阅读：`CacheUtil`、`ChatUtil`、`ClipboardUtil`、`CollectionUtils`、`ComposeExt`、`DiffUtils`、`EmojiUtils`、`Json`、`MarkdownUtils`、`SecretCipher`、`SoundEffectPlayer`、`TimeUtil`、`UiState`、`AIIconMatcher`、`UpdateDownload`、`UpdateTransferPolicy`、`UpdateParsing`、`NotificationUtil`、`UpdateNotes`、`StringUtils`、`Version`。均无待替换宽泛 catch；正常解析错误继续向调用方传播，不给纯函数机械增加吞异常层。ChatUtil 的 UUID 导航日志不包含消息/prompt/附件，不为此次异常审查改导航。
- 既有测试已读：`UpdateDownloadEngineTest` 1–340（其余待续读）、`ConversationSessionTest` 1–110（其余未宣称已读）；已枚举 service 对应测试。尚不存在实际 MediaCreationService 取消清理回归，本轮需新增真实临时文件/挂起调度回归，而非只测方法或源码字串。

## 最终限定修复（2026-11-28）

前表保留的是初审时站点与判断，不是全部已修声明；下表为最终实际落地范围。Lead 最后指令要求只收尾已确认的三个问题组，不再扩写其他文件。

| 实际文件 | 已落修复与不变量 |
|---|---|
| service/MediaCreationService.kt + MediaInputCleanup.kt | 回滚 owner 放到 submit 的 IO 调度外层，覆盖 copyInputs 成功返回时的 prompt cancellation 窗口；仅 DB 归属检查与删除在 NonCancellable+IO 中运行；已入库记录不删素材，DB 查询失败 fail-closed；清理异常作为 suppressed，不替换原 CE/Error。保留 register-before-insert、取消/最终帧处理、APIMart/5秒轮询/前台服务行为。 |
| utils/CancellableHttpCall.kt | owner 从 execute 一直覆盖到完整 body 消费；父协程取消时取消真实 Call，解除阻塞 execute/read；finally 取消清理子作业，不遗留 watcher。 |
| utils/UpdateDownloadEngine.kt | transfer 通过上述 owner 绑定真实 Call，仍在 download 的 IO 段读取与写文件；attemptSource、416校验、meta/清理 fallback 使用 Exception-only wrapper；每次尝试及结果处理检查 active。CE/Error 不重试、不切换源，既有 Range/If-Range/416/哈希/大小合同不变，取消保留 partial/meta。 |
| utils/UpdateChecker.kt | httpGet 仍同步 execute+body.string 且显式 IO；接入 Call owner；refresh/init/可选SHA/cache fallback 不再吞 CE/Error；下载失败日志不写 error.message，改固定 update.download 操作名。保留最外层入口 CE→UI 取消终态清理，避免占槽。 |
| utils/UpdateInstaller.kt | suspend 下载包装与 OEM 设置页回退使用 Exception-only wrapper；恢复性错误仍返回 Result，CE/Error直接传播；保留包名/versionCode/签名校验。 |
| utils/ContextUtil.kt + ImageExportIo.kt | bitmap/文件共享 pending MediaStore 事务式导出，只有写入并关闭流且提交 pending 成功才返回 Saved；文件源/目标均 use，输入打开/写入/close失败均回滚；CE/Error 回滚后直抛，二次清理错误 suppressed；剪贴板、URL、异常日志不再写用户原文，保留权限请求与用户 toast。 |

### 新增真实回归（11 个 @Test 方法，待 Lead 执行）

- `service/MediaInputCleanupTest`：3项。真实临时目录+IO交接时取消后孤目录删除；已持久记录素材在后续失败时保留；DB归属检查失败时保留目录且不替换CE/Error。
- `utils/UpdateCancellationTest`：3项。真实本地HTTP socket挂headers取消；收到部分body后挂起取消、保留partial；实际生产下载回调抛CE/Error保持原异常且请求次数为1，无重试/切源。前两项通过 OkHttp EventListener.canceled 核对真实 Call.cancel，而非只观察 Job。
- `utils/ImageExportIoTest`：5项。源/目标成功均关闭并保持字节；输入打开失败关闭目标并删除真实pending文件；写失败关闭双流且回滚失败不替换主错误；目标close失败删除真实pending文件；CE/Error回滚后仍原样传播。
- 另只读复核既有 `UpdateMainThreadSourceGuardTest`：httpGet 保留 withContext(IO)/execute、不使用await；download入口仍包IO，未修改守卫放宽要求。

### 验证与冻结边界

- `git diff --check` 已执行，无空白错误；未启动 Gradle、formatter或其他编译任务，**以上11项尚未执行，不报通过**；由 Lead 统一跑 JVM/编译与发布 CI。
- 未修改 ChatService.kt / ChatResilienceTest.kt；共享状态里其差异属于 core owner，未覆盖。未改speech/构建/DI/版本；未commit/push；未调用手机。
- 真机图库Provider/Android权限/后台FGS未设备执行：用户明确跳过真机；纯流回滚测试不是 MediaStore 真机集成测试。
- 初审表中其他 FGS 日志/CrashHandler/ImageUtils EXIF/NotificationAvatar/PNG fallback 等建议**本次未修改**，按 Lead 最终有界续接要求留台账，不虚称全范围已修。
