# 库模块异常审查台账（task-11，代码交付完成）

## 工作边界与当前检查点

- 范围：ai/common/document/mediagen/oauth/search/speech/terminal/ui/web/workspace 的 main 宽泛 catch（Exception/Throwable/RuntimeException）和关键 suspend runCatching。
- 仅新增异常相关变更；保留原有4MP编码、APIMart、5秒轮询与其余未提交工作。未运行Gradle/formatter，未改版本、构建配置、app或提交。
- 本文是逐项阅读记录，不将“检索到”算作“修好”。最终检查点：Lead已完成库编译/回归；本agent已读取新增5份JUnit XML确认16项全部通过。最后一批ASR日志/UI边界小修及新增UI 5项已保存并再次冻结，待Lead最终门禁，不提前声称其执行通过。
- 公共API：`me.rerere.common.util.formatExceptionForLog(operation:String,error:Throwable):String`。操作名必须常量，类型/cause/代码帧保留，刻意不读message/toString和文件名（消息可能含HTTP体/prompt/令牌/私人路径）；上限8层cause、每层12帧、8192字符，identity循环检测。
- 公共API：`runCatchingCancellable(block:()->T):Result<T>`，inline；CancellationException及JVM Error原样重抛，只有可恢复Exception成为Result.failure；新增测试覆盖OOM/AssertionError/LinkageError不走fallback。仅在业务挂起边界使用，不批量改变纯解析fallback。

## 已阅读并已处理

| 文件/边界 | 处理与理由 |
|---|---|
| common/util/ExceptionDiagnostics.kt（新增） | 新增payload-free有界格式化和取消/致命错误保持helper。两套8项JVM测试已读取XML确认通过。 |
| ai/provider/providers/openai/ChatCompletionsAPI.kt onFailure | 非JSON/空body原可close(null)误报成功；统一非空streamFailure，移除原始响应体/异常message日志。 |
| ai/provider/providers/openai/ResponseAPI.kt onFailure | 同上；429零额度提示原throw后被catch吞掉，现返回业务异常传递到Flow。保留私密请求开关、请求与decoder协议。 |
| ai/provider/providers/claude/ClaudeProvider.kt onFailure | 同上；纯metadata重编号2处runCatching保留（跨协议/缺元数据正常）。 |
| ai/provider/providers/google/GoogleProvider.kt onFailure/onEvent | 使用统一非空错误，诊断不含SSE data；保留decoder与生成行为。 |
| ai/provider/providers/google/InteractionsAPI.kt onFailure/onEvent | 同上；原始t优先保留，解码失败以suppressed附加，错误不覆盖原网络原因。 |
| ai/util/StreamFailure.kt（新增） | peek错误体最多16KiB仅供业务错误解析，不进入日志；默认仅接受JsonObject结构（Lead依据真实lenient解析失败修复），非JSON错误页保留HTTP异常；保留RealEventSource的Response所有权。取消异常原样保留。 |
| ai/provider/providers/grok/GrokProvider.kt listModels | 2个并行目录请求Result边界重抛取消；可用一个目录即成功的fallback保留；纯Instant解析保留。 |
| ai/provider/providers/AccountAuth.kt authenticate | 刷新凭据runBlocking边界重抛取消；常规鉴权失败仍返回null，由原401传给调用者。 |
| ai/util/ImageEncodeCache.kt put | 保留缓存非关键失败fallback，仅替换为脱敏有界诊断，排除序列化/磁盘路径消息。 |
| mediagen providers：aliyun/AliyunMediaGenerationProvider.kt、volcengine/VolcengineMediaGenerationProvider.kt、openrouter/OpenRouterMediaGenerationProvider.kt | create和video query挂起Result边界改取消保持，image query既有unsupported契约不变。 |
| mediagen providers：openai/OpenAIMediaGenerationProvider.kt、minimax/MiniMaxMediaGenerationProvider.kt、apimart/ApiMartMediaGenerationProvider.kt | create/query边界改取消保持，不改请求构建、协议、任务解析与APIMart新增功能。 |
| search/BingSearchService.kt、BochaSearchService.kt、BraveSearchService.kt、DeepSeekSearchService.kt、DoubaoSearchService.kt、ExaSearchService.kt、FirecrawlSearchService.kt、GrokSearchService.kt、JinaSearchService.kt、LinkUpService.kt、MetasoSearchService.kt、OllamaSearchService.kt、PerplexitySearchService.kt、RikkaHubSearchService.kt、SearXNGService.kt、SerperSearchService.kt、TavilySearchService.kt、TinyfishSearchService.kt、ZhipuSearchService.kt | 各公开search/scrape挂起Result边界重抛取消；所有原Response.use保持。纯参数/错误JSON解析fallback保留。 |
| search Bocha/Exa/Metaso/SearXNG/Zhipu decode.onFailure | 日志改为脱敏有界格式，移除重新抛带body的新异常，getOrThrow保留原解析cause和堆栈。 |

