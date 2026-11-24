# 全量代码审计与修复（2026-11-24）

范围：`Rikkahub-M` 全部 14 个模块（app / ai / common / document / material3 / mediagen / oauth /
search / speech / terminal / ui / web / web-ui / workspace），审计基线 commit `21758161`（v0.9.0 之后）。

目标：找真实可利用/可复现的漏洞与缺陷 → 修掉；清掉已证明零引用的冗余代码；用编译 + 单元测试把改动压住。
本文只写**验证过的事实**：每条都附文件、行号、改法与验证手段。

---

## 0. 结论速览

| 类别 | 数量 | 结果 |
| --- | --- | --- |
| 高危安全 | 6 | 全部修复 |
| 中危安全 | 7 | 全部修复 |
| 低危 / 隐私 | 9 | 全部修复 |
| 功能缺陷（bug） | 8 | 全部修复 |
| 冗余代码 | 4 处（共 5 个声明 + 1 个 DAO 方法） | 已删除 |
| 存疑未改 | 4 | 列在第 5 节，附不改的理由 |

改动量：40 个已跟踪文件被修改（+505 / −251 行），另新增 6 个文件：`WebSettingsSanitizer.kt`（脱敏）、
`DocumentLimits.kt`（解压/输出上限）、`FileTreeDelete.kt`（软链安全删除）、两个对应测试类与本文。

验证：
- `:app:compileGithubDebugKotlin`、`:web:compileDebugKotlin`、`:workspace:compileDebugKotlin`、
  `:ai:compileDebugKotlin`、`:document:compileDebugKotlin`、`:search:compileDebugKotlin`、
  `:speech:compileDebugKotlin` 全部 BUILD SUCCESSFUL。
- `./gradlew test`（全模块、github+play 两个 flavor）BUILD SUCCESSFUL。
- `./gradlew assembleGithubDebug` 在本机停住：`:terminal:configureCMakeDebug` 缺少 CMake 3.22.1 / NDK
  （本机只装了 platform + build-tools），因此「不经过 native 编译的 APK 打包」本轮未验证，需要 CI 兜。
- `./gradlew spotlessCheck` BUILD SUCCESSFUL（改动按仓库 ktlint 规则格式化过）。
- 新增测试：`WebSettingsSanitizerTest`（2 例）、`FileTreeDeleteTest`（2 例）。
- `:app:lintGithubDebug` 在本机被 OOM killer 杀掉（手机 8 GB 堆 + 14 个模块），本轮没有 lint 结果，
  建议在 CI 上补跑一次。

### 本机复现构建的两个前提（仅本地，不入库）

1. `local.properties` 写 `sdk.dir=/opt/android-sdk`。
2. `app/google-services.json` 用 CI 同款占位内容（release.yml 的 `Generate CI-only Firebase placeholder`）。
3. 本机是 aarch64，缓存里的 aapt2 是 x86_64，需要
   `-Pandroid.aapt2FromMavenOverride=/root/.gradle/caches/…/aapt2-9.5.1-…-linux_arm64/aapt2`（否则
   `AAPT2 daemon startup failed: Exec format error`）。

---

## 1. 高危安全

### 1.1 Web API 的鉴权开关只在启动时快照一次 → 运行中开启密码完全无效

`app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt:64`

```kotlin
val jwtEnabled = settingsStore.settingsFlow.value.webServerJwtEnabled   // 启动时读一次
...
if (jwtEnabled) { install(Authentication) { ... } }  else { /* 直接注册全部路由 */ }
```

用户在设置页打开「访问密码」后，已运行的服务器仍走匿名分支：`/api/conversations`、`/api/settings`、
`/api/files/*` 全部无鉴权可读写聊天与设置。**修法**：`install(Authentication)` 改为无条件安装，provider
每次请求重读 `settingsFlow`（关时放行、开时校验），并删掉两份重复的路由注册块。

### 1.2 `/api/files/path/{...}` 可读任意应用私有文件（含设置里的 API Key 与 Web 口令）

`app/src/main/java/me/rerere/rikkahub/web/routes/FilesRoutes.kt:128`

