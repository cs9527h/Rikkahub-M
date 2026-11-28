# Lead 集成复核与发布边界

## 独立集成处置

- 保留远端同期README无图片改版及85项优化计划文档，快进合并至40399cc7；没有覆盖外部文档或把85项计划勾成完成。本轮范围为当前对话的既定修复/工程治理；长期计划仍逐项追踪。
- 月度机制分离提交b9b063b6；真实GitHub派发返回HTTP422后修正job级runner context，提交6fa35fe5；实际run46113426481成功，已下载状态及报告，0新增上游提交、无月分支/PR为正确无变化行为，不冒称正向PR已验证。
- AI streamFailure首次新回归失败不是直接放宽测试：lenient JSON把任意文本解析为primitive，默认provider错误现在要求JsonObject，否则保留HTTP状态失败并附解析cause；原回归保持。后续库统一回归exit0。
- 首轮MCP回归4条assertSame因协程stacktrace recovery复制异常而失败；测试须确认类型/message且cause identity链回原对象，同时保留不探测下一项、Response关闭和fatal不恢复断言。不关闭debug或把取消吞成Result以使测试通过。
- 日志页clipboard复制的RuntimeException catch增加取消先行重抛；原Binder过大提示保留，普通故障改稳定operation脱敏诊断。
- ShareSheet、ChatFloatingWindows外部URL启动、UIAvatar保存的诊断移除原始URL/path/Throwable message，保留类型/有界代码位置；未改头像保留/清理或悬浮窗逻辑。

## 已有阶段证据

- 第一批功能阶段1,656 JVM中1,655通过、PDF条件跳过1项，非最终1.1.7全部源码结果。
- 网页11项回归、TypeScript、oxlint通过；本轮库common/ai/speech/mediagen/ui冻结后复验exit0，含新增UI操作5项。
- 月度临时Git22项及真实context新增契约通过；后续全Python仍纳入发布前统一检查。
- 设置/媒体21项JVM与10项Compose回归源码已落，主源码/Android测试中间编译成功；实际CI instrumentation和新迁移SQLite行为尚待最终候选运行。

## 不可混淆的边界

- 用户跳过本次手机连接和实体设备验收；没有后续手机命令、跨通道重放、应用安装或读用户聊天。measured=false、metrics为空。
- 合成JVM/Android fixture不是实际设备性能样本；CI模拟器不是实体手机。本轮性能/长稳/文件删除实际耗时不写passed。
- 保留原发布校验器与build/upgrade/Lint/签名/ABI/哈希门禁，失败或未完成的自动化门禁不当作通过；只有新候选准确SHA运行结果可写最终验收。
- source/版本/CI必须冻结后统一验证；未运行的新增用例只列待执行。此文不是正式发布完成记录，最终结果见1.1.7技术说明。
