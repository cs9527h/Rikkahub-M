# App 异常与取消逐项审查台账（task-10，新窄范围代码已冻结）

> 最终检查点：2026-11-28。task-10收窄范围的逐项上下文审查、五项指定修复与回归已落地，交Lead统一编译/测试；本人未运行Gradle。以下保留历史检查点作为证据，旧表“待修”不代表当前状态，以本节最终处置为准。不是全仓零缺陷/全部日志已脱敏声明。基线全部既有未提交成果保持，未重置。

## 最终处置与交付

1. **SkillManager必修已处理**：新增生产[BuiltinSkillExtraction](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/skills/BuiltinSkillExtraction.kt>)成功一次门闩。失败不置完成、继续向调用者抛出，listSkills/prune不会把提取故障当作空目录剪掉启用项；正常提取和缓存行为不变。
2. **ModelsDevUpdater必修已处理**：使用异步Call.await、Response.use，并在完整body读取/解码期间保持Call.cancel联动。保留cache→assets、ETag、最低100模型、临时文件rename及可选刷新失败回旧catalog；取消不发布半成品或误报刷新错误。
3. **RequestLoggingInterceptor必修已处理**：one-shot/duplex明确marker且不额外writeTo，不重发请求；用户显式HTTP trace中repeatable请求体仍保留（128KiB上限），敏感/未知header值与query值脱敏；故障记录只有固定operation/异常类型/有界堆栈，不持久化rawbody或异常message，body采集故障不阻止真实请求。
4. **MCP close追加修复已处理**：所有关闭入口委托生产[McpClientCleanup](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpClientCleanup.kt>)，NonCancellable保证父取消后继续清理，5秒协作式超时防止无限等候，关闭失败固定operation诊断，JVM Error不降格普通失败。未承诺强杀不协作的SDK原生阻塞。
5. **corruption保留原件追加修复已处理**：生产[CorruptedSettingsRecovery](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/CorruptedSettingsRecovery.kt>)先独立复制原始bytes并fd.sync；仅成功后返回emptyPreferences。失败保留原件、不替换配置；正常无损坏启动完全不变，成功备份后的原有重置语义保留。
6. **取消及诊断首批已处理**：OCR、MCP资源发现/刷新/探测、userinfo、usage写入、memory进度、RemoteFileStore后台清扫、Chat标题/建议/压缩入口不吞CancellationException。普通生成重复原始堆栈合并为一条脱敏诊断；原有标题CAS、后台压缩归属、30秒工具步骤checkpoint、OOM提示、头像隔离及文件快删保持。
7. **旧范围已移交**：S3/WebDav客户端和data/sync/files/db/model交 [storage-exceptions](</root/workspace/Rikkahub-M/docs/audit/storage-exceptions.md>)；MediaCreationService及其他service/utils交 [service-utils-exceptions](</root/workspace/Rikkahub-M/docs/audit/service-utils-exceptions.md>)。原有已改代码完整交接，不重复写入。这两份台账/测试由各owner和Lead验收。

### 回归与验证（不把未执行说成通过）

- [BuiltinSkillExtractionTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/skills/BuiltinSkillExtractionTest.kt>)：2项，真实临时文件失败→重试/成功门闩及双线程互斥。
- [ModelsDevUpdaterTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/remote/ModelsDevUpdaterTest.kt>)：4项，实际Call.Factory+阻塞Source取消、关闭资源、旧cache保留与有效cache/ETag原子发布。
- [RequestLoggingInterceptorTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/ai/RequestLoggingInterceptorTest.kt>)：6项，实际OkHttp interceptor链计写、trace开关、敏感值、失败不泄漏、采集失败不阻断及有界body。
- [McpOAuthDiscoveryCancellationTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthDiscoveryCancellationTest.kt>)：5项。Lead首轮回归资源/请求计数未报错，4条异常identity断言受协程栈恢复复制影响；已改同类/message+cycle-safe cause身份链，保留取消后不探测、close及fatal断言。未通过关闭debug掩盖问题。
- [McpClientCleanupTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpClientCleanupTest.kt>)：3项，父取消仍等关闭、可挂起关闭超时收尾、fatal直抛。
- [CorruptedSettingsRecoveryTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/datastore/CorruptedSettingsRecoveryTest.kt>)：3项，精确原始bytes、写备份失败不返回默认值、已有抢救副本不覆盖。
- 合计23项新增/调整用例待Lead以最终源码统一运行；本人最终git diff --check成功，exit code 0。未运行Gradle/formatter，未commit/push/版本/发布。

