# 0.4.1 完整验收：真实失败与下一批修复

## 已执行的第一批完整检查（f8779613）

- 预览 run 44882701252、禁止发布的签名 run 44882720223 均已实际执行。
- OAuth workflow 权限已生效；签名工作流成功读取已配置的仓库签名 secrets。没有要求用户提供 Token、口令或私钥。
- Kotlin/JVM：AI 312 测试，0 失败；应用 665 测试，0 失败、1 个已有条件跳过（需要外部 DeepSeek 私人归档的可选测试）。通过数为 312 + 664，不能把 665 写成全部通过。
- 两个作业都在 Android Lint 停止，不是 JVM 测试失败，也不是签名权限失败。实际 129 errors：NewApi 3、NonObservableLocale 1、ContextCastToActivity 2、LocalContextGetResourceValueCall 56、MissingTranslation 67。
- 因任务顺序，完整仪器测试与签名 APK 构建尚未执行，不能沿用旧提交的 18 例结果充当本批通过。
- 失败报告上传已验证有效；从实际构建 artifact 提取 Room 35.json，逐表比较 34→35，只有 memory_file 增加 deleted_at（NOT NULL DEFAULT 0），已保留真实导出文件，没有手工伪造 schema hash。

## 接续修复

- 补真实 Room MigrationTestHelper 的 34→35 完整 schema 验证：保留 1000 个消息节点、2000 条合成消息、原提示词与两个独立记忆命名空间；同时记录迁移前后 50 行窗口 SQL 查询的中位数/P95、Android API/机型/ABI、进程 PSS。仅为数据库查询微基准，不是 UI 帧率、流式输出或真机性能验收。
- 完整仪器测试 wrapper 保留 Gradle 失败码，即使日志收集失败也不会变绿；缺少要求的微基准输出同样失败。只收集专用合成测试日志标签。
- 覆盖升级脚本原先忽略 signed 参数，查找 candidate 而非 dist 目录，并启动 .debug 包；现按模式选目录和包名，signed 为同包 com.rikkahub.mate、adb install -r。启动/窗口/测量缺失由警告改为失败。
- signed 升级在一次性 CI 模拟器内写入合成私有文件与旧备份目录哨兵；不存在旧数据库时才创建合成 SQLite，不覆盖已有旧库；对旧库/已有 WAL/SHM 和哨兵逐一比较升级前后 SHA-256。禁止 uninstall/clear-data，版本必须递增到 code16。这个证明范围不等于所有原生聊天内容的语义迁移。
- 修正完整仪器测试暴露前可确定的旧包名断言，保持明确检查 com.rikkahub.mate(.debug)。
- 记忆仓库禁止新建/无版本写入静默覆盖同路径回收站内容；先恢复或明确永久删除后才能复用，补完整恢复与跨命名空间测试。
- 通知身份读取最新会话而非过期数据库/不相关当前助手；读取取消正确传播，不再吞掉取消后启动前台服务。
- SDK 30 截图/回车检查改为 Lint 可识别的显式异常分支；统计月份读取响应式 Locale；聊天 Activity 从 LocalActivity 获取，不强转 LocalContext。
- 原可选私人归档测试改用合成分支归档，保留原有全档文本、分支与唯一 ID 断言，不再依赖私人导出或条件跳过。

## 检查顺序调整，不降低门槛

Lint 仍是必需检查，错误仍使构建及整体 CI 失败，不创建 baseline、不禁用错误、不使用 continue-on-error。将其放在完整测试和候选 artifact 生成之后，以便修复翻译/资源问题时仍能独立收集签名与数据保留证据。只有签名/版本验证及 artifact 上传成功才允许进入诊断性的覆盖升级；publish 额外明确要求 build 与 upgrade 都 SUCCESS，并执行精确候选真机验收验证器。因此 Lint 红灯不能发布稳定版。

## 新批本地证据与待办

- Python 门禁、升级数据契约和测试 wrapper：39 测试通过（合成执行器测试，不是 Android 升级通过）。
- 两个工作流 actionlint、两个脚本 bash -n 通过；Kotlin 修改经 ktlint；本批 Android 原生结果必须由新 run 确认。
- 尚须实际修完 Lint 中的配置响应式资源调用与缺失翻译，不将这些可修代码问题归为外部阻塞。
- 当前仍没有可交付的本批已验证签名 APK、完整覆盖升级通过记录、跨设备实机恢复与 UI/流式长聊天帧率记录。PR 保持草稿，禁止稳定发布。
