# 全量代码复查与修复（2026-11-25）

范围：`Rikkahub-M` 全仓（app / ai / common / document / material3 / mediagen / oauth / search /
speech / terminal / ui / web / web-ui / workspace / scripts / tools），审计基线 commit `afa109f8`（1.0.8）。
承接 [2026-11-24 审计](2026-11-24-full-security-and-deadcode.md)，这次只看那之后新增/改动的代码 +
上次标记存疑的地方，并把新发现的问题修掉。

验证手段（本机 aarch64，aapt2 用 qemu 包装）：

```bash
./gradlew test -Pandroid.aapt2FromMavenOverride=/opt/aapt2-qemu/aapt2     # 全模块 JVM 单测
./gradlew compileGithubDebugAndroidTestKotlin -Pandroid.aapt2FromMavenOverride=/opt/aapt2-qemu/aapt2
./gradlew spotlessCheck -Pandroid.aapt2FromMavenOverride=/opt/aapt2-qemu/aapt2
python3 -m unittest discover -s scripts -p "test_*.py"                    # 69 例，全过
cd web-ui && npx react-router typegen && npx tsc --noEmit                  # 类型检查通过
```

结论速览：

| 类别 | 数量 | 状态 |
| --- | --- | --- |
| 数据丢失 / 隐私 | 3 | 已修 |
| 安全（Web 服务、文件写入） | 3 | 已修 |
| 功能缺陷（崩溃、卡死、重试风暴、误删） | 8 | 已修 |
| 观测/清理/格式 | 3 | 已修 |
| 新增测试 | 4 个文件 / 9 例 | 全过 |

---

## 1. 数据丢失与隐私

### 1.1 换机/重装恢复聊天会把备份里的记忆删光（P0）

`app/src/main/java/me/rerere/rikkahub/data/sync/PartialMemoryRestore.kt`

新装机时 `getDatabasePath()` 还不存在 → `latest.isFile == false` → `sourceMemories = emptyList()`；
而 CHATS 类型 `preserveCurrentMemory = true`，`merged` 是备份数据库的副本，随后
`deleteAllFiles()` 把这个副本自带的 `memory_file` 行删掉再 upsert 空列表。触发条件正好是备份的唯一场景。

修法：只有存在「本机记忆来源」（`memory.json` 或本机库快照）时才做记忆搬运，否则让备份库原样通过。

### 1.2 整理记忆会把别的助手的聊天一起发出去（P0）

`ConversationDAO.historySources` / `historySourcesUpdatedSince` 没有 `assistant_id` 条件，
而记忆库 scope 就是助手 id：给助手 A 整理记忆时会把所有角色卡的私人聊天发给模型并写进 A 的库，
之后在 A 的对话里被召回注入，与「助手库与全局库互不可见」的设计矛盾。

修法：两条查询加 `(:owner IS NULL OR assistant_id = :owner)`，`prepareIncremental` 与
「从历史重新整理」选择器只在 `GLOBAL_MEMORY_ID` 时传 `null`。

### 1.3 导入文件里的文件名可以把写入带出目标目录（P1）

`FileUtils.buildUuidFileName` 只取 `substringAfterLast('.')` 当扩展名，`displayName` 可能来自
导入的备份包或远端 MCP 配置：`x./../../datastore/settings.preferences_pb` 会得到
`<uuid>./../../datastore/settings.preferences_pb`，规范化后落在 `filesDir/datastore` 里。

修法：扩展名只接受 `[a-z0-9]{1,8}`，否则退 `bin`；`FilesManager.createTargetFile` 再加一道
canonicalPath 前缀断言。

---

## 2. 安全

### 2.1 上传文件按客户端 MIME 内联回传 = 同源存储型 XSS（P1）

`web/routes/FilesRoutes.kt` 的 `/files/id/{id}` 与 `/files/path/{...}` 直接把 `Content-Type`
设成客户端给的 MIME，没有 `nosniff`、没有 `Content-Disposition`；前端把访问令牌放在
`localStorage`，一个上传的 `.html` / `.svg` 在该源下执行就能读走令牌、导出全部会话。