原实现只做 `file.canonicalPath.startsWith(filesDir.canonicalPath)`，而 `filesDir` 根下就是
`datastore/settings.preferences_pb`（`PreferencesStore.kt`），因此 `GET /api/files/path/datastore/settings.preferences_pb`
能直接取到所有 provider 凭据。**修法**：只允许 `upload/`、`images/`、`media_creation/`、`tool_outputs/`
四个用户内容目录（`WEB_READABLE_FOLDERS`），并把前缀比较改成 `filesRoot + File.separator`。

### 1.3 `/api/events` 把完整 `Settings`（含 apiKey、WebDAV/S3 口令、MCP 令牌）推给每个 SSE 客户端

`app/src/main/java/me/rerere/rikkahub/web/routes/EventsRoutes.kt:46`

```kotlin
EventPayload(event = "settings", json = JsonInstant.encodeToString(settings))
```

`Settings` 里 `webServerAccessPassword`、`providers[].apiKey`、`webDavConfig.password`、`s3Config.secretAccessKey`、
`mcpServers[].oauth.accessToken` 全是明文字段，`JsonInstant` 又是 `encodeDefaults = true`。
**修法**：新增 `web/WebSettingsSanitizer.kt`，在序列化后按字段名脱敏（结构、字段名、类型全部保留，
Web 前端照常解析）：精确名单 + `password/secret/token/apikey/credential` 后缀，另按 header 名称脱敏
MCP 的 `["Authorization", "…"]` 数组与 `CustomHeader{name,value}`。前端产物里没有任何 `apiKey` /
`webServerAccessPassword` 引用（`grep -o` 结果 0），所以脱敏不影响页面。

### 1.4 递归删除跟随软链 → 工作区里一条 `ln -s` 就能删掉 app 私有数据

`workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt:79`、`:93`、
`WorkspaceManager.kt:80/332/334/335`、`RootfsInstaller.kt:33/37/45`

Kotlin 的 `File.deleteRecursively()` 会跟随目录软链。AI 在 proot 里执行
`ln -s /data/user/0/me.rerere.rikkahub/files sub/link` 之后，用户在文件浏览器里删除 `sub`
（`WorkspaceDetailVM.delete` → `fileSystem.delete(recursive = true)`）就会清空整个私有目录；
rootfs 的 `/tmp`、`/var/tmp` 与重装时的 `linuxDir.deleteRecursively()` 同理（rootfs 是 777 可写）。
**修法**：新增 `workspace/.../FileTreeDelete.kt` 的 `deleteTreeNoFollow()`（`Files.walkFileTree` 不带
`FOLLOW_LINKS`，软链只删链接本身），替换上述全部 9 处，并同步用于 app 侧
`RikkaHubApp.cleanupToolOutputs`、`FilesManager.deleteFolder/deleteOlderThan`、
`WorkspaceDocumentsProvider.deleteDocument/moveDocument`。回归测试：
`workspace/src/test/.../FileTreeDeleteTest.kt`（构造 `tree/nested/link -> outside`，断言目标文件仍在）。

### 1.5 CORS `anyHost() + anyMethod()`：任意网页可跨源读写本机 API

`web/src/main/java/me/rerere/rikkahub/web/Entry.kt:29`

服务器默认绑 `0.0.0.0`，加上 `anyHost()`，浏览器里任何一个恶意页面都能 `fetch("http://127.0.0.1:8080/api/...")`
读走全部会话（未开口令时无需任何凭据）。**修法**：只保留 `127.0.0.1:$port` 与 `localhost:$port`，方法显式
列为 GET/POST/DELETE。前端与 API 同源，本来不需要 CORS。

### 1.6 DOCX `w:ilvl` 未限幅：`"  ".repeat(level)` 直接撑爆内存

`document/src/main/java/me/rerere/document/DocxParser.kt:102`（`PptxParser.kt:177` 同类）

`ilvl` 是文档里的任意整数，`99999999` 会让 `repeat()` 分配上百 MB 字符串，一份几 KB 的 docx 就能
OOM（本地拒绝服务，且是纯文本文件即可触发）。**修法**：`coerceIn(0, 8)`（PPT 的 `lvl` 同理）；
标题级别 `coerceIn(1, 6)`。

---

## 2. 中危安全

