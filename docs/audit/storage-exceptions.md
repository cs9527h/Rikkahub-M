# 存储边界异常审查（task-16，限定修复已冻结）

## 边界与当前状态

- 仅负责 `app/.../data/sync`、`data/files`、`data/db`、`data/model` 及对应 JVM 测试；Lead 明确扩展允许独占 `androidTest/data/db/migrations` 回归。不声称已读其他目录。
- 初始统一 Gradle 期间只读，之后 Lead 明确解冻；已完成其锁定的三类真实风险及死 API 删除，现再次冻结等待 Lead 统一验证。全程未运行 Gradle、格式化器、手机或提交发布。
- 下表行号为初始审查快照，文字保留候选建议及理由；**是否实际修改以末节最终清单为准**，不是所有“需/拟”项均已改动。
- 当前技能目录均为 DSH 专用，和 Kotlin 存储任务无匹配；已读仓库 AGENTS。
- 现有头像最终引用确认与原子 rename 必须保持锁内；保留附件 live 保护和备份磁盘快照。
- 检索只是异常站点清单，不等同逐项审查。下表只有读过上下文后才添加结论。

## 已读：同步客户端

| 位置 | 审查结论 / 解冻后动作 | 依据 |
|---|---|---|
| [S3Client](../../app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Client.kt):35–287（所有 Result 操作） | 保留已改 `runCatchingCancellable`；它只捕获恢复性 Exception，CE/Error 直抛。日志仍输出 key，需移除动态 key；错误 responseBody 目前只保存在异常对象，不主动输出原文。 | 已读公共 helper。 |
| S3Client:133–157 `getObjectStream` | 拟移除无调用死 API：在 `withContext(IO)` 完成与调用方恢复间有 prompt cancellation，返回 InputStream 可能无人接管。单纯改 runCatching 并未解决所有权交接。 | 全仓 Kotlin 检索只有声明；实际备份使用 file-download API。 |
| S3Client:159–191 `downloadObjectToFile` | 保留 `prepareRequest.execute` 范围与双 use；核对下游失败临时文件清理。 | 流在作用域内消费，不跨调度器返还资源。 |
| S3Client:315–325、328–437 | SHA/XML 同步解析无挂起，日期纯 parse fallback 可保留；不机械更换成新业务失败。 | 已读上下文；大文件 IO 循环取消响应仍待对应调用链审查。 |
| [WebDavClient](../../app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavClient.kt):53–331 | 保留已改 CE/Error wrapper、字节读取 use 和作用域下载。需删除原始 URL/path 的 debug 日志，确保服务端响应内容不进诊断。 | 全操作上下文已读。 |
| WebDavClient:135–153 `getStream` | 同 S3，无调用死 API 拟移除；不以“当前内部没有 suspend”证明调度器交接无泄漏。 | 全仓 Kotlin 检索只有声明。 |
| WebDavClient:407–426 `parseLastModified` | 三种日期格式兼容 fallback 保留（同步纯解析）；终止日志不应输出原始服务端日期字符串。 | 失败不影响资源名/内容，仅元数据可空。 |
| WebDavClient:449–458 | URL path 的纯解析失败返回 null，保守保留资源而非误排除；可保持，避免把普通 parse 变成业务错误。 | 没有 suspend，失败不删除备份。 |

## 已读：sync 其余异常站点（按冻结时行号）