### 保留与未夸大的风险

- 同步UUID/JSON/时间兼容fallback、辅助数据不可用的保守返回、取消先行重抛、事务失败后清理并重抛均逐站点说明保留，不机械加日志。
- HistoryMemoryWorker的同步WorkManager探测失败返回false仍为需实测确认项：可注入Future失败观察generation与worker并发；本轮未声称修复或已有复现。
- 用户显式开启的成功HTTP trace仍可能包含repeatable请求体，这是保留的产品功能，不等同于普通故障诊断；日志外发仍需用户选择，不宣称整个LogPage不存在用户内容。
- Lead批准最后5个遗留诊断站点一并收敛，均已仅换固定operation+formatExceptionForLog：WorkspaceRepository安装失败、WorkspaceReminder跳过可选文件、SettingsJsonMigrator失败、Preferences初始化失败、ChatRequestFactory召回统计失败。去除root/url/路径/rawThrowable，不改用户反馈或控制流；召回统计DAO/事务内部没有另一条App故障报告，因此在本catch补一次有界cause/帧，不增加重复报告。没有借此重新扫描或扩展功能。

---

## 历史逐项证据（下列“待修”由上方最终处置覆盖）

## 方法与判定

- 已用 grep 建立 catch / runCatching 站点清单，再用 read 阅读下面列出的真实函数及异常出口；清单数量不是完成证明。
- 行号为阅读时的位置，后续导入/修复会使其移动；以文件和函数名定位。
- **修取消**：仅 suspend 工作或调用 suspend 的链路采用 common.util.runCatchingCancellable；普通解析 fallback 不机械替换。
- **保留**：解析失败回空值、资源清理后重抛、取消先行重抛、保守删除保护。**待修**不等于已完成。
- 诊断复用 common.util.formatExceptionForLog，operation 用固定标识，不记录异常 message/toString、请求体、prompt、密钥、query、私人路径。失败 Result 通常交上层报告，避免同一次失败重复落日志。

## 已读站点与当前处理

