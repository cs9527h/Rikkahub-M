# 设置/媒体状态拆分、异常审查与真实交互回归

任务：task-12；记录完成时间：2026-11-28。生产代码与测试已冻结，由 Lead 统一构建、执行 JVM 测试及安排 CI 模拟器测试。

## 1. 实际生产接线与拆分

| 原页面 | 已接入的生产结构 | 实际业务职责 |
|---|---|---|
| 文件管理页原 668 行 | [SettingFilesPage][files-page] → [SettingFilesContent][files-content] + [FileManagerState][files-state] | route 保留真实 catalog、引用保护、SAF 与预览；状态机负责筛选/区域、串行删除、批量结果、成功快删和恢复；content 渲染原 MD3 Expressive 控件并调用同一状态机 |
| 媒体输入区原 1226 行 | [MediaCreationComposer][composer] → 可注入端口的生产 content + [原模型/参数/素材控件][controls] + [MediaComposerState][panels] | 既有 VM 适配、面板/选图角色恢复、实际生成禁用状态分开；不是只为测试另造 UI |
| 既有媒体 VM | [MediaCreationVM][media-vm] → [MediaDraftController][draft-state] | reducer 的 draft 已成为 VM 真正的草稿源；模型选择、revision、提交 ticket、成功清稿与失败/取消保稿均走 reducer |
| 媒体会话页面 | [MediaCreationPage][media-page] | VM key 使用会话 id，composer 子树按 vm.sessionId 分区，阻止复用旧会话的 UI 恢复状态 |

拆分时实测行数（最后格式调整前）：文件 route 470、content 323；媒体 composer 400、controls 972；SettingSpeechPage 586、MediaCreationPage 533。新增业务状态在独立状态类中，文件数量增加不等于业务复杂度消失。SettingSpeechPage 已有 VM 与生命周期行为保留，本轮选择文件管理与媒体作为两个实际拆分对象。

**DI：无需新增 Koin 注册。** 文件状态由 rememberSaveable 持有；媒体 reducer 由既有 MediaCreationVM 持有；ComposerActions 只是现有 VM 的生产端口适配。未修改资源、di、构建或版本发布配置。

## 2. 状态迁移、不变量与边界

### 文件管理

1. 删除端口仍在生产 route 每次读取当前记录及当前头像引用；继续调用 AvatarStorage.deleteIfUnused / FilesManager.delete，保持会话/头像引用保护，测试注入的存储端口不取代生产保护。
2. 只有删除明确返回 true 才加入 removedPaths。false/普通异常保留条目；批量逐项执行、按路径去重，成功项立刻隐藏、失败项可继续操作；取消原样抛出并 finally 释放 busy。
3. removedPaths 不再由当前 tab 的 remember key 重建；切换分区不会丢失刚完成删除的快显状态。reconcile 只接收完整、成功的 catalog，加载占位和局部分区列表不用于清理 tombstone。
4. 对仍包含已删路径的旧 catalog，条目继续隐藏；完整 catalog 确认路径缺失后清理 tombstone，此后同路径再次出现按重新引入处理。**catalog 没有单调版本信息，所以测试不证明“权威缺失之后又到达的更旧快照”可被辨认；未宣称修复底层任意乱序快照。**
5. Saver 恢复区域、筛选、排序、成功隐藏路径及 SAF pendingExportId；busy 和破坏性确认快照刻意不恢复，避免进程重建后沿用过期删除目标。
6. 切区域只重置不相容筛选及待确认目标，不重置 sort 或已发起的 SAF 请求身份。导出、预览和原有快删反馈保持。

### 媒体创作