| 位置 | 保留 / 拟最小修复原因 |
|---|---|
| [BackupManager](../../app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt):58–65 | 临时过期包清理属尽力清理，可继续备份；保留 fallback，改只接恢复性异常，诊断不携Throwable原始message/路径。 |
| BackupManager:88–198 | `catch Throwable` 只删除本次失败archive并原样重抛，finally清理staging，保留；核心资产capture已在设置捕获后、后续挂起前执行，不能移动。 |
| BackupManager:211–243 | usableSpace失败降级硬上限是平台兼容，保留；流use+finally删staged，逐块ensureActive已存在。 |
| BackupManager:333–373 | zip单entry复制只在条目之间check取消，大entry需在复制块内ensureActive；不改恢复的发布点/已完成数据真值。 |
| BackupManager:458–474 | Room打不开时对原库拍快照的fallback合理；需CE先行，保留双失败suppressed；日志改有界脱敏格式。 |
| BackupManager:591–610 | 已CE先行，失败只移除尚未更改live的待恢复区；保留。 |
| [BackupAssetSnapshots](../../app/src/main/java/me/rerere/rikkahub/data/sync/BackupAssetSnapshots.kt):49–81 | catch Throwable仅删未完成target并原样重抛，且每块onBlock可传播取消；保留。 |
| [DatabaseBackup](../../app/src/main/java/me/rerere/rikkahub/data/sync/DatabaseBackup.kt):27–45 | 只读库可能因WAL无法恢复而改RW读取，兼容fallback合理；加CE先行，不改变schema/逻辑数据；use关闭数据库保持。 |
| [PendingRestore](../../app/src/main/java/me/rerere/rikkahub/data/sync/PendingRestore.kt):46–52 | buildEntries同步失败时隔离pending且不碰live，合理；CE不得包装成RestoreFailed。 |
| PendingRestore:54–79 | restoreSettings是suspend；CE目前经rollback后包装成RestoreFailed，需rollback保持但原样重抛CE；rollback失败保留suppressed，绝不假装可启动。 |
| [PartialMemoryRestore](../../app/src/main/java/me/rerere/rikkahub/data/sync/PartialMemoryRestore.kt):43–96 | 已CE先行，副本合并Room事务/close/finally工作区清理完整；失败后仅删未发布副本，保留。 |
| [BackupFileUris](../../app/src/main/java/me/rerere/rikkahub/data/sync/BackupFileUris.kt):8–28 | 纯URI/托管目录分类失败返回null，不猜引用；保留语义，但fatal Error不应变null。 |
| [S3Sync](../../app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt):119–140 | 过期单版本删除失败允许下项，但当前runCatching还吞Error；换CE/Error直抛wrapper并脱敏诊断。上传/恢复finally清理保留；日志移除远端name/key。 |
| [WebDavSync](../../app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt):107–128 | 同上；保留逐项trim，取消/致命错误不继续。恢复finally清理保留，日志移除远端displayName。 |
| [DeepSeekImporter](../../app/src/main/java/me/rerere/rikkahub/data/sync/importer/DeepSeekImporter.kt):249–255 | 非法日期=>null使上层使用默认时间的纯解析兼容，保留语义，限制捕获Exception。仅阅读该异常站点及相邻readBytes，不声称完整读导入器。 |

## 已读：files 异常站点

| 位置 | 保留 / 拟最小修复原因 |
|---|---|
| [FilesManager](../../app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt):106–145 | 单项URI导入失败可继续其余项；保留行为，Error/CE直抛；原URI+message+stackTrace三重日志改固定操作名脱敏。 |
| FilesManager:303–319 | http图片下载的runCatching包住suspend exportImage，正在吞取消；无use/disconnect会泄露连接。拟双层use/finally断连、wrapper保护取消，保留恢复性失败行为并记安全诊断。 |
| FilesManager:380–440 | 删除失败返回false是合理失败可见性；不改范围/live引用保护逻辑，wrapper不吞Error。 |
| FilesManager:481–509 | appScope中repository.get/insert为挂起，runCatching会吞取消且原path/stack落盘；换wrapper+固定操作诊断；不改写顺序。 |
| [AvatarStorage](../../app/src/main/java/me/rerere/rikkahub/data/files/AvatarStorage.kt):87–106 | catch Throwable仅在NonCancellable丢弃本次未提交picker结果并原样抛；保留。 |
| AvatarStorage:213–281 | CE已先行，其他异常返回false保守保留文件；仅日志脱敏。最终snapshot identity+pending+runtime确认与move.retire继续在SettingsStore.update锁内，禁止搬移。 |
| AvatarStorage:303–309 | URI+canonical验证失败返回null是“保留文件”而非误删；保留语义，限制捕获Exception。 |
| AvatarStorage:347–354 | watcher尽力操作容错、CE先行正确；保留并换安全诊断。 |
| [FileUtils](../../app/src/main/java/me/rerere/rikkahub/data/files/FileUtils.kt):41–49,52–87,104–150 | canonical/content-provider/MIME探测失败=>null/通用MIME，属于平台兼容；保留fallback。查询日志删URI和原Throwable，runCatching改只捕获恢复性Exception。 |
| [MediaCreationFiles](../../app/src/main/java/me/rerere/rikkahub/data/files/MediaCreationFiles.kt):107–130 | 最后帧索引不可用退时间抽帧、retriever异常返回null是媒体容错；保留。release清理失败不得吞Error/CE，必要诊断固定操作名。 |
| [SettingsFileReferences](../../app/src/main/java/me/rerere/rikkahub/data/files/SettingsFileReferences.kt):65–73 | 不支持序列化器=>true保留文件，已CE/Error不吞；保持。 |
| [MessageFileReferences](../../app/src/main/java/me/rerere/rikkahub/data/files/MessageFileReferences.kt):19–35,65–86 | URL格式失败=>null；自定义JSON错误=>complete=false，未知从不作删除证据；无宽捕fatal，保持。 |
| [AvatarReferences](../../app/src/main/java/me/rerere/rikkahub/data/files/AvatarReferences.kt):15–35 | 纯URI分类fallback合理；保留语义，只捕获恢复性Exception。 |
| [ManagedFileCatalog](../../app/src/main/java/me/rerere/rikkahub/data/files/ManagedFileCatalog.kt):258–291 | 非法path=>null；流式scanner/IO/Unknown保守保护保持，IllegalArgumentException窄捕获不吞Error。 |