| 文件 / 函数 | 基线站点 | 上下文与处置 | 当前状态 |
|---|---:|---|---|
| [S3Client](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Client.kt>) putObject(ByteArray) | 39 | 网络 PUT Result 包装，原来把取消变失败；换可取消 Result。HTTP 错误不再拼原始响应体到日志。 | 已改，待统一编译 |
| 同文件 putObject(File) | 73 | 上传 channel；同上。保留流式上传，未改4MP/轮询等行为。 | 已改，待统一编译 |
| 同文件 getObject | 106 | 网络 GET；取消重抛；读取输入流加 use 关闭。 | 已改，待统一编译 |
| 同文件 getObjectStream | 133 | 网络流 Result；取消重抛。API 无生产调用点；返回 InputStream 由调用者关闭，dispatcher 交接取消窗口还需单独实测，未称已解决。 | 部分修复 / 交接风险待验证 |
| 同文件 downloadObjectToFile | 159 | execute/use 控制响应和文件流生命周期；取消不能降格失败值。 | 已改，待统一编译 |
| 同文件 deleteObject | 193 | DELETE取消须停止上层备份循环。 | 已改，待统一编译 |
| 同文件 headObject | 220 | HEAD失败仍是Result，取消独立传播，避免exists把取消判不存在。 | 已改，待统一编译 |
| 同文件 listObjects | 255 | LIST分页取消独立传播；移除原始错误体日志。 | 已改，待统一编译 |
| 同文件 parseListObjectsResponse / LastModified | 379 | 同步Instant解析，非法时间为null，不含挂起点；合理兼容fallback。 | 保留 |
| [WebDavClient](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavClient.kt>) put(ByteArray) | 57 | PUT网络Result；取消重抛，HTTP错误体不入日志。 | 已改，待统一编译 |
| 同文件 put(File) | 87 | 流式PUT，保留file.readChannel；取消重抛。 | 已改，待统一编译 |
| 同文件 get | 114 | GET网络Result；readBytes输入流加use，取消重抛。 | 已改，待统一编译 |
| 同文件 getStream | 135 | 返回InputStream，与S3同类无生产调用API，交接窗口不夸称修复。 | 部分修复 / 交接风险待验证 |
| 同文件 downloadToFile | 155 | execute响应作用域+output.use已正确，取消重抛；保持EOF处理。 | 已改，待统一编译 |
| 同文件 delete | 191 | DELETE取消独立传播，保留普通失败Result。 | 已改，待统一编译 |
| 同文件 head | 212 | HEAD取消独立传播，避免exists误判不存在。 | 已改，待统一编译 |
| 同文件 mkcol | 237 | MKCOL取消不能被视作普通失败；405兼容行为保留。 | 已改，待统一编译 |
| 同文件 propfind | 266 | PROPFIND取消独立传播，XML解析失败仍Result。 | 已改，待统一编译 |
| 同文件 ensureCollectionExists | 308 | 挂起探测失败后可能继续MKCOL；取消独立传播，非取消失败仍兼容创建。 | 已改，待统一编译 |
| 同文件 list | 325 | 挂起分页结果包装；取消独立传播。 | 已改，待统一编译 |
| 同文件 parseLastModified | 412 / 416 / 420 | 三种同步日期格式逐次尝试，失败null合理；现有dateString原文日志需收敛为常量。 | fallback保留 / 日志待修 |
| 同文件 excludeCurrentCollection.rawPath | 452 | 同步URI身份比较失败保留原资源列表，不误删备份；无挂起点。 | 保留 |
| [RemoteFileStore](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/remote/RemoteFileStore.kt>) upload | 67 / 98 | 原有onFailure已重抛取消；无需重复改wrapper。 | 保留 |
| 同文件 后台sweep | 89 | 子任务吞取消且原Throwable可能带路径；改可取消Result和固定operation脱敏诊断。 | 已改 |
| 同文件 testConnection | 103 / 108 | 原有取消重抛，普通故障Result归调用者。 | 保留 |
| 同文件 S3ObjectStorage.lastModified | 203 | 同步RFC1123解析null兼容；下游headObject已修取消，不再把取消当不存在。 | 保留 |
| [OcrTransformer](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt>) performOcr | 85 / 126 | 模型suspend调用取消原本变OCR文本；改取消重抛。缓存URI和OCR原文日志去除；普通失败用固定operation脱敏诊断，不把异常正文拼进模型输入。 | 已改 |
| [DocumentAsPromptTransformer](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt>) resolveWorkspacePath | 69 | 同步URI解析失败返回null，兼容文件格式。 | 保留 |
| 同文件 readDocumentContent | 76 | 同步URI解析失败转换为用户错误标记，无suspend。 | 保留 |
| 同文件 readDocumentContent解析块 | 81 / 90 | EPUB可挂起，但已有CancellationException先重抛；错误按附件显示，未新增日志。 | 保留 |
| [McpOAuthDiscoveryClient](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthDiscoveryClient.kt>) discoverProtectedResource | 61 | 取消原本被当候选失败继续探测；改可取消Result。 | 已改 / 回归编写中 |
| 同文件 discoverAuthorizationServer | 74 | 同上，取消不继续下一个well-known。 | 已改 / 回归编写中 |
| 同文件 probeResourceMetadataUrl | 89 | 401探测取消不降格null；普通网络失败仍允许兼容well-known fallback。 | 已改 / 回归编写中 |
| 同文件 executeRaw | 150 | 删除重复suspendCancellableCoroutine实现，委托common Call.await，复用Call.cancel与Response交接关闭钩子。注入executeRequest仅便于测试真实候选流程。 | 已改 |
| 同文件 execute失败分支 | 143 | IOException不再包含URL或响应体截断；成功发现日志不列URL/issuer列表。 | 已改 |
| [McpOAuthCoordinator](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt>) startAuthorization | 55 / 57 | 取消已先重抛；日志仍带配置名/原Throwable，待换脱敏固定operation。 | 语义保留 / 日志待修 |
| 同文件 ensureFreshToken | 99 / 118 | 取消不应返回旧token；换可取消Result。普通刷新失败保留旧config；原日志message待脱敏。 | 取消已改 / 日志待修 |
| 同文件 needsAuthorization | 130 | 取消不能判无需授权；改可取消Result，原失败日志待脱敏。 | 取消已改 / 日志待修 |
| 同文件 authorize资源发现 | 142 | 取消不能继续origin兼容fallback；改可取消Result。正常未实现RFC9728仍fallback。 | 取消已改 / 日志待修 |
| [ProviderAccountManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/account/ProviderAccountManager.kt>) fetchGrokEmail | 227 | 可选userinfo失败允许null，但取消不可吞；改可取消Result。await().use已有响应关闭；原Throwable日志待脱敏。 | 取消已改 / 日志待修 |
| [UsageRecorder](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/UsageRecorder.kt>) record后台insert | 69 | Room取消独立传播，不记录正常scope终止；真实失败固定operation脱敏。 | 已改 |
| [MemoryConsolidator](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/MemoryConsolidator.kt>) consolidate | 142 / 144 | 取消先重抛正确；普通故障清理后重抛，无吞错。现有内存库ID/异常正文日志待脱敏。 | 语义保留 / 日志待修 |
| 同文件 advance | 258 | 外键失败可降格false兼容会话已删，但不能吞取消；改可取消Result。原异常正文日志待脱敏。 | 取消已改 / 日志待修 |
| [MemoryRecall](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/memory/MemoryRecall.kt>) recalledFiles | 90 | 同步工具结果JSON解析，失败无召回；无suspend，不加噪声。 | 保留 |
| [MemoryTools](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt>) memoryWritePath | 231 | 同步工具结果JSON解析，失败null不虚构成功写入。 | 保留 |
| [ChatService](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt>) launchGenerationJob通知身份 | 365 / 367 | 取消已先重抛；非取消读取失败可回退App身份，不归错助手。 | 保留 |
| 同文件 cleanupQueuedAttachments | 507 | 取消已重抛；引用不明保留附件正确。原Throwable日志待脱敏。 | 语义保留 / 日志待修 |
| 同文件 queued.reply.completeWith | 611 | 同步收集语音结果，Result交调用者，不含挂起；保留。 | 保留 |
| 同文件 sendQueuedMessage/sendQueuedNotices/regenerate/approval | 623 / 659 / 662 / 709 / 759 | reportFailure已重抛取消；NotFound丢旧通知正确。send异常前printStackTrace和报告上下文待收敛。 | 语义保留 / 诊断待修 |
| 同文件 runTurn | 918 / 921 / 930 / 939 | 取消先重抛，MCP配置错误独立提示，OOM用户可见处理保留；普通异常三份原文/堆栈日志重复且含敏感内容，待合并脱敏。 | 语义保留 / 诊断待修 |
| 同文件 saveTurnCheckpoint | 960 / 962 | 取消重抛正确，普通中途保存失败继续最终重试正确；保留30秒工具完成步骤限频。 | 保留 / 日志待修 |
| 同文件 generateTitle | 1025 | 挂起标题请求取消不应进onFailure；已改可取消Result，原标题CAS保留；printStackTrace待替换。 | 取消已改 / 诊断待修 |
| 同文件 generateSuggestion | 1051 | 同上；wrapper已换但第二个return标签在中断时尚未改完，需解冻后修正。 | 半成品，优先编译检查 |
| 同文件 compressConversation | 1086 | 启动后台压缩的挂起加载取消仍是旧runCatching；解冻后修，保留任务归会话、弹窗关闭不取消的新行为。 | 待修 |
| 同文件 launchCompressionJob / compressMidTurn | 1167 / 1208 | catch已有取消重抛；deliver恰好一次+finally清状态保留。 | 保留 / 日志待修 |
| 同文件 translateMessage | 1563 | 取消已先重抛，失败清翻译字段并UI提示；缺脱敏操作诊断，后续评估避免重复。 | 已读 / 待决定 |

