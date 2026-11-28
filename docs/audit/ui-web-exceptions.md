# App UI / Web / Mate 异常与取消审查台账

## 范围、证据与边界

- 任务：task-13。先读仓库 AGENTS 和当轮技能目录；本项为普通 Kotlin/Android，不匹配 DSH 插件技能。
- 审查对象：App UI（排除 setting/mediacreation 与既有 Avatar/ChatScrollAnchor 业务逻辑）、App web/mate、RikkaHubApp；Lead 补充批准 hooks、search、quickstart、debug 和 ConversationExport。
- 方法：grep 定位后用 read 读取每项所在函数/调用上下文，区分协程控制流、恢复边界、可忽略展示解析、资源清理；下表列出实际读取处置，不以扫描命中数冒充整个文件或仓库穷尽审查。位置为首次读取时行号，修改后可能移动；同时给出稳定符号。
- 不动 data/service/utils、库模块、setting/mediacreation、构建/版本；保留共享工作区上一轮修改。未运行 Gradle、formatter、commit/push，也未操作手机。
- 公共诊断复用 `me.rerere.common.util.formatExceptionForLog`：稳定操作名、异常类型、有界 cause/代码位置，不输出原始 message/toString、请求体、prompt、key、URL query、用户路径。公共 `runCatchingCancellable` 只捕获 Exception，CancellationException 和 JVM Error 直抛。

## 已修复的行为边界