## 已读：db/model 异常站点

| 位置 | 保留 / 拟最小修复原因 |
|---|---|
| [Migration_6_7](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_6_7.kt):35–78 | 解析失败本应回滚，不应丢记录；保留失败终止，但删对话id/异常message，保留cause且CE直抛；cursor放use/finally避免失败未关闭。 |
| [Migration_11_12](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12.kt):58–60,78–81 | **确认丢数据风险**：nodes解析失败视空数组，后清空原nodes；需fail-closed让迁移事务回滚，不能当合理fallback。selectIndex默认0的旧格式兼容保持。 |
| Migration_11_12:86–89 | SQLiteBlobTooBigException原逻辑跳过且留下原nodes，不是清空；日志需脱敏；是否进一步改为中止迁移需Lead确认，不擅自新建大blob迁移流程。 |
| [Migration_15_16](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_15_16.kt):43–48,66–73 | **确认丢数据风险**：跳过坏node后若其余node迁移有变化，DELETE整会话仅插成功rows；需坏行即失败回滚或整会话跳过，拟fail-closed并补纯逻辑回归；cursor use关闭。 |
| [MigrationUtils](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/MigrationUtils.kt):44–49 | 兼容类型名转换失败返回原始messagesJson，不覆盖未知数据；fallback保留，Error/CE不变成“成功保留原文”。 |
| [WorkspaceEntity](../../app/src/main/java/me/rerere/rikkahub/data/db/entity/WorkspaceEntity.kt):57–61,72–74,89–98 | 非法旧枚举/SSH JSON/挂载/审批覆盖采用现有保守默认；不改产品含义，仅限制捕获恢复性Exception。 |
| [Assistant](../../app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt):89–94 | 错误正则编译结果缓存合理，避免逐chunk重建异常；保留cache，但printStackTrace可能包含regex/prompt，改固定操作脱敏诊断，不缓存Error。 |
| Assistant:103–115 | 无效replacement分组保留原文合理；CE不吞，诊断不写原message/输入。 |
| Assistant:230–237 | Lorebook关键词regex格式失败=>未触发是合理兼容；窄捕获正则语法错误，不吞无关异常。 |
| [MemoryFile](../../app/src/main/java/me/rerere/rikkahub/data/model/MemoryFile.kt):69–75 | 非法history source UUID=>null合理，不猜来源；保持fallback，限制捕获恢复性Exception。 |

覆盖说明：以上为四目录检索出的全部catch/runCatching站点对应上下文；DAO无catch查询不在本次异常分项逐文件阅读声明中。后续改动后重新检索差异，若发现新站点继续登记。

## 最终限定修复（已落地，等待 Lead 验证）

1. [MigrationUtils](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/MigrationUtils.kt) 新增 `legacyNodesForMigration` 与 `messagesForMigration` 两个真实生产解码边界；不是测试专用副本。
2. [Migration_11_12](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12.kt) 使用严格解码：任意源 node 非法立即抛错，事务不会将原文当成空数组清掉；全部节点预先验证，cursor 用 use 关闭。原有 selectIndex=0 兼容及大 blob 暂留源 nodes 行为保留；大 blob 诊断移除原始对话id/Throwable日志。
3. [Migration_15_16](../../app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_15_16.kt) 取消坏行略过：解码失败直接事务回滚，不再因其他行需要 tool 合并就 DELETE 坏行；查询 cursor 用 use 关闭。代价是含损坏/未来未知类型的旧库会停止升级而不是继续丢数据，原库可保留用于后续修复。
4. [PendingRestore](../../app/src/main/java/me/rerere/rikkahub/data/sync/PendingRestore.kt) 在真正挂起的 settings 回调取消后仍同步 rollback DB/WAL/附件，然后抛出原 CancellationException 对象；不包装为 RestoreFailedException。保留 pending journal 供后续启动重试。普通恢复失败的隔离/回滚和 Error 模拟进程中断语义不变。
5. [HttpImageStream](../../app/src/main/java/me/rerere/rikkahub/data/files/HttpImageStream.kt) 为 [FilesManager](../../app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt) HTTP 图片保存所实际调用：资源始终由消费协程持有，input.use + finally.disconnect，挂起导出期间取消、解码异常、Error、非200及connect失败均关闭可用资源。未声称同步 connect/read 能被协程即时打断。
6. FilesManager HTTP 与异步索引/文件IO容错改用已有 Exception-only helper，CE/Error 不转换成普通失败；导入/索引失败日志改固定操作名+脱敏诊断，移除 base64 保存后的本地URI日志。不改删除范围、快删索引入口或 live 保护。
7. [S3Client](../../app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Client.kt) `getObjectStream`、[WebDavClient](../../app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavClient.kt) `getStream` 已删除并移除 InputStream 导入。初始全仓 Kotlin 检索只有这两个声明，无生产/测试调用；现有备份下载继续使用作用域内消费的 download-to-file API。通过移除死入口消除资源跨 withContext 返回的所有权窗口，不伪称已测试该窗口。