## 第二批已读（22:20）

| 文件 / 函数 | 站点 | 逐项决定 |
|---|---:|---|
| [ExportSerializer](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt>) ModeInjection.import / tryImportNative | 103 / 121 | 同步ContentResolver读取交Result、原生JSON兼容回null，均非suspend；保留，错误由导入UI报告。 |
| 同文件 Lorebook.import / tryImportNative / tryImportSillyTavern | 149 / 160 / 178 | 依次尝试原生与酒馆schema是有意格式探测，失败不打印用户内容；保留。 |
| [SkillValidator](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/skills/SkillValidator.kt>) validate | 13 | 同步校验通过Result返回具体SkillValidationException，未吞挂起取消；保留。 |
| [SkillFrontmatterParser](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/skills/SkillFrontmatterParser.kt>) parse | 20 | 同步SafeConstructor/有限alias/depth/codepoint解析；缺frontmatter回Empty符合调用者约定，不记录原文；保留。 |
| [SkillManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/skills/SkillManager.kt>) ensureBuiltinSkillsExtracted | 43 | 同步可选内置提取失败日志后继续；标志目前失败也置true，后续清理配置可能误认为内置技能消失，需修为失败不确认提取成功（待实现/回归），不能声称安全。 |
| 同文件 saveSkillFile | 169 | 临时文件finally删除、失败false交UI；同步IO非取消挂起。保留原子写语义，路径/原Throwable日志待脱敏。 |
| 同文件 saveSkillFileBytesAtomically | 212 | 失败尝试恢复backup、finally清staging，非挂起；保留目录回滚。日志待脱敏，不改既有原子流程。 |
| 同文件 parseSkillDir | 249 | 解析失败Result区分缺SKILL/缺名称/描述/IO，列表有invalidDirs入口，不加重复日志；保留。 |
| [BuiltinSkills](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/skills/BuiltinSkills.kt>) copyTree | 63 | 仅FileNotFoundException区分AssetManager空目录，输入/输出use完整，非宽泛吞错；保留。 |
| [ProxyConfig](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/remote/ProxyConfig.kt>) toProxyOrNull / SettingsProxySelector.select | 22 / 83 | 同步URI校验与系统代理不可用回直连；不打印代理凭据，无suspend；保留。 |
| [NodeFavoriteAdapter](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/repository/NodeFavoriteAdapter.kt>) decodeRef / decodeMeta | 49 / 57 | 收藏历史JSON可缺旧字段，失败null由上层跳过坏引用，非suspend；保留。 |
| [ProviderAccountManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/account/ProviderAccountManager.kt>) startLogin / refreshIfNeeded | 85,87 / 153,155 | 两处取消先重抛正确；登录最终失败进状态，刷新包cause重抛。登录诊断已改固定operation，无账户名/异常原文；refresh异常交最终调用者。 |
| [MemoryConsolidationScheduler](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/MemoryConsolidationScheduler.kt>) runOnce | 265 / 267 | 取消先重抛、普通失败进入用户可见run.error；底层consolidator已有脱敏诊断，不重复日志；保留。 |
| [WorkspaceReminderTransformer](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt>) readRootfsText | 119 / 121 | 可选AGENTS/os-release不可读不阻塞发送；取消重抛已正确。路径/原Throwable日志待脱敏。 |
| 同文件 RemoteEnvironmentCache.get | 160 / 162 | 取消不写缓存，普通失败有60秒TTL；保留，无重复日志。 |
| [ModelsDevUpdater](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/remote/ModelsDevUpdater.kt>) start / loadLocal缓存 / loadLocal bundled | 39 / 47 / 56 | 都是同步调用（refresh内部execute），use完整。缓存坏回assets合理；阻塞refresh未联动取消，待改await或明确未解；失败日志待脱敏。 |
| [RequestLoggingInterceptor](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/RequestLoggingInterceptor.kt>) intercept | 30 | catch记录后原样重抛；不吞错。但记录request.body/raw headers/query与error.message，且额外writeTo会二次消费one-shot body，待针对性修，不是可直接保留的解析fallback。 |
| [ChatRequestFactory](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt>) recordRecall | 327 / 329 | 取消先重抛，辅助召回统计失败不挡生成合理；现日志只有常量无cause，待补脱敏类型/有界堆栈。 |