修法：统一走 `respondUserFile()`——加 `X-Content-Type-Options: nosniff`，
`text/html`、`image/svg+xml`、`*/xml`、`*/javascript` 改 `application/octet-stream` + `attachment`，
其余仍 `inline` 以便预览。

### 2.2 口令限流表只增不减（P2）

`web/WebApiModule.kt` 的 `AuthAttemptLimiter` 只在同一个来源再次出现时清理，换着 IP 打会把表撑大。

修法：加 `pruneExpired()`，每个窗口期扫一次全部 key。

### 2.3 JWT 校验硬比较 `iat`，时钟回拨即 401（P2）

`web/WebJwt.kt`：手机被 NTP 回拨后，刚签发的 token 立刻 `nowSeconds < iat`。

修法：`iat`/`nbf` 允许 120s 偏差（`exp` 仍然严格）。

---

## 3. 功能缺陷

### 3.1 大图 PNG 的角色卡数据读不到（P1）

`utils/PngTextChunkReader.kt`：遇到 `length > 8MB` 直接 `break`，而 4K 立绘的 IDAT 常超过这个值，
后面的 `tEXt`/`iTXt` 全部丢失，导入表现为「没有角色数据」。

修法：超大块用 `skipFully` 跳过继续扫描；另加 4M 字符总量上限，避免多块文本堆满堆。

### 3.2 断点续传在长度未知时把已下好的文件判成「不完整」并删除（P2）

`utils/UpdateDownloadEngine.kt`：`total = range.total ?: (range.start + contentLength())`，
`contentLength()` 为 -1 时 `total` 变成 `start - 1`，写完必然 `written != total`，
可续传文件被删掉。

修法：长度未知保留 `-1`，只在 `total > 0` 时做等值校验（原逻辑已是这样）。

### 3.3 取消下载后通知卡死、立刻重试会有两个协程写同一个 APK（P2）

`utils/UpdateChecker.kt` / `UpdateDownloadService.kt`：`cancelDownload()` 立即
`downloadJob = null` + `stopService`，服务来不及渲染「已取消」，进度通知永久停住；
同时 `startDownload` 用 `isActive` 判断，取消后立刻重试会让新旧两个协程同时写 apk 与 `.meta`。

修法：`finishCancelled()` 先发结果通知再停服务；`cancelDownload` 等旧 Job `join()` 后才置空；
`startDownload` 改成 `downloadJob != null` 才拒绝。

### 3.4 自动备份失败不推进检查时间 → 每次前后台切换都重跑整包上传（P1）

`mate/MateAutoBackupWorker.kt`：失败只置 `hadFailure`，不写 `lastCheckedAt`，`isDue` 永远为 true。

修法：失败时也写 `checked(lastCheckedAt)` 与可读错误；成功路径无条件清空旧错误
（原来 `fingerprint == null` 会把上次的错误字符串留着）。

### 3.5 恢复输入流没有上限，先写满 /data 再报空间不足（P1）

`data/sync/BackupManager.kt`：`stageRestoreFromStream` 无条件整包复制到 cacheDir，空间预检在之后。

修法：复制时按 `usableSpace - margin`（上限 32GB）逐块校验并支持取消。

### 3.6 明文凭据 zip 留在 cacheDir（P1）

本机导出 `includeCredentials = true`，临时包原来建在 `cacheDir`：进程被杀时 catch 里的 delete 不执行，
含 API Key 的 zip 长期留着，或反过来被系统清理抽走正在上传的包。

修法：临时包与暂存目录改到 `noBackupFilesDir/backup-tmp`，进入时清理 6 小时前的残留。

### 3.7 旧格式备份包（无清单）完全跳过空间预检（P2）

`BackupManifest.verifyDetailed` 允许没有清单的包，但预检只写在 `entries?.let { }` 里。

修法：`entries == null` 时按 zip 条目大小估算。

