# 月度上游审查机制实施与验证

本页是实现验收记录；首次实际月度分支运行后，脚本会在该隔离分支用逐提交决策台账替换本页。当前**未声称真实GitHub Actions/PR已运行成功**。

## 已完成

- [工作流](<../../.github/workflows/upstream-review.yml>)：每月1日UTC01:00（中国09:00）及workflow_dispatch；默认分支checkout，串行并发，contents/pull-requests两项job写权限，无自动批准、合并、发布。
- [计划/应用脚本](<../../scripts/upstream_review.py>)：固定上游URL、完整SHA、clean-pick隔离分支、重复月分支停止、原子create-only lease；已审baseline人工维护。
- [独立状态](<../upstream-sync-state.json>)：初始已审SHA为c08d720036857445038249220a9168009e3160a0，与原22项取舍一致；自动计划及候选应用不推进基线。
- 逐项分类：非breaking fix且文本源码白名单才候选；feature/merge、CI/build/dependency/version、symlink/gitlink/binary/executable、异常路径和本地持久化/会话/workspace不变量全部人工；较早人工项的同路径后续fix也人工。
- 冲突立即abort、阻断后续候选；保留先前clean-pick与冲突证据，审查PR为draft，该任务仍失败。非隔离分支、dirty、HEAD变化、旧Git操作、基线非祖先均停止。
- 无上游脚本/构建执行。所有git/gh为argv，无shell=True；不可信标题/路径转义，非法UTF-8路径保守人工；失败诊断不作为Actions命令输出。
- Markdown/JSON台账、status.json（真实返回的PR URL或明确error）、always summary/artifacts；PR无权限或返回异常时明确失败，既有月分支不被重试覆盖。

## 实际本地验证

命令：`python3 -B -m unittest discover -s scripts -p test_upstream_review.py -v`

- 首跑18项：17通过，1个orphan历史fixture的`git rm`因继承暂存索引失败；未掩盖，已修测试清空该临时索引。
- 中间21项：全通过，6.498秒。
- 最终22项：全通过，7.167秒，exit 0。
- 测试用真实临时工作仓库和bare origin；Git init/commit/cherry-pick/push/abort确实执行，但仅在自动清理的临时目录。未修改本项目Git历史、未联网。
- 覆盖：同基线无变更、fix/feature/breaking与保护路径、merge/binary/软链/非法文件名、前序人工依赖、冲突恢复/后续阻断、dirty/非隔离/HEAD变化、已有本地/远端分支、patch等价与重复运行、恶意标题/路径、机器状态、PR权限拒绝的完整CLI失败artifact及正常URL返回。
- GitHub PR调用在测试中模拟，不能替代真实远端权限验证。
- 已有PyYAML解析工作流，并校验cron/手动入口/job权限/串行并发/always artifacts/state基线：通过，exit 0。
- `git diff --check`限定本任务文件：通过。未安装额外依赖、未运行Gradle或formatter。

## Lead实际部署检查点

- 已独立提交并推送机制提交`b9b063b6`，没有夹带未完成的应用代码；保留远端同期README与质量计划文档。
- 第一次真实workflow_dispatch被GitHub明确以HTTP 422阻断：job级env中`${{ runner.temp }}`不在允许的表达式context。未产生成功运行，不将此记录为验收通过。
- 已改为step中从`RUNNER_TEMP`写入`GITHUB_ENV`初始化目录，并增加对应Python契约回归；待修正提交推送后再次真实派发。原22项真实Git回归保留。

## 仍待Lead真实部署验证

1. 统一提交/推送后，对新workflow执行真实workflow_dispatch，保留run URL和artifact。
2. 若当前仍与已审基线同值，应无分支/无PR；此结果只验证无变化路径。新增上游提交出现时，另验create-only月分支、draft PR和台账。
3. 仓库需允许Actions创建PR；job token仍仅contents/pull-requests写权限。权限缺失时应看到非零失败及status.error，不能记作创建成功。
4. 本地未验证GitHub定时器实际调度、bot凭据、分支保护、PR开关或actionlint。工作流不执行上游测试，人工仍须显式运行完整回归与既有发布门禁。

详细操作和分步检查点见[同步台账](<../upstream-sync.md>)，上方22项取舍原文保持完整。