## 2026-11-28 08:40：收窄范围后的只读补审

新分工：core-fixes仅其余data(ai/remote/repository/skills/export/datastore)+ChatService；data/sync/files/db/model转file-catalog，其他service+utils转speech-fixes。旧表是交接证据，不代表重复接管。当前生产/测试冻结，以下先记录已读判断。

| 文件 / 函数 | 站点 | 逐项决定 |
|---|---:|---|
| [SettingsJsonMigrator](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/migration/SettingsJsonMigrator.kt>) migrate / quickMessages existingArray | 25 / 49 | 全同步旧JSON迁移，失败保留原文而非清空；现外层Throwable日志待脱敏。内层既有数组类型探测合理保留。 |
| [PreferenceStoreV2Migration](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV2Migration.kt>) migrateAssistantsJson | 67 | 同步旧type修复，失败原样返回，保留原JSON，不涉及取消。 |
| [PreferenceStoreV3Migration](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV3Migration.kt>) migrateAssistantsQuickMessages | 63 | 同步提取旧内嵌快捷消息；无挂起，失败回原文/空新增集，避免半迁移，保留。 |
| [PreferenceStoreV4Migration](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV4Migration.kt>) migrateAssistantsConversationInjections | 39 | 同步schema适配，失败原文保留，不机械改可取消包装。 |
| [PreferenceStoreV5Migration](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV5Migration.kt>) migrateProvidersQuotaQuery | 40 | 同步旧quota配置变换，失败原文保留，不打印key/template；保留。 |
| [PreferencesStore](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt>) corruptionHandler备份 | 69 | 同步保留损坏设置副本；失败仍emptyPreferences会丢掉最后原件，需失败关闭，不是合理吞错。诊断待脱敏，后续最小修。 |
| 同文件 初始化读盘 | 233 / 235 | 取消先重抛；Throwable分支halt后重抛是阻止dummy覆盖用户配置，不当成功fallback；保留fail-closed，日志待脱敏。 |
| [WorkspaceRepository](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt>) installRootfs | 222 / 227 / 232 | 取消/线程中断先NonCancellable恢复shell状态后重抛；失败BROKEN后重抛。保留原行为，当前日志有root/url/原Throwable待脱敏。 |
| 同文件 decryptSecret | 544 | 无挂起解密异常包IOException并保留cause，明确提示重录本机凭据，不回退空密钥；保留。 |
| 同文件 readFromBackend | 560 | runInterruptible联动取消；异常分支ensureActive将JSch包裹的中断按取消处理再throw，保留。 |
| [HistoryMemoryManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/memory/HistoryMemoryManager.kt>) 恢复调度 / start / continue | 210,212 / 230,232 / 328,330 | 三处取消先重抛；真实WorkManager失败落任务行，start还原样重抛给UI。状态诊断已有memoryFailure脱敏，不重复日志，保留。 |
| 同文件 run.sources解析 | 385 / 387 | 同步坏sources明确标失败，不留running僵尸；取消分支保留。 |
| 同文件 readBatch / skip source | 427 / 429 / 431 / 444 | 取消与ExtractionFailure先重抛；坏历史只跳当前source，checkpoint保留其他已保存候选。通用异常转换不打印历史，保留。 |
| 同文件 parseHistoryCandidates | 515 / 517 | 同步模型返回格式坏转具体ExtractionFailure；不记录响应体；保留。 |
| 同文件 run终结 | 537 / 539 / 541 / 543 | 明确2分钟模型超时落可重试失败；父取消独立重抛；提取失败与其余失败分阶段落库，保留。 |
| 同文件 undoLastBulk.payload | 1068 | 同步坏UndoPayload不执行任何恢复（返回null），不会拿残缺快照写回；保留。 |
| [HistoryMemoryWorker](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/memory/HistoryMemoryWorker.kt>) isActive | 49 | 同步WorkManager Future.get异常回false为现有探测契约；需实测确认WorkManager DB临时故障是否可能误释放任务锁，未列为已修。验证：注入Future失败并观察generation/worker并发。 |
| [McpSessionRegistry](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt>) callTool | 157 / 159 | 取消先重抛；普通失败可触发OAuth检查后原样重抛。Coordinator探测已修取消，保留。 |
| 同文件 connectSession | 254 / 257 | 取消/失败均closeClient后重抛/状态化；日志待脱敏，closeClient取消语义见下。 |
| 同文件 syncSession | 293 / 295 | 取消先重抛，普通失败进入Error/NeedsAuthorization，不覆盖旧配置；保留。 |
| 同文件 reconnectAfterDelay | 396 | 取消重抛且finally用NonCancellable释放锁/重连Job引用，正确保留。 |
| 同文件 closeClient | 422 | SDK close可挂起，原runCatching在已取消上下文下可能没有完成资源关闭；需有界NonCancellable清理，普通关闭失败脱敏后保守释放引用，解冻后修。 |