| 文件/首次位置/符号 | 原风险及本次处置 |
|---|---|
| [RikkaHubApp](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt>) 85 restore | 已回滚恢复错误保留原数据；保留此恢复策略，原始 throwable 日志改为脱敏诊断。 |
| 同上 125–131 resumePending history | 已正确重抛取消；原先只有一句失败无类型/位置，补稳定操作诊断。 |
| 同上 140 resumeMediaCreations | suspend runCatching 会吞取消；改用公共取消感知边界，安全诊断。 |
| 同上 156 resumeMemoryConsolidation | 同上；普通失败仍不阻塞其他启动任务。 |
| 同上 183 incrementLaunchCount | 旧独立入口同样保留取消和诊断，虽当前维护主入口另有步骤。 |
| 同上 207 runMaintenanceStep | 取消不再被当成单个步骤失败后继续后续清理；普通故障仍隔离；步骤名只来自本类常量调用。 |
| 同上 234 startWebServerIfEnabled | delay/awaitLoaded 取消直抛，不伪造启动故障；权限缺失分支仍正常跳过。 |
| 同上 326 AppScope handler | 未捕获故障诊断去除异常原文，保留类型/代码位置。 |
| [BackupPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupPage.kt>) 139 本地导出 | 改 runUiOperation；取消不触发失败 toast，finally 清 busy；已有导出临时文件 finally 删除保留。 |
| 同上 177 本地恢复 | CancellationException 先行重抛，普通故障增加脱敏诊断，busy finally 保留。 |
| 同上 242 远端立即备份 | 取消直抛，busy 在 finally 清理，成功/失败回调仍区分。 |
| 同上 376 远端恢复 | 不再把取消提示为恢复失败；保留 restoringName/busy finally。 |
| 同上 416 删除远端备份 | 取消不当作删除失败，普通失败记录稳定操作；成功后才刷新列表。 |
| 同上 543/558 WebDAV/S3 测连接 | 取消直抛且 testing 必清，普通失败只记脱敏诊断。 |
| 同上 751 ImportSheet | 导入各类型统一取消/诊断/busy finally；各临时文件原有删除 finally 保留。 |
| [BackupVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt>) 111 WebDAV 列表 | suspend emit/list 取消不再生成 UiState.Error；普通失败增加操作诊断。 |
| 同上 276 S3 列表 | 同上。189 stream.close 为同步 best-effort 清理，保留。 |
| [WorkspaceVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceVM.kt>) 22 create / 28 rename | 原 runCatching 结果完全丢弃；改取消直抛、普通失败诊断。未新增页面状态 API，失败仍留原列表供重试。 |
| [WorkspaceDetailVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailVM.kt>) 197 delete | 改取消直抛，普通失败保留页面 error 并诊断。 |
| 同上 214 import | 同上；不把取消当作可恢复导入错误。 |
| 同上 232 export | 同上；SAF 空输出仍明确失败，原导出资源所有权未变。 |
| 同上 259 exportToCacheFile | 改取消直抛并诊断，避免取消后继续 onReady 分享。 |
| 同上 357 executeTerminalCommand | 改 runUiOperation，命令失败有诊断，取消也在 finally 清 running，不永久锁住输入。 |
| 同上 328 installRootfs | 已有取消重抛；外层仅捕 Exception，JVM Error 不转成普通安装失败；增加脱敏操作诊断。 |
| [WorkspaceFileEditorPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceFileEditorPage.kt>) 73 read | 快速换文件导致旧 LaunchedEffect 取消时不再回写“读取失败”；普通读失败有操作诊断。 |
| 同上 107 save | 保存取消直抛，saving finally 清理；成功才 toast 保存成功。 |
| [WorkspaceTerminalSessionManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSessionManager.kt>) 153 prepare | 已有取消重抛；改只捕 Exception，不把 JVM Error 判成缺 rootfs；日志去除 root 路径/异常原文。 |
| 同上 176 create | 同步创建改取消感知结果边界，日志同样脱敏；现有挂载错误 UI 保留。 |
| [WorkspaceTerminalSession](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSession.kt>) 170 openUrl | 同步 Intent 降级保留；日志去除完整 URL（可能带 token），仅输出操作/异常类型/代码位置。 |
| [SshTerminalTransport](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/SshTerminalTransport.kt>) 88 control | 同步控制任务不再丢弃所有异常；取消/JVM Error 直抛，普通控制失败有脱敏诊断；89 已关闭 executor 的 RejectedExecutionException 正常忽略。 |
| [SkillsVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsVM.kt>) 98 importFile | CancellationException 先行重抛，普通失败有安全诊断；不在取消后发布 import 失败回调。 |
| 同上 176 importGitHub | 同上；移除 printStackTrace，避免 URL/个人路径泄漏到日志。 |
| [AssistantImporter](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt>) 78/104 两个 picker | 外层改取消感知边界，保留 isLoading finally；日志去除异常原文。 |
| 同上 283 importAssistantFromUri | 内层不再先吞取消导致外层误判成功；普通格式/导入失败仍在内层一次 toast，取消直抛。 |
| [AssistantPromptPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantPromptPage.kt>) 318 template preview | Transformer suspend 取消不再渲染 UiState.Error，保留正常模板错误展示。 |
| [TranslatorVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorVM.kt>) 132/134 translate | 原取消正确，保留 job 身份 finally；替换 printStackTrace 为脱敏诊断，错误 Flow 不变。 |
| [ImgGenVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenVM.kt>) 181 generate / 229 edit | 取消从 return@launch 改为重抛，普通故障不输出 provider 响应原文，仍提供 UI 错误。 |
| 同上 351 deleteImage | 取消不再被捕成删除失败；普通失败脱敏诊断。 |
| 同上 366/368 deleteImages | 原取消正确，保留批量失败返回；日志去原异常原文及无必要对象标识。 |
| [ImgGenPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenPage.kt>) 372 reference import | 同步 IO 块采用取消感知 Result；坏图仍按原设计跳过，后续图继续。 |
| 同上 767 save gallery | 取消直抛不再 toast 保存失败，普通故障有操作诊断。 |
| [MemoryCenterVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/memory/MemoryCenterVM.kt>) 162 autoSave | 原取消/finally 正确；普通故障补安全操作诊断，候选仍留待手动保存。 |
| 同上 263 previewRecall | 查询取消直抛，不再转换成“零检索结果”；普通故障仍空结果降级并诊断。 |
| 同上 302 launchAction | 原取消/busy finally 正确；普通故障补诊断。 |
| [MemoryCenterPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/memory/MemoryCenterPage.kt>) 499 preview | 第二层 runCatching 也改取消感知，否则会再次吞掉 VM 层已重抛的取消。 |
| [DebugPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugPage.kt>) 319 recover | 取消不触发错误 toast，recovering finally 复位，普通恢复失败诊断。 |
| [QuickStartVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/quickstart/QuickStartVM.kt>) 60 import | 已在 fold 明确重抛取消，保留；改原始 throwable 日志为脱敏操作诊断。 |
| [ConversationExport](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt>) 166 save | 取消直抛；成功 toast 不再出现在已取消的保存后；去 printStackTrace。 |
| 同上 200 prepare/share/save | 准备操作改 runUiOperation，exporting finally 清，去异常原文日志；376 URI 名、832/839 tool JSON 为同步展示 fallback，保留。 |
| [ExportHooks](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/hooks/ExportHooks.kt>) 68 writeToUri | 改真实失败边界，空输出流不伪装成功；取消/JVM Error 不吞，诊断脱敏。 |
| [CropLauncher](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ai/CropLauncher.kt>) 57 confirm | 取消直抛；临时文件、saving、onCleanup 移入 finally；compress=false 明确失败，旧 message+stackTraceToString 日志移除。 |
| [ProviderQuotaText](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ProviderQuotaText.kt>) 62 query | 取消不缓存/渲染失败；普通失败仍不缓存，安全诊断。 |
| [ChatMessageEditedFiles](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageEditedFiles.kt>) 86 export / 175 share | 不再静默丢弃操作错误；取消直抛，空输出流失败，普通失败给可重试提示+安全诊断。 |
| [ImagePreviewDialog](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ImagePreviewDialog.kt>) 78 save | 取消直抛；progress 在 finally 恰好 dismiss，日志不含 URL/异常原文。 |
| [TextArea](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/TextArea.kt>) 102 import | 取消不再回写失败提示；普通导入错误诊断脱敏。 |
| [HighlightCodeBlock](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt>) 129 export | 空输出流不成功；取消先行重抛，普通错误不 printStackTrace。475 preview 已重抛取消，保留；540 纯高亮失败返回原文，保留。 |
| [Markdown](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt>) 356 CSV export | 与代码导出一致，空输出流明确失败，取消重抛，安全诊断。 |
| [SafeModeActivity](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/activity/SafeModeActivity.kt>) 141 export / 187 restore | 已有取消/finally，保留；外层只捕 Exception，fatal JVM Error 不作普通失败；诊断不含文件路径。173 内层 Throwable 仅删临时文件后原样重抛，保留。 |
| [MateChangelogPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/mate/MateChangelogPage.kt>) 58 releaseNotes | 取消直抛、loading finally 清；普通联网失败仍保留上次/内置结果而非清空。 |
| [MateAutoBackupWorker](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/mate/MateAutoBackupWorker.kt>) 112 retention / 132 record-check | 两个 suspend runCatching 改取消感知；清理失败仍不推翻已成功备份；诊断脱敏。125/151 原取消分支保留；127/153 故障推进检查时间/有限重试策略保留，不存 provider 原文。 |
| [WebServerManager](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt>) 86 register / 122 unregister | suspend NSD 边界不再吞取消；日志安全。103 start / 129 stop 先重抛取消并清 isLoading，普通操作诊断脱敏。148 端口不可绑定返回 false 的同步判定保留。 |
| [NsdServiceRegistrar](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/NsdServiceRegistrar.kt>) 83 register | 取消时清理并重抛；阻塞注册后显式 ensureActive；取不到 IP 时也释放已拿到 multicast lock。 |
| 同上 94/102 cleanup | unregister 抛错也尝试 close；任何 close 结果都进入 finally 释放锁，引用先清；只捕恢复性 Exception，日志去原文。130 local IP 故障仍降级 null，安全诊断。 |
| [FilesRoutes](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/routes/FilesRoutes.kt>) 241 assets | 仅打开 asset 的 IOException 转为 404；响应写入故障/取消原样传播，不再把已开始响应的断连改成 404。 |
| [AIIconRoutes](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/routes/AIIconRoutes.kt>) 27 icon | 仅打开可选内置图标失败时回退 SVG；流已响应后失败不再尝试第二次响应。共用可测 respondAsset 资源边界。 |