## 已阅读并保留（有明确fallback或向上传播，不添噪音）

| 文件/项 | 保留理由 |
|---|---|
| ai/agent/Retry.kt catch | 已ensureActive并显式排除取消；网络错误有限重试，异常向上传递；不在库中重复报错。 |
| ai/agent/AgentLoop.kt stream catch、runTool catch/JSON解析 | 取消分支已先重抛；stream处理失败包装防网络重试；工具错误返回业务结果，不添加重复日志。 |
| ai/util/FileEncoder.kt 全部7处 | 图片/视频/音频/MIME为同步Result契约；缓存失效后读原图、EXIF缺失/变换失败保留原图合理；保留前轮4MP工作。 |
| ai/ui/UIMessagePart.kt inputAsJson、MessageMetadata.kt metadataAs、StreamChunkHandler.kt parseServerToolJson | 增量/跨协议/非JSON参数为正常可选字段fallback，非挂起，不逐片日志。 |
| ai/provider/providers/openai/ChatCompletionsStreamDecoder.kt JSON heartbeat解析 | 兼容心跳/非JSON事件的既有fallback；不改decoder。 |
| common/cache/LruCache.kt（8处）、SingleFileCacheStore.kt（2处） | 非持久化真值的缓存IO、legacy格式升级、过期/淘汰删除失败允许降级；同步接口，无取消吞并路径；避免每键噪音。 |
| common/js/QuickJSFetch.kt contentType catch | 可选header类型兼容；真实Call有parentJob取消与Response.use；未改。 |
| common/http/JsonExpression.kt（2处） | 纯表达式/数值验证返回ParseResult或null，是可预期输入，不逐字符日志。 |
| common/http/sse/RealEventSource.kt catch | Response.use管理整个读流；向listener.onFailure传递原异常/取消IOException；不重复日志。 |
| search/CustomJsSearchService.kt（2处） | 已.onFailure重抛CancellationException且30s超时；真实JS/HTTP取消有既有测试，保留。 |
| search/ExaSearchService.kt optional参数3处；DeepSeekSearchService.kt errorMessage | 纯可选参数/错误JSON解析，不挂起；失败返回既有fallback。 |
| mediagen/providers/ProviderHttp.kt parse JSON | Response.use已关闭；错误HTTP有结构化异常；成功非JSON后续任务解析明确失败；不在底层重复日志。 |
| document/EpubParser.kt catch | 已显式重抛java取消异常，其他失败保留用户可见解析错误；Zip/entry.use与额度保持。 |
| document/DocxParser.kt 2处、PptxParser.kt 3处、PdfParser.kt 单页catch | 同步解析容错；主解析返回有上下文错误，notes/单页降级不终止其他页面。 |
| document/pdf/TrueTypeCmap.kt、CffFont.kt 各1；PdfFont.kt 3；PdfCMap.kt 2 | 坏字体/缺映射/不支持charset合理fallback，非挂起；逐字形日志会洪泛。 |
| document/pdf/PdfFile.kt 7处 | xref/对象流/目录损坏重建与扫描恢复；失败项跳过保证其余页可读，无挂起；保持既有PDF兼容。 |
| document/pdf/PdfSecurity.kt decrypt | 部分已明文对象/解密失败采用原data兼容；不是网络重试/挂起。 |
| document/pdf/PdfTextExtractor.kt 3处 | 注释/词法/单操作符损坏局部容错，不整页终止；不输出文档内容日志。 |
| workspace/ssh/SshConnectionPool.kt use/openShell（3处） | 已识别isInterruption、释放引用/重试只一次；异常最终向调用者传播，无重复日志。 |
| workspace/ssh/SshConnection.kt sftp/open catch（2处） | 中断关闭channel、代理建连失败关闭跳板连接后重抛；资源生命周期正确。 |
| workspace/ssh/SshConnection.kt output.close、kill、hostkey2处runCatching | EOF/终止清理与未知/坏key校验降级，非挂起且认证默认拒绝，不输出key。 |
| workspace/RootfsInstaller.kt createHardLink | 仅IOException/UnsupportedOperation/SecurityException降级copy，其余异常重抛；文件模式保持。 |
| workspace/FileTreeDelete.kt | best-effort无跟链删除，false由上层处理；不记录用户路径。 |
| workspace/ProotTermination.kt 2处 | PID尚未写出、/proc进程退出都是正常竞态；短等待有期限；不加噪音。 |
| oauth/OAuthLoopbackCallbackServer.kt 3处 | 注册/启动失败释放待处理会话和server后重抛；清理失败不覆盖原错误。 |
| oauth/OAuthHttpClient.kt device.error解析 | 错误JSON字段可选，未知错误仍以DeviceAuthorizationException上抛；保留Response.use和取消交接修复。 |
| speech/StepAsrRequests.kt consume catch | Response.use、Call.cancel、dispose和finally移除活跃请求完整；异常交给continuation，不重复日志。 |
| speech/SystemTTSProvider.kt onDone catch | 非挂起回调；异常交给continuation，finally关闭TTS。 |
| speech/TtsController.kt awaitOrCreate catch | 移除失败/取消缓存再重抛，保留。其余播放/合成/重试catch已识别取消，日志已脱敏。 |
| ui/webview/WebViewLocalAssets.kt、WebViewContentCache.kt | 资源不存在/缓存被清理正常null接口，纯IO.use；不引入common依赖，不逐次日志。 |
| terminal/TerminalSession.java 2处 | transport启动失败通过主线程业务消息报告；读线程关闭后的异常是预期清理。 |
| web/src/main | 本轮检索无宽泛catch或runCatching命中。 |