## 新范围剩余站点逐项补齐（08:55，只读冻结）

| 文件 / 函数 | 站点 | 决定 |
|---|---:|---|
| [McpManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt>) callTool | 120 / 122 | 取消重抛，仅McpClientUnavailableException变为工具失败文本；其余异常直抛，上层chat诊断，保留。 |
| [Jwt](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/account/Jwt.kt>) decodeJwtPayload | 16 | 同步Base64/JSON可选声明解析，坏token回null而非当验证成功；无suspend，保留，不记录token。 |
| [ConversationTools](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt>) READ conversation_id | 162 | 仅同步Uuid.parse包Result；真正仓库suspend读取在wrapper外，取消可传播，保留。 |
| [WorkspaceTools](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt>) replaceText | 206 | 仅IllegalArgumentException给模型明确路径/替换失败，非日志/非suspend包装；保留。 |
| 同文件 pathOutsideWritableRoots | 457 | 同步解析失败默认true（加强审批），没有丢弃suspend取消，保留fail-closed。 |
| [PhoneUseTool](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/PhoneUseTool.kt>) phoneUseActionOf | 76 | 流式未完成JSON回null仅影响动作提示，不代表执行成功，无suspend，保留。 |
| [JavascriptTool](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/JavascriptTool.kt>) JS stringify / evaluateJavascript | 94 / 132 / 134 | 94为JS源码内局部stringify fallback，不是Kotlin异常站点；132仅QuickJsInterrupted转限时错误，134仅QuickJsException转有界工具文本。父协程取消仍由withContext边界传播，native关闭由quickJs作用域负责；保留。 |
| [CalendarTool](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/CalendarTool.kt>) 查询/新增参数解析 | 124 / 311 | 两处try只包同步日期解析，不包日历访问/挂起，非法时间转换工具INVALID_TIME；保留。 |
| 同文件 parseCalendarTime | 433 / 434 / 435 / 436 | 四种同步时间格式逐次探测，最后统一非法参数错误，无日志；逐项保留。 |
| [ScreenTimeTool](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ScreenTimeTool.kt>) 参数解析 | 112 | 同步时间参数转INVALID_TIME，不捕挂起API，保留。 |
| 同文件 resolveLauncherPackages / resolveAppName | 251 / 259 | 两个同步PackageManager辅助显示查询，失败空集/包名，不改变执行成功状态；保留。 |
| 同文件 parseUsageTime | 271 / 272 / 273 / 274 | 四个同步格式fallback，非suspend，保留。 |
| [MediaCreationRepository](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/repository/MediaCreationRepository.kt>) decodeOrDefault | 285 | 同步旧记录可选字段JSON缺失/坏值回默认，仅解码展示参数；没有把挂起DB错误当默认，保留。 |
| [ConversationRepository](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt>) countConversationsByAssistant / loadMessageNodes收藏ID | 312 / 506 | 两个wrapper均仅同步Uuid.parse；前者跳坏归属统计ID，后者忽略坏收藏引用。整会话消息分页/JSON加载失败仍直抛，绝不缺页保存；保留。 |
| [WorkspaceRepository](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt>) resolveAbsoluteFile | 329 | 同步后端localAbsoluteFile可无本地落点，失败null符合能力探测；suspend DB获取在wrapper外，保留。 |
| [ChatService](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt>) cleanup | 286 | 同步清draft provider+session任务引用，返回Result交调用者，非suspend包装；保留。 |