## 已读取、保留的解析/可选降级与专有控制语义

以下不是“没发现 catch 所以跳过”，均读取了所列位置的上下文。保留不等于绝对无风险；需要改产品反馈的候选列在最后。

| 文件 / 首次位置 | 逐项保留原因 |
|---|---|
| [VoiceSessionController](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/VoiceSessionController.kt>) 67/100/128 | 三处都有“本作用域已取消则重抛”的显式条件；活跃作用域里的 ASR 自有 timeout/排队 Deferred 失败会转 Event.Failed 并进入语音 Error，不能机械改成吞掉内部 timeout。录音 dispose 在 finally。 |
| [ManagedFilePreviewDialog](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/file/ManagedFilePreviewDialog.kt>) 140/142、147/149、240/242/244 | 每层取消均先重抛；解码失败仍允许原文件导出；缺外部查看 App 是独立可恢复 UI 提示；opening finally 清理。 |
| [ManagedFilePreviewPdf](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/file/ManagedFilePreviewPdf.kt>) 69/95/97 | Throwable 仅 recycle 位图后原样重抛；外层取消不吞，普通坏 PDF 给页内提示，descriptor/page/renderer 均 use。 |
| [ChatInput](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt>) 574 | 同步 IME grant 释放 best-effort；属于清理，不加每次成功/失败噪声。577/579、597/600：取消重抛，长文本取消/失败恢复已消费文本；634/636：可选补全 provider 失败忽略，但取消保留。 |
| [WorkspaceCompletionProvider](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ai/completion/WorkspaceCompletionProvider.kt>) 105/107、154/156 | 目录消失和没有 .gitignore 是索引可选项，不因逐目录缺失刷日志；取消仍重抛。 |
| [WorkspaceCwdPicker](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceCwdPicker.kt>) 70/72 | 已重抛取消，目录异常给空列表；产品上“空”与“读失败”未区分，列为后续反馈候选，不谎称已修。 |
| [ChatMessage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt>) 291/293、374 | 网页预览取消已重抛且 preparingPreview finally 清；引用 JSON 非结构化输出回退正常，不记录聊天内容。 |
| [Mermaid](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Mermaid.kt>) 96/98、151/153 | 保存/预览已有取消重抛、权限/成功结果判断；普通故障有 toast，保留本轮既有业务。320 是 HTML 内 JavaScript catch，非 Kotlin 协程边界。 |
| [MaterialMarkdown](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MaterialMarkdown.kt>) 210 | 同步外部图片 Intent 无 handler 降级，不属于 suspend；未新增日志记录图片 URL。 |
| [ShareSheet](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ShareSheet.kt>) 70 | 同步 chooser 启动错误；不是吞协程取消。现有 printStackTrace 尚属诊断脱敏后续候选。 |
| [VideoPlayerDialog](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/VideoPlayerDialog.kt>) 114 | 同步外部查看器启动 fallback；不涉及 suspend，保留，缺用户失败反馈列为后续。 |
| [ChatMessageAttachments](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAttachments.kt>) 51/55/56/66 | 依次为 URI、file URI、FileProvider、chooser 同步解析/平台能力 fallback，防止未知 scheme 点击崩；未改成泛化错误日志。 |
| [AttachmentChips](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ai/AttachmentChips.kt>) 288 | URL 显示名解析，无挂起；失败用 fallback。 |
| [ChatMessageTools](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt>) 150/351/462 | 工具文本、问题结构、答复 JSON 同步展示/构造 fallback，非事务/网络错误边界，保留。 |
| [ChatMessageCot](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt>) 100 | chart 成功标志 JSON 可选解析，失败按普通工具展示。 |
| [ToolUI](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt>) 172 | 同步 pretty JSON 失败回原始文本展示；不把工具输出写日志。 |
| [BuiltinToolUIs](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt>) 710 / recover / 831 | 时间格式三级解析与发布日期解析；失败用原日期，不是网络/操作故障。 |
| [HistoryMemoryTab](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/HistoryMemoryTab.kt>) 48/50 | 关联建议失败不阻止人工保存；取消正确重抛。 |
| [MemoryReextractPicker](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/MemoryReextractPicker.kt>) 66/68、147 | 异步读取有 failed 提示且取消重抛；日期输入解析失败用 null 校验。 |
| [MemoryTaskHistoryScreen](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/MemoryTaskHistoryScreen.kt>) 213/215 | retry 取消重抛、busy finally 清；普通失败用未恢复状态提示，不写记忆日志原文。 |
| [MemoryReviewScreen](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/MemoryReviewScreen.kt>) 101/103、122/124、493 | suggestions 可手动替代，批量动作有 error/busy finally；取消正确；日期同步原文降级。 |
| [MemoryUsageScreen](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/MemoryUsageScreen.kt>) 76/77/96/97/98/113 | 历史记录 JSON 列表/原因的展示 fallback，不影响存储，不新增包含记忆内容的日志；仅匹配与邻近结构读取，未重新逐行审整个屏幕。 |
| [PropertyEditor](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/PropertyEditor.kt>) 203 | 同步 JSON 编辑器按输入给校验错误；保留用户可见、最多100字符错误，不当作系统故障日志。 |
| [WorkspaceDetailVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailVM.kt>) 88/90、105/107、181/183、283/285、297/299 | 现有批量导出、目录加载、设置保存各有取消重抛/页内结果，finally 已清 exporting；94 临时文档删除为同步 best-effort。未为每文件重复加日志。 |
| [WorkspaceDetailPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt>) 303、998/1000 | 同步外部 app fallback；图片缩略图解析取消正确、缺图回默认图标，避免列表滚动每项刷日志。 |
| [WorkspaceSshEditorVM](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceSshEditorVM.kt>) 151/153/160/167、190/192 | host-key 未知/变化是用户确认流程，不改成普通网络错误；取消正确重抛，保存普通错误已有状态。 |
| [WorkspaceSshEditorPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceSshEditorPage.kt>) 295 | 同步有界私钥读取，失败不写私钥/个人路径日志；缺显式用户反馈留后续候选。 |
| [WorkspaceStorageMountCard](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceStorageMountCard.kt>) 169/284/306/308 | 挂载路径/Document ID 校验与 ROM 设置入口备用跳转，均同步解析/能力 fallback，保留。 |
| [WebJwt](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/WebJwt.kt>) 102/104 | 无效 token 的 Base64/JSON 安静失败关闭；不记录 bearer/token 原文。 |
| [RouteUtils](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/routes/RouteUtils.kt>) 8 | 同步 UUID 校验，转换成 BadRequest 正确。 |
| [ConversationRoutes](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/routes/ConversationRoutes.kt>) 384 | SSE UUID 纯解析，后续 reference 生命周期 try/finally 未变；不涉及网络取消捕获。 |
| [QRCodeScanner](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/QRCodeScanner.kt>) 212 | 专有 ReaderException 表示当前帧没码，finally reset，逐帧日志会噪声，保留。 |
| [UpdateCard](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/UpdateCard.kt>) 164 | 同步发布时间解析失败只不显示日期，保留。 |
| [ChatFont](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/theme/ChatFont.kt>) 50/58/59 | 字体损坏/路径解析失败回默认字体；只读额外核对，未改。 |
| [LogPage](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/log/LogPage.kt>) 345/363 | 剪贴板 Binder 过大提示、JSON pretty 失败回文本；只读额外核对，未纳入写范围。 |
| [UIAvatar](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/ui/UIAvatar.kt>) 135/137 | 既有取消正确、NonCancellable 清未提交头像；按禁止触碰既有Avatar逻辑未改。原始 throwable 日志脱敏留给该逻辑 owner。 |
| [ChatFloatingWindows](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/floating/ChatFloatingWindows.kt>) 98 | typed ActivityNotFound 同步 fallback，只读核对；现有 URI 原文日志留给该范围 owner，不扩大写范围。 |