1. reducer draft=null 明确表示未加载，beginSubmission 返回 null；initialize 不覆盖已加载状态。既有 session 消失通知及服务端任务运行链保留。
2. 提交前同步 TextFieldState，提交成功交接前再次同步。ticket 保存整份草稿 revision；prompt、素材、模型、参数或编辑目标发生变化后，不清空后续草稿；改动后撤销至同样文本也保留新 revision。
3. 未改动的成功提交仅清 prompt/assets/editingNodeId，模型与参数保留。改动后的成功仅在仍指向原 editingNodeId 时脱离旧编辑目标，不覆盖新编辑目标。
4. 同时最多一个 pending ticket；生成按钮绑定 submitting 禁用。失败/取消只释放门禁，不清稿；旧 ticket 的晚到回调不处理同一 reducer 的新 ticket。
5. 模型切换继续遵守原 sameShape 参数保留/异形重置，以及 supportedBy 素材适配、首尾帧和参考图规则。面板关闭过时的能力相关选择，但系统相册已发起的 importRole 保留，返回后交给当前 VM 能力规整。
6. 会话 id 对 VM 与 composer 子树分区；不同会话各持有独立 reducer，首会话晚到成功不清第二会话输入。状态机测试和 Compose 会话切换夹具验证这一隔离，不冒充完整导航/数据库端到端测试。
7. ASR lifecycle、APIMart 入口、媒体已有产出取消/重试链均保留。新增“撤销”交互覆盖退出编辑/清空输入；服务端实际取消网络任务仍由既有服务处理，不因本轮抽取而重写。

## 3. 完整所辖 catch / runCatching 分类台账

审查范围为 app 的 setting（含 components）与 mediacreation。逐处读上下文后分为：普通故障诊断、预期校验/fallback、清理后重抛、取消透传。表内多行号对应同一业务 try 的取消与普通异常分支，不重复当作两处缺陷。

公共 `runCatchingCancellable` 只捕普通 Exception，CancellationException 和 JVM Error 直抛。UI 诊断适配器也先透传取消/致命错误，再调用公共 `formatExceptionForLog(operation,error)`；日志使用常量操作名，不输出原始 exception message、prompt、URI、令牌、URL query 或私人路径。校验/fallback 不机械增加日志；清理后重抛不重复报告。

### 设置目录

| 位置（审查行号） | 分类与最终处置 |
|---|---|
| [文件页:152–155][files-page] 头像同步 | 普通故障补 files.avatar.synchronize 脱敏诊断；取消上抛；原 best-effort 保留，迁移失败不遮掉已有文件 |
| [文件页:161–164][files-page] 索引修复 | 普通故障补 files.index.repair；取消上抛；继续显示已有索引 |
| [文件页:174–178][files-page] catalog 订阅 | 普通故障诊断并保留 Result.failure 错误 UI；取消上抛 |
| [文件页:212–216][files-page] SAF 写入 | 普通故障补 files.export.write 并原失败反馈；取消上抛；finally 释放 exporting |
| [文件页:228][files-page] SAF 启动 | 补 files.export.launch；失败清 pendingExportId；诊断入口透传取消 |
| [文件状态:93–97][files-state] 单项/批量删除 | 取消不记失败、不继续后续项；普通异常走注入日志端口、计失败、不误移除；finally 释放 busy |
| [提供商详情:490–495][provider-detail] 模型列表 | 保留旧列表 fallback；取消上抛；原 printStackTrace 替换为 provider.models.list 脱敏诊断 |
| [MCP 页:462][mcp] JSON 导入 | 普通解析/导入失败保留表单错误，同时补 settings.mcp.import；诊断入口透传取消 |
| [主题页:533][theme] JSON 导入 | 保留表单错误，补 settings.theme.import；取消不变普通错误 |
| [设置首页:306][settings-page] 系统分享 | 精确 ActivityNotFoundException；保留无分享 App toast，补 settings.share.launch，不扩大 catch 范围 |
| [通知设置:302][notification] 电池优化设置 | **保留预期 fallback**：不同 ROM 逐个尝试系统 Intent，找不到一个时换下一个；不是宽泛吞异常，不对每次探测重复报错 |
| [网络设置:132][network] 代理测试 | 改用取消友好 Result 边界；普通失败补 settings.proxy.test；busy 改为 finally 释放 |
| [搜索详情:286][search] 搜索测试 | Result 失败在事件协程内诊断，不在重组渲染中打日志；普通抛错转换失败 UI；取消透传；finally 释放 testing |
| [媒体设置][media-settings] S3 测试 Result.onFailure | 补 settings.media.upload.test；取消透传；finally 释放 testing；不移除 APIMart 入口 |
| [提供商列表:406][provider-list] 扫码导入 | 普通解码/导入失败保留 toast，补 provider.qr.import；使用取消友好边界，不记录二维码内容 |
| [提供商列表:428][provider-list] 图片二维码 | 同上，保留无二维码的正常反馈；不记录 URI 或二维码数据 |
| [字体页:92][font] 外层导入 | 普通故障补 settings.font.import；取消不再变成导入失败 toast |
| [字体页:429][font] 字体有效性校验 | **保留校验转换**：普通失败包装为带 cause 的 IllegalArgumentException；取消/致命错误由公共边界直接抛出 |
| [字体页:436][font] 导入临时文件清理 | **保留清理后重抛 Throwable**：删除临时文件后原样抛出，不转换取消或致命错误，不在内部重复报告 |
| [字体页:460][font] 字体原子替换回滚 | **保留清理后重抛 Throwable**：恢复旧字体后抛原错，不机械缩窄导致回滚遗漏 |
| [字体页:472、473][font] 删除路径 canonical | **保留受限清理 fallback**：canonical 失败就不删除；改为取消友好边界，不记录用户路径、不盲删 |
| [配额配置:224][quota] URL 模板校验 | **保留 false 校验结果**：输入编辑中的无效模板是常见情况，不按网络故障记日志；取消/致命错误透传 |
| [连接测试:131][connection] 非流式 | 取消友好边界；普通失败 provider.test.nonstream，再更新错误 UI |
| [连接测试:156][connection] 流式 | 取消友好边界；普通失败 provider.test.stream，不把关闭页面的取消作为测试失败 |
| [连接测试:183][connection] 工具调用 | 取消友好边界；普通失败 provider.test.tools，不记录消息或工具输入 |
| [提供商配置:579][provider-config] service-account 导入 | 补 provider.service-account.import，reader 改 use 关闭；保持原解析/UI 行为，不日志凭据内容 |
| [关于页:328][about] 权限恢复后启动安装器 | 修复忽略 Result 后无条件成功 toast；仅启动成功才反馈成功；普通失败诊断并显示失败；InstallPermissionRequiredException 仍走预期权限分支；effect key 包含 apk/context |
| [关于页:376][about] 打开发布页 | 普通失败补 settings.update.release-page；取消/致命错误不吞；不记录 URL query |
| [关于页:478][about] 浏览器下载 | 普通失败补 settings.update.browser，保留原 UI 流程 |
| [关于页:488][about] 手动安装 | 保留权限/普通失败区分；普通失败补 settings.update.install，预期权限异常不当普通故障重复记录 |
| [关于页:526][about] 下载完成自动安装 | 同手动安装分类；取消友好边界，不吞致命错误 |