### 三项必修的最小方案与真实回归计划（冻结期间拟定，尚未落地）

1. **SkillManager**：成功一次门闩只在extract返回后置位；提取异常继续向listSkills/prune调用者传播，禁止失败被当作空内置列表剪除启用项。测试实际生产门闩：第一次真实临时文件提取失败、第二次成功、第三次不重提取；并发调用只提取一次。
2. **ModelsDevUpdater**：保留本地cache→assets、ETag、最低100条校验、临时文件rename；HTTP改await并在读取body期间仍有Call.cancel联动，响应use关闭。测试注入Call.Factory：取消挂起请求/取消已交付但阻塞读取的body均取消Call并关闭资源，普通刷新失败不覆盖旧cache/catalog。测试不得只验证helper或源码字符串。
3. **RequestLoggingInterceptor**：保留用户显式开启的LogPage请求trace功能；isOneShot/isDuplex返回明确未采集marker，禁止额外writeTo，也不重发真实请求；普通可重复body仍可显示。敏感header脱敏，error字段只用固定operation诊断，故障记录不附rawbody/rawmessage/query。测试实际OkHttp interceptor链+计数RequestBody：one-shot/duplex真实传输只写一次，repeatable保持trace，关闭日志不额外写；失败仍原异常抛出且日志无敏感值。
4. **MCP回归断言修正**：withContext允许stacktrace recovery复制CE/Error；测试检查异常同类同message且identity cause链可达原异常（带cycle guard），保持停止后不再请求/关闭资源断言，不关闭debug或修改生产吞异常。