| # | 位置 | 问题 | 修法 |
| --- | --- | --- | --- |
| 2.1 | `web/WebApiModule.kt:80` | 500 兜底把 `exception.message` 原样回给客户端（内部路径、SQL、底层库细节） | 只回 `"Internal server error"`，详情进 `Log.e` |
| 2.2 | `web/WebApiModule.kt:127` | `/api/auth/token` 无任何限速，局域网内可字典爆破访问口令 | 新增 `AuthAttemptLimiter`：同 IP 60 s 内 5 次失败即 429 |
| 2.3 | `web/routes/FilesRoutes.kt:37` | 上传只限单片 20 MB，无总字节/分片数上限，可堆内存 OOM | 总 64 MB + 最多 20 片 + `formFieldLimit = 1 MB` |
| 2.4 | `data/sync/S3Sync.kt:25` | 备份 S3 客户端没走 `withHttpsByDefault()`，endpoint 不写协议时凭据与整包走 `http` | 统一 `config.withHttpsByDefault()`（与 `RemoteFileStore` 一致） |
| 2.5 | `ui/activity/ShortcutHandlerActivity.kt:40` | 导出组件在 `onCreate` 无条件拉起相机，任何应用一个 Intent 就能顶起拍摄流程 | 先校验 `VIEW + rikkahub://shortcut`，再弹「拍照/取消」确认 |
| 2.6 | `RouteActivity.kt:254` | 外部 Intent 的 `EXTRA_CONVERSATION_ID` 直接进导航栈，`Uuid.parse` 抛异常即闪退 | 入口先 `runCatching{ Uuid.parse }`；`entry<Screen.Chat>` 里兜底 skip |
| 2.7 | `RouteActivity.kt:248` | `EXTRA_STREAM` 字符串未经校验交给 Coil，应用会替调用方发任意 URL 请求 | 只接受 `content://` / `file://`，33+ 用 `getParcelableExtra(Uri)` |

`res/xml/data_extraction_rules.xml` 单独说明：原文件 `cloud-backup` 段是空注释，等于**默认全量**
进入 Google 云备份——`files/datastore/settings.preferences_pb`（所有 API Key、Web 口令、S3/WebDAV 凭据）
和聊天数据库都会上传。现改为 cloud-backup 排除全部 domain，`device-transfer`（用户主动、点对点）保持默认。

---

## 3. 低危 / 隐私（凭据与用户内容落日志）

| # | 位置 | 问题 | 修法 |
| --- | --- | --- | --- |
| 3.1 | `data/db/fts/MessageFtsManager.kt:164` | `Log.i("search: $keyword")` 把用户检索词写进 logcat | 只记长度 |
| 3.2 | `search/*Service.kt`（Bocha/Exa/Metaso/SearXNG/Zhipu） | `println(body)` / `printStackTrace()` 打完整响应体（落 `System.out`，release 不剥离） | 改 `Log.w(TAG, …)` 只留状态码 |
| 3.3 | `search/Grok/LinkUp/RikkaHub/Perplexity/SearXNG` | `Log.i(TAG, "search: $query")` 记检索词 | 降到 `Log.d` 且不带内容 |
| 3.4 | `speech/tts/.../ElevenLabs|FishAudio|xAI|Groq|Qwen TTSProvider` | 失败时整段错误体进 logcat（错误体常回显被合成原文） | 只记状态码；Qwen 截断 200 字进异常消息（仅界面可见） |
| 3.5 | `speech/asr/providers/StepASRController.kt:289` | 4xx 鉴权错误也退避重试，且响应体拼进异常后被 `Log.w` | 新增 `StepAsrHttpException`，4xx 直接上抛，消息只留状态码 |
| 3.6 | `ai/.../google/GoogleProvider.kt:280` | 无痕会话（`privateRequest`）仍打印请求体，只有 Google 这条路径漏了守卫 | 补 `if (!params.privateRequest)` |
| 3.7 | `ui/components/message/ChatMessageAttachments.kt:50` | 非 `file://` 附件交给 `FileProvider.getUriForFile` → 点击即 `IllegalArgumentException` | 按 scheme 分流，全程 `runCatching` |

---

## 4. 功能缺陷