## 验证与未完成的验收

- 新增 [UiOperationTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/ui/components/UiOperationTest.kt>)：成功/失败结果、finally清busy、真正挂起任务 cancelAndJoin、取消不调用 failure UI/日志、重试恢复、fatal JVM Error 直抛、异常原文不进日志。
- 新增 [AssetResponseTest](</root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/web/AssetResponseTest.kt>)：缺资产只在响应前回退、流 IO 故障不触发第二HTTP响应、开流/流期间取消原对象重抛、输入流成功/失败均关闭。
- 直接 Java 调用缓存 Kotlin 2.4.21 compiler 编译真实生产 [UiOperation](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/components/UiOperation.kt>)、[AssetResponse](</root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/web/AssetResponse.kt>) 与公共 helper 后运行 JUnit 4.13.2：**11/11通过，exit 0**。输出类目录：`/tmp/rikkahub-ui-web-exceptions.gtfJhE`。此前冻结期间版本5/5亦通过，非最终结果。
- 定向 `git diff --check` exit 0；未自行运行 Gradle。Lead 曾在中间冻结版本完成main编译无本范围错误；本次后续生产改动仍需最终统一 main/JVM/Android test 编译。
- 用户跳过本次手机连接/真机验收。未声称 Android instrumentation、Ktor真客户端断网、SAF ROM 行为或实机语音通过。
- 尚需设备/集成验证：Ktor响应写入断开时资源关闭；NSD取消及无IP分支的 multicast lock；SAF取消后的临时目标文件行为；现有视图回调在销毁期间取消时的状态恢复。
- 有界保留候选：同步 Intent 启动失败（MaterialMarkdown、ShareSheet、VideoPlayerDialog、附件/SSH私钥选择）部分仅安静降级，今后可统一用户提示；所辖已正确取消且已有页内失败反馈的可选建议/预览未机械加日志。
- 额外只读发现：LogPage剪贴板 catch RuntimeException 也覆盖CancellationException；其写范围未获批准，已报告Lead。未检查统计页所有解析点，也未开展前一轮只读Web安全/XSS/CORS全链路审查；不要把本台账当作那些项目的完成证明。