### 媒体创作目录

| 位置（审查行号） | 分类与最终处置 |
|---|---|
| [媒体 VM:259][media-vm] 文件导入 | 取消友好 Result；普通失败 media.draft.import；移除旧 URI + Throwable 原文日志，保留部分文件不可读反馈 |
| [媒体 VM:287][media-vm] 画板保存 | 取消友好 Result；普通失败 media.draft.sketch；不把取消当添加失败 |
| [媒体 VM:373][media-vm] 草稿副本 | 取消友好 Result；普通失败 media.draft.copy 后清理副本；移除旧私人 path 日志 |
| [媒体 VM:501–504][media-vm] 提交 | 原取消分支上抛；普通失败 media.submit + 原错误事件；ticket completion 负责释放提交门禁 |
| [产出卡:525][record] 单项保存 | 取消友好 Result；普通失败 media.gallery.save；只成功才成功 toast |
| [产出卡:542][record] 分享 | 取消友好 Result；普通失败 media.share；保留系统分享行为 |
| [媒体页:504–508][media-page] 批量保存 | 保留逐项继续和失败汇总；取消立即上抛；普通失败 media.gallery.batch |
| [相册工具:292][media-common] 写入失败清理 | **保留清理后重抛**：撤销 MediaStore 项后原错继续向 UI 边界传播；不在此重复打日志 |
| [相册工具:293][media-common] 清理自身失败 | 清理异常 addSuppressed 到原异常，不再覆盖原故障；Android 9 的请求权限切回 Main |

## 4. 已保存的回归用例：21 JVM + 10 Compose

### JVM（21 项）