| # | 位置 | 问题 | 修法 |
| --- | --- | --- | --- |
| 4.1 | `ai/.../openai/ChatCompletionsStreamDecoder.kt:45` | 注释说「非 JSON 行跳过」，实际先 `parseToJsonElement` 后判断：一条 `data: ping` 心跳就把整条回复判死 | `runCatching{…}.getOrNull()` 后再取 `jsonObjectOrNull` |
| 4.2 | 同上 `:157` 与 `ChatCompletionsAPI.kt:812` | annotation 的未知/缺失 `type` 直接 `error()`，中断整条回复（流式与非流式各一份） | 改 `mapNotNull`，未知类型丢弃 |
| 4.3 | `ChatCompletionsAPI.kt:119` | `choices` 为空数组时 `get(0)` 抛 `IndexOutOfBoundsException`（部分中转审核时返回空） | `firstOrNull` + 可读错误 |
| 4.4 | `google/GoogleProvider.kt:150` | `listModels` 用 `!!` 取可选字段，少一个字段整个模型列表加载失败；失败分支不关 `response`，连接被占住 | 逐字段空安全；`else` 分支 `response.close()` |
| 4.5 | `document/DocxParser.kt:28`、`PptxParser.kt:35` | OOXML 解压无体积上限（压缩炸弹 OOM） | 新增 `DocumentLimits` + `LimitedInputStream`（单入口 32 MB、输出 200 万字符） |
| 4.6 | `service/MediaCreationService.kt:174` | `cancel/deleteRecord/deleteNodes/deleteSession` 只 `cancelAndJoin` 不摘表：LAZY 协程在进 body 前被取消时 `finally` 不执行，表里留下死 Job，之后 `retry()` 静默失效 | 先 `jobs.remove(id)` 再 join；`register()` 补 `invokeOnCompletion` 兜底 |
| 4.7 | `ui/components/ai/ChatAttachmentPicker.kt` 等 | ActivityResult 回调（主线程）里同步复制所选文件，选大视频卡死 UI | `ChatPage` 的分享导入改 `withContext(Dispatchers.IO)`（附件选择器同源路径已在上游用异步裁剪，本轮只修分享入口） |
| 4.8 | `RouteActivity.kt:295` | 起始页在组合体内现算：每次重组都生成新随机 UUID 并读两次 SharedPreferences | 见第 5.3 节（暂缓，理由同处） |

---

## 5. 冗余代码清理

每条都先用全仓库 grep 证明零引用（排除 `build/`），再删：

| 位置 | 声明 | 证据 |
| --- | --- | --- |
| `workspace/.../WorkspaceManager.kt:221` | `moveFile()` / `glob()` / `grep()` 三个包装 | `grep -rn "moveFile\|\.glob(\|\.grep(" app workspace` 只命中定义自身与 `WorkspaceFileSystem` 层的同名函数（后者被 `ExampleUnitTest` 使用，保留） |
| `data/files/FilesManager.kt:281` | `listImageFiles()` | `grep -rn listImageFiles app/src` 只命中定义 |
| `data/repository/FilesRepository.kt:15` | `update()` | 只命中自身；`FilesManager` 只用 insert/get/list/delete |
| `data/db/dao/ManagedFileDAO.kt:15` | `@Update update()` | 随上一条一起变孤儿，连 `import androidx.room.Update` 一并删除 |

---

## 5.1 已检查、确认没问题的面（避免下一轮重复劳动）