## 第二阶段已读并处理（源码暂稳，定向测试由Lead执行）

| 文件/边界 | 处理与理由 |
|---|---|
| speech/asr/providers/StepASRController.kt、MiMoASRController.kt、DeepSeekASRController.kt stop/record/triggerFlush | 明确重抛取消；flush用取消保持Result；MiMo/DeepSeek复用StepAsrRequests，dispose先终止Call并在消费结束前保持取消链；真实错误仍更新ASRState。 |
| speech/asr/providers/DashScopeASRController.kt、OpenAIRealtimeASRController.kt、VolcengineASRController.kt record/decode | 录音catch重抛取消；坏JSON/解码日志用安全operation+有界堆栈；录音资源stop/release维持best-effort，避免取消妨碍清理。 |
| speech/tts/provider/providers/MiniMaxTTSProvider.kt | parse catch仅覆盖JSON/hex转AudioChunk，emit移出catch，消费者失败/取消不再误当坏音频吞掉；保留单个坏事件fallback。 |
| speech/tts/provider/providers/QwenTTSProvider.kt | 移除requestBody日志；解析失败fallback日志无data；响应错误仅状态码，不整段读错误体。 |
| speech/tts/provider/providers/StepTTSProvider.kt、DeepSeekTTSProvider.kt、QwenTTSProvider.kt | 新CancellableSpeechCall.useCancellable保持Call取消直到整个body消费完成；try/finally释放取消observer，Response.use关闭；Step错误/读失败原生命周期缺口修复。 |
| speech/tts/controller/TtsController.kt | 合成、播放、重试维持有限重试和取消策略，Throwable日志改为payload-free诊断；await缓存清理不重复报错。 |
| ui/crop/ImageCropImage.kt、ui/crop/ImageCropDialog.kt、ui/sketch/SketchImage.kt | 普通加载/裁剪错误维持提示/null，取消重抛，图片URI不进日志；裁剪cropping标志finally复原。无common依赖，日志仅稳定operation/异常类。 |
| ui/components/Input.kt | parse仅catch NumberFormatException（溢出/输入中间态）；onValueChange移出catch，调用者故障不再吞掉；移除逐键printStackTrace。 |
| workspace/ssh/SshConnectionPool.kt scheduled closeIdle | 清理失败保留调度存活，同时java.util.logging输出固定operation和类型，不包含SSH主机、密钥、路径。未加common依赖。 |
| terminal/TerminalEmulator.java OSC52 | 原clipboard参数移出日志，只记固定操作与异常类型；不改变Termux字节流协议/回调。未加common依赖。 |
| oauth/OAuthCallbackForegroundService.kt | 普通启动失败仍返回false/通知pending状态，日志无Throwable原文，只记稳定operation和类型；未加common依赖。 |

