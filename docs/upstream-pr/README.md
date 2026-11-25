# 提交给上游 rikkahub/rikkahub 的补丁

分支已经推到 fork（`cs9527h/Rikkahub-M`），基于上游 `master`（`eb417de2`），每个分支只含 1 个提交。
本目录的 `.patch` 是同一份改动的离线副本（`git am` 可直接应用）。

自动建 PR 被 GitHub 拒绝（见文末原因），**一键建 PR 的链接**如下（打开后点 “Create pull request”）：

| # | 内容 | 分支 | 建 PR 链接 |
| --- | --- | --- | --- |
| 1 | 流式解码容忍非 JSON 心跳行与未知 annotation | `pr/stream-nonjson-lines` | https://github.com/rikkahub/rikkahub/compare/master...cs9527h:Rikkahub-M:pr/stream-nonjson-lines?expand=1 |
| 2 | Google `listModels` 逐字段空安全 + 失败分支关闭连接 | `pr/google-listmodels-null-safety` | https://github.com/rikkahub/rikkahub/compare/master...cs9527h:Rikkahub-M:pr/google-listmodels-null-safety?expand=1 |
| 3 | DOCX/PPTX 列表层级限幅（畸形文档 OOM） | `pr/docx-indent-clamp` | https://github.com/rikkahub/rikkahub/compare/master...cs9527h:Rikkahub-M:pr/docx-indent-clamp?expand=1 |
| 4 | Step ASR 4xx 不再重试，错误消息不带响应体 | `pr/asr-no-retry-4xx` | https://github.com/rikkahub/rikkahub/compare/master...cs9527h:Rikkahub-M:pr/asr-no-retry-4xx?expand=1 |

## 为什么没有脚本直连建 PR

`gh pr create` 与 GraphQL `createPullRequest`（带显式 `headRepositoryId`）都返回：

```
FORBIDDEN: cs9527h does not have the correct permissions to execute `CreatePullRequest`
```

`compare` API 能正常解析这些分支（`status=ahead, ahead_by=1`），说明分支没问题；这是 GitHub 对该 fork
网络里建 PR 的权限限制（通常与 fork 的创建方式/账号权限有关），需要账号所有者在网页上点一次。

## 每个补丁验证了什么

- 都基于上游当前 `master`（`eb417de2`），只改 1 个文件（#3 改 2 个），无格式改动；
- 复现路径在各自提交信息里写明：SSE 心跳行、兼容接口缺字段、`ilvl` 极大值、4xx 重试。

## 不打算提交上游的改动

本地 fork 的大量改动（Web 服务、工作区/终端、加密备份→明文备份、宽屏适配、启动串行化等）属于
Rikkahub-M 的产品决策，与上游定位不同，不逐条提交。