| 文件 | 数量 | 实际验证内容 |
|---|---:|---|
| [FileManagerStateTest][file-tests] | 6 | success/false/异常分别处理；跨 tab 与旧 full catalog；路径缺失后重新引入；批量去重与重入门禁；取消保留先前成功项并释放 busy；Saver 恢复筛选/SAF/快删但不恢复确认；区域筛选重置 |
| [MediaDraftControllerTest][draft-tests] | 9 | nullable 加载；未改稿成功清空；改 prompt 保稿；只改模型/参数保稿；编辑后撤销 revision；失败/取消和旧 ticket；跨 controller 会话隔离；模型/素材规整；保留新 editingNodeId |
| [MediaComposerStateTest][panel-tests] | 4 | 面板/选图角色 Saver；换模型关闭旧能力面板；取消和 JVM Error 在 Android 日志前透传 |
| [SettingsDiagnosticsTest][diagnostic-tests] | 2 | 设置错误边界不把取消或 JVM Error 转成普通 UI 错误 |

### Android Compose（10 项）

| 文件 | 数量 | 实际交互而非源码匹配 |
|---|---:|---|
| [SettingFilesInteractionTest][file-ui-tests] | 6 | 实际菜单→删除确认→快显隐藏→切 tab；false 不误移除；异常不误移除且恢复按钮；实际清理 sheet→批量确认→失败保留；成功 tombstone 经 StateRestorationTester 恢复；真实搜索输入/恢复及区域切换重置 |
| [MediaComposerInteractionTest][media-ui-tests] | 4 | 点击真实生成按钮锁重复提交；真实模型 sheet 选择与编辑输入后旧结果不清稿；退出编辑/清空/失败保新输入；模型面板与 TextFieldState 保存恢复；会话切换后首会话晚到结果不清第二输入（部分断言组合于同一用例） |

测试直接渲染生产 content，替换的只是存储/VM 端口；没有源码字符串匹配用例。媒体夹具接同一个 reducer 并模拟服务回执，不代表真实供应商/数据库/全导航链已端到端执行。

## 5. 验证状态与未执行项目

- 本队执行过 `git diff --check`：通过；执行过静态行数统计及逐项上下文阅读。
- 本队未运行本轮 Gradle、formatter、手机安装、commit/push。Lead 正在统一 Gradle（包括新增测试），最终编译/JVM 结果由 Lead 汇总；文档不把“已保存测试”写成“已通过测试”。
- 初轮 main 编译发现移动控件后的两个 private 扩展跨文件访问问题，已定向改为 internal，未扩大为公共 API。最新统一编译结果待 Lead 反馈。
- 10 项 Compose 用例交由 CI 模拟器执行，当前记录不宣称已跑设备用例。
- 用户已明确跳过本次手机连接/真机验收；没有据此跳过代码、JVM 或 CI 模拟器回归，也不将模拟器结果当真机结果。
- 当前仍未验证：特定 ROM 的系统选图/SAF grant、真实相册权限、供应商网络任务与完整导航链、release 帧时/耗电。共享目录中其他队员的 ASR/APIMart/文件引用/电池优化更改保留，本轮不归属其成果。

[files-page]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingFilesPage.kt>
[files-content]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingFilesContent.kt>
[files-state]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/FileManagerState.kt>
[composer]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationComposer.kt>
[controls]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationComposerControls.kt>
[panels]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaComposerState.kt>
[media-vm]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationVM.kt>
[draft-state]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaDraftController.kt>
[media-page]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationPage.kt>
[provider-detail]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt>
[mcp]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt>
[theme]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingThemePage.kt>
[settings-page]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt>
[notification]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNotificationPage.kt>
[network]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt>
[search]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchDetailPage.kt>
[media-settings]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMediaPage.kt>
[provider-list]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt>
[font]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt>
[quota]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/QuotaQueryOption.kt>
[connection]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConnectionTester.kt>
[provider-config]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt>
[about]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAboutPage.kt>
[record]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationRecordItem.kt>
[media-common]: </root/workspace/Rikkahub-M/app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationCommon.kt>
[file-tests]: </root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/ui/pages/setting/FileManagerStateTest.kt>
[draft-tests]: </root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaDraftControllerTest.kt>
[panel-tests]: </root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaComposerStateTest.kt>
[diagnostic-tests]: </root/workspace/Rikkahub-M/app/src/test/java/me/rerere/rikkahub/ui/pages/setting/SettingsDiagnosticsTest.kt>
[file-ui-tests]: </root/workspace/Rikkahub-M/app/src/androidTest/java/me/rerere/rikkahub/ui/pages/setting/SettingFilesInteractionTest.kt>
[media-ui-tests]: </root/workspace/Rikkahub-M/app/src/androidTest/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaComposerInteractionTest.kt>