### 当前验收边界

- 新分配给core的catch/runCatching站点均已read对应函数上下文并分类（以各逐项行而非计数证明）；还不是“所有修复完成”。
- 必修未落地：SkillManager失败提取不得置成功/剪掉已启用项；ModelsDevUpdater阻塞execute改可取消await；RequestLoggingInterceptor跳过one-shot/duplex二次writeTo、保留用户开启的重复可读body日志功能、敏感headers脱敏、故障记录payload-free。
- 追加安全修复待落地：Preferences损坏副本失败应阻止重置；MCP关闭客户端需有界NonCancellable；已读若干非敏感操作缺cause的日志待统一固定operation脱敏。
- 统一回归已跑到App965项：新增MCP测试四项assertSame因协程stacktrace recovery复制异常而失败；取消后的请求计数/关闭断言未报错。拟测试检查同类同message且带cycle guard的cause链可追原异常，不改生产取消语义或关闭debug。
- 存储范围完整结果由 [storage-exceptions](</root/workspace/Rikkahub-M/docs/audit/storage-exceptions.md>) 接管；其他service/utils完整结果由 [service-utils-exceptions](</root/workspace/Rikkahub-M/docs/audit/service-utils-exceptions.md>) 接管。它们都在进行中，不能据此宣称发布验收通过。

## 旧范围初始待审清单（历史快照，后续以新分工及逐项表为准）

以下仍须 read 对应上下文再给每个站点决定；不能仅凭grep/文件名作结论：

- data/export、data/skills、data/repository/WorkspaceRepository、data/repository/NodeFavoriteAdapter、data/repository/MediaCreationRepository、data/repository/ConversationRepository 的解析站点；data/datastore及migration全部。
- data/sync：PartialMemoryRestore、WebDavSync、S3Sync、BackupAssetSnapshots、PendingRestore、BackupManager、DatabaseBackup、BackupFileUris、importer时间兼容。
- data/files：SettingsFileReferences、MessageFileReferences、ManagedFileCatalog、AvatarStorage（保留既有原子隔离回收）、AvatarReferences、FilesManager、MediaCreationFiles、FileUtils。
- data/db全部迁移/实体兼容；data/model Assistant/MemoryFile。
- data/ai：RequestLoggingInterceptor、ChatRequestFactory、MemoryConsolidationScheduler、WorkspaceReminderTransformer、HistoryMemoryWorker/Manager、ConversationTools、McpManager/SessionRegistry、Jwt；account其余catch；local工具Calendar/ScreenTime/Javascript/PhoneUse、WorkspaceTools。
- service：WebServerService、ConversationSession、所有ForegroundService、MediaCreationService、BackgroundShellManager；ChatService.cleanup尚需完整上下文决定。
- utils：PngTextChunkReader、NotificationAvatar、UpdateInstaller/Checker/DownloadEngine/DownloadService、ContextUtil、DatabaseUtil、ImageUtils。

## 验证状态

- 本轮生产编译/全量测试交由Lead；本人未启动Gradle/formatter/提交/发布。
- 已新增 [McpOAuthDiscoveryCancellationTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthDiscoveryCancellationTest.kt>) 5项，执行生产探测/候选流程（取消不继续、普通失败继续、响应关闭、fatal不恢复）；尚未运行。公共Result/脱敏helper的单元测试由network-fixes负责，不能用它替代App生产调用流程回归。
- 22:15小步更新：ChatService剩余标签已修正；标题/建议/启动后台压缩均采用可取消Result；runTurn原三份日志收敛为一份脱敏诊断，保留OOM用户提示和已有30秒checkpoint；普通任务reportFailure统一一次固定operation诊断，取消不日志。MCP coordinator、userinfo、MemoryConsolidator已使用固定operation脱敏日志。上表“日志待修/半成品”是22:10的历史标记，以此更新为准，后续逐项回填。
- 公共runCatchingCancellable已由模块代理收敛为只捕获Exception：CancellationException及JVM Error原样抛出，避免getOrDefault/结果回退误恢复OOM。
- 当前台账明确标注尚未审完与半成品，不宣称全库异常治理完成。