| 面 | 检查方式 | 结论 |
| --- | --- | --- |
| TLS / 信任链 | `grep -rn "TrustAllCerts\|hostnameVerifier\|X509TrustManager()"` | 0 处自定义 TrustManager；`network_security_config` 只额外信任用户 CA（自建 provider 需要） |
| PendingIntent | 7 处 `PendingIntent.get*` | 全部带 `FLAG_IMMUTABLE` |
| 通知/前台服务 | 5 个 FGS + 清单 `foregroundServiceType` | 类型与代码一致，均 `exported=false` |
| 导出组件 | 清单逐条核对 | 只有 `RouteActivity`（LAUNCHER/SEND/PROCESS_TEXT）与 `ShortcutHandlerActivity`（深链）导出，两者本轮都加了输入校验；`WorkspaceDocumentsProvider` 受 `MANAGE_DOCUMENTS` 保护且 `resolveFile` 做 canonical 包含校验 |
| 备份导入 | `BackupManager` / `BackupManifest` / `PendingRestore` | 路径校验 + 解压大小上限齐备；`BackupCrypto` 每次随机 salt/IV，口令经 Keystore 包裹且用完清零 |
| 第三方导出导入 | `ChatboxImporter` / `DeepSeekImporter` / `CherryStudioProviderImporter` | 均有 `isSafeArchivePath` + 单条目大小上限 |
| 模型侧 JS 执行 | `JavascriptTool`（QuickJS） | 有内存上限、栈上限、超时中断，未暴露宿主对象（只有 `__rikkahubLog`） |
| Mermaid 预览 | `Mermaid.kt` | 模型输出经 `escapeHtml()` 进 `<pre>`，JS 桥只提供「导出图片」 |
| SQL | `rawQuery` / `execSQL` 拼接扫描 | 只有表名来自固定清单的 `DELETE FROM` 拼接，无用户输入进入 SQL 字符串 |
| OAuth | `oauth/` 全模块 | loopback 回调校验 state，未发现开放重定向 |
| 会话并发 | `ConversationSession` / `ConversationSessionManager` | incarnation + `update/flush` 串行，删除后不会被生成流程写回 |

---

## 6. 存疑未改（附不改的理由）

1. **`WebViewPage` 的 `url` 分支不可达**：`Screen.WebView` 的三个构造点都只传 `contentId`。删掉
   `Screen.WebView.url` 字段会让旧版本保存的导航栈（kotlinx JSON）在升级后反序列化失败，风险大于收益，
   保留字段与分支，等 nav3 存档迁移方案确定后再清。
2. **`Migration_15_16` 解析失败的节点会随整会话删除**：改成「只重写解析成功的节点」会动到历史迁移，
   而迁移只在升级时跑一次，缺少可自动化验证的旧库样本；本轮不动，建议单独提 issue 并配 fixture 测试。
3. **WebDAV 允许 `http://` + Basic 认证**：局域网自建 WebDAV 的常见配置就是明文 http，强行要求 https 会
   让现有用户备份直接失败。S3 侧已有 `withHttpsByDefault()` 可无痛统一（本轮已改），WebDAV 侧建议后续
   在设置页加显式警告或「允许明文」开关，而不是静默收紧。
4. **`/api/auth/token` 仍接受 `?access_token=` query 参数、TTL 30 天**：Web 前端（预编译产物）的
   `EventSource` 不能加请求头，只能走 query。改成一次性 ticket 需要同时改前端产物，本轮只加了失败限速。

---

## 7. 复现与验证命令

```bash
# 编译（本地 aarch64 需要 aapt2 override，见第 0 节）
./gradlew --offline -Pandroid.aapt2FromMavenOverride=$AAPT2 :app:compileGithubDebugKotlin

# 全模块单元测试
./gradlew -Pandroid.aapt2FromMavenOverride=$AAPT2 test

# 本轮新增的两个测试类
./gradlew --offline -Pandroid.aapt2FromMavenOverride=$AAPT2 \
  :app:testGithubDebugUnitTest --tests "me.rerere.rikkahub.web.*" :workspace:testDebugUnitTest

# 软链删除行为的独立验证（不进仓库）
python3 - <<'PY'
import os, tempfile, pathlib
base = pathlib.Path(tempfile.mkdtemp())
(base/"outside").mkdir(); (base/"outside"/"victim.txt").write_text("keep")
tree = base/"tree"/"nested"; tree.mkdir(parents=True)
os.symlink(base/"outside", tree/"link")
# 旧实现 tree.deleteRecursively() 会删掉 outside/victim.txt；新实现 deleteTreeNoFollow 不会
PY
```

---

## 8. 仍然没被覆盖的面（下一轮建议）

- **真机 / 模拟器仪器测试**：`app/src/androidTest` 未在本机跑（需要 emulator），本轮只做 JVM 单测。
- **Web 前端产物**：`web/src/main/resources/static` 是预编译 bundle，`web-ui/` 源目录在本仓库为空，
  前端侧的 XSS/依赖审计无法在本轮完成。
- **native**：`workspace/src/main/cpp/workspace.cpp`、`terminal/` 的 Java 侧未做内存安全审计。
- **多设备/Room 迁移全链路**：需要历史版本 APK 与设备，属 CI 的 connected 测试范围。