## 最后一批小修（已保存、再次冻结，等待最终门禁）

- speech ASR WebSocket.onFailure：DashScope去response.message并先检查连接身份，OpenAIRealtime/Volcengine的Throwable日志均用已有payload-free格式化helper。
- Step HTTP重试保留attempt数字，不再拼e.message；DashScope两处服务器message只进入业务state、不进入logcat，日志改稳定operation。
- ui图片fallback：ImageCropImage两个边界、SketchImage一个边界均显式放行fatal Error及CancellationException；不增加common依赖。
- ImageCropDialog消费者catch：提取18行纯`ui.util.performUiOperation`小边界（不含Android依赖），try/catch只包crop，onConfirm处于catch外，调用方失败不再误报裁剪失败；外finally仍复原cropping。新增5项真实JVM回归覆盖实际使用的边界。
- speech其余纯JSON/release best-effort与业务返回已分类；补读Step非JSON delta fallback与各ASR.releaseRecorder，保持stop/release互不阻断的清理契约。
- 范围内原始检索各命中已逐项分类到上述文件行；保留同步解析/缓存/清理fallback不等于遗漏。Bing现有Jsoup同步请求仍由5秒超时约束，不声称可立即中断同步库；未做额外架构重构。

## 回归与验证状态

- 新增ExceptionDiagnosticsTest 4项：稳定operation/type/代码行、cause cycle/深链、超长消息/stack预算、令牌/URL query/个人路径/提示原文不出现。
- 新增CancellableResultTest 4项：普通Result、显式取消、真正挂起后取消及finally清理、三类fatal Error直抛。
- 新增StreamFailureTest 5项：空/非JSON HTTP失败不成功、原网络错误保留、429零额度不被吞、16KiB peek预算/Response归属、取消原样传播。
- 新增CancellableSpeechCallTest 2项：真实OkHttp Call在消费期取消并关闭Response、不继续success；消费者异常仍关闭响应且保留cause。
- 新增MediaCancellationContractTest 1项：真实APIMart create请求取消联动真实Call、不返回Result.failure。
- 已实际读取JUnit XML（UTC 2026-11-28 00:32–00:33）：common ExceptionDiagnostics 4/4、CancellableResult 4/4，ai StreamFailure 5/5，speech CancellableSpeechCall 2/2，mediagen MediaCancellationContract 1/1；合计16项、0失败/错误/跳过。执行归Lead，本agent未启动Gradle。
- 新增RecoverableUiOperationTest 5项：consumer异常不走失败提示、普通crop异常、取消、三类fatal、成功一次回调。最后小修后尚未执行，Lead已收到:ui:testDebugUnitTest门禁请求；不把此前库绿灯当作新增UI测试通过。
- mediagen XML stderr含OkHttp尝试AndroidLog后的JVM fallback提示，但该suite 1/1通过；不将它误算测试失败，也不改测试去掩盖真实请求。
- 本agent最后git diff --check退出码0；无Gradle/formatter/构建配置/版本/commit/push改动。纯parse/cache/release保留项并非“清零catch”，避免正常输入或清理阶段日志洪泛。

## 交付结论

已完成约定11个库模块的异常边界审查与上述修复；document/web以逐项保留结论交付，无不必要源码改动。所有新增诊断避开HTTP体、用户prompt、令牌、URL query和私人路径；没有common依赖的oauth/terminal/ui/workspace只使用本地稳定operation/异常类型日志，未新增依赖。上一轮4MP/APIMart/5秒轮询及共享目录其他改动保持。最终新增UI与最后ASR日志小修的执行门禁归Lead，发布与真机决策不由本task代替。