### 3.8 保留策略可能删掉最新的自动备份（P2）

`data/sync/BackupRetention.kt` 只按服务端 `Last-Modified` 排序，S3 缺字段/WebDAV 解析失败会退成
`Instant.EPOCH`，最新那份被排到队尾删掉。

修法：先按文件名里的 `yyyyMMdd_HHmmss`（定宽、字典序即时间序）排序，服务端时间与名字做兜底。

### 3.9 任务行里的来源列表损坏会让整理永远停在「正在整理」（P1）

`HistoryMemoryManager.run` 的 `Json.decodeFromString` 在 try 之外，异常穿出 Worker，
WorkManager 判失败但任务行仍是 `running`。

修法：解析失败落 `fail(READING_HISTORY)` 并返回。

### 3.10 导入 `readUri` 无上限，选错文件直接 OOM（P2）

`data/export/ExportSerializer.readUri` 整份 `readText()`。

修法：64M 字符上限，超限给出可读错误。

### 3.11 WebDAV 下载只等 `isClosedForRead`（P2）

`data/sync/webdav/WebDavClient.downloadToFile` 丢弃 `readAvailable` 的 `-1`，标志未置位时空转到被杀进程。

修法：收到 `-1` 立即结束。

### 3.12 冲突判定把「不过/不同/不错」当成否定（P2）

`data/ai/memory/MemoryTriage.kt` 的否定词含单字「不」「没」「未」，同主题候选被判成互相矛盾，
批量按建议永远只能逐条确认。

修法：多字否定词表 + 「带不/没/未但不是否定」词表，先把后者挖掉再判断单字。

---

## 4. 观测与清理

- `UpdateChecker` 的更新说明：`UpdateNotes.userFacing(body).ifBlank { body }` 会在抽不出用户条目时
  把整段 CI/gradle 正文塞回弹窗，改为退回 `summary`。
- `Version.parse` 不认 `v` / `V` 前缀：`V1.0.10` 三段全变 0，更新提醒再也弹不出来。已剥前缀并加测试。
- `UpdateDownloadEngine.isRetryableTransport` 里 `error !is UpdateCheckException` 是死判断（该类不是
  `IOException`），保留行为不再改动，此条只记录。

---

## 5. 验证过、确认没问题的部分（不再重复看）

- Room 迁移链 34→41 与 `app/schemas/*` 逐列一致，`AppDatabaseFactory` 注册与 autoMigration 缺口连续无遗漏。
- 备份 zip slip：`BackupManifest.validatePath` + `PendingRestore.resolveInside` + canonical 前缀三重校验；
  压缩炸弹有 32GiB / 10 万条目上限与逐条 CRC。
- `WebSettingsSanitizer` 脱敏覆盖字段名归一、`Pair` 与 `CustomHeader` 两种头形态，没有绕过。
- 前端 `code-block.tsx` 的 `dangerouslySetInnerHTML` 只吃 `escapeHtml()` / highlight.js 输出，无 XSS 点。
- 全仓无 `GlobalScope`、`Thread.sleep`、`System.exit`、空 `catch {}`。

## 6. 仍存疑（未改，附理由）

| 项 | 理由 |
| --- | --- |
| 记忆召回 `maxChars = 2 × tokenBudget` | 单位本身是「约」，中英混排下 2 字符/Token 是粗估；改成更紧会直接减少注入内容，需真机对比召回质量再定。 |
| `history_memory_seen` 不清理 | 清掉旧指纹会让旧内容重复计费；要加裁剪得先定「多久之前的指纹允许重复发送」。 |
| 整理暂停的 `saveJob` 快照写 | 写之前已经比对过 `dao.job(scope) == job`，剩余竞态窗口在两次挂起之间，需 DAO 级 CAS 才能彻底消除，改动面大于收益。 |
| `ChatList.topFadingEdge` 曲线 | 纯视觉取舍（标题带 15% 可见度），无法在无真机环境下判定，留给截图验收。 |