### 按最终有界指令保留 / 未扩改

- **未改** AvatarStorage、AvatarFileQuarantine、BackupAssetSnapshots、ManagedFileCatalog/live scanner；最终确认+原子 rename 继续锁内，快删与已有资产快照保持。
- URI/date/UUID/MIME/WorkspaceEntity/MemoryFile 等纯解析兼容 fallback 未机械改成公共 helper；`MigrationUtils.migrateMessagesJson` 原文保留策略也保持。
- 初始表中的其余候选（S3/WebDAV 动态 URL/key/date debug 日志、BackupManager/FileUtils/Assistant 原 Throwable 日志，S3/WebDAV trim 的 Throwable runCatching，Migration_6_7 cursor/错误上下文等）**本轮未新增修复**。它们是审查结果/后续待办，不是已解决声明；本轮不继续扩大扫描或跨范围变更。
- BackupManager 单大条目恢复时只在条目间检查取消、S3 同步哈希/阻塞输入的取消响应仍是残留限制；本轮未改备份生命周期或声明整套长稳验证完成。

## 精确回归与验证状态

下列都是生产代码行为调用，不是源码文本匹配；已写入但**本代理未编译/未执行**，由 Lead 统一 Gradle/CI 执行。

| 用例 | 断言与覆盖边界 |
|---|---|
| [MigrationSourceRetentionTest](../../app/src/test/java/me/rerere/rikkahub/data/db/migrations/MigrationSourceRetentionTest.kt)（3项 JVM） | 调用生产 decode helper：坏 JSON/非数组/坏node结构抛错；合法空历史/多分支保留；坏或未知消息不可被 tool 迁移悄悄略过。不代替 SQLite 事务测试。 |
| [PendingRestoreTest](../../app/src/test/java/me/rerere/rikkahub/data/sync/PendingRestoreTest.kt)（新增1项 JVM） | 实际创建 DB/WAL/附件文件、发布 restore、在 settings 回调真正挂起后取消；断言 CE 原对象、原文件回滚、journal保留及下一次 apply 可重试。原普通故障/进程中断用例继续保留；不冒充真实 Android DataStore 跨文件事务验证。 |
| [HttpImageStreamTest](../../app/src/test/java/me/rerere/rikkahub/data/files/HttpImageStreamTest.kt)（5项 JVM） | 调用生产 HTTP 消费 helper；实际挂起 callback 后取消，验证 input.close+disconnect 与同CE传播；解码 IOException、AssertionError、HTTP403、connect失败、成功路径均测清理。使用可观测的 HttpURLConnection 实例，不需要网络，不冒充设备网络长稳。 |
| [MessageMigrationRetentionTest](../../app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/MessageMigrationRetentionTest.kt)（3项 Android） | requery 真实 SQLite：11→12 四种非法源失败后原nodes与version=11不变，先前INSERT/UPDATE/CREATE回滚；15→16 assistant+tool可合并且第三坏节点会触发全事务回滚，三行原文/version=15保留；全部合法时仍正常合并。使用最小历史源表，**非 MigrationTestHelper / 完整旧 Room schema assets 验证**。CI现有 connectedGithubDebugAndroidTest 全包可覆盖。 |

- 本轮 `git diff --check` 已通过；未自行启动 Gradle、格式化器、手机或提交发布。
- Lead 建议验证：`testGithubDebugUnitTest --tests '*MigrationSourceRetentionTest' --tests '*PendingRestoreTest' --tests '*HttpImageStreamTest'`；编译 `compileGithubDebugAndroidTestKotlin` 并在现有 CI `connectedGithubDebugAndroidTest` 执行真实 SQLite 用例。
- 共享工作树包含其他代理成果，未 reset/覆盖；此清单不把 HEAD diff 里的他人既有修复算作本轮新增。
