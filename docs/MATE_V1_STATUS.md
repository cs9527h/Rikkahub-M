# Rikkahub-M v0.1.0 状态说明

本文件随 Release 更新，只写已经落地的内容；未验证项单列。

## 已完成（代码 + CI 证据）

| 项 | 说明 |
|---|---|
| 底座 | 以 RikkaHub 原生 Kotlin/Compose 工程为基底（预留 Room、ChatService、多服务商、MCP、工作区等原能力），不是把 Mate 界面拼上去 |
| 应用身份 | `applicationId = com.rikkahub.mate`；显示名 `Rikkahub-M`；Kotlin namespace 保持 `me.rerere.rikkahub`，避免反射/序列化/数据库路径被打断 |
| 默认助手 | 全新安装只创建一个内置助手；已删除的内置助手不会被恢复，选择会自动迁移到剩余助手 |
| 广告与推广 | 赞助、捐赠、邀请返佣、聚合网关预设已移除；`SettingsNormalization` 只清理未配置的推广预设，带 token 的自建服务商不动 |
| 联系方式 | 关于页、设置页统一指向 `https://qm.qq.com/q/Yvc3ugiCoE`；上游 RikkaHub 的 AGPL 许可与版权声明保留 |
| 服务器入口 | 设置页可进入「连接李鱼服务器」，支持地址、配对码、二维码、设备名、状态与设备管理（见 `app/src/main/java/me/rerere/rikkahub/mate/`） |
| 聊天 | Mate 网关作为独立后端接入，SSE 流式、历史持久化、请求 ID 断线恢复 |
| 构建 | `Mate V1 preview build` 在 `feat/mate-v1-integration-20261119` 上全绿（含 553 项单测、web-ui 构建、APK 打包） |
| 签名 | 发布 APK 用长期密钥签名，私钥只存在 GitHub Actions Secrets（`MATE_KEYSTORE_BASE64` 等 4 项），仓库内无任何密钥 |

## 尚未完成 / 未验证

- 真机安装与启动冒烟、真实服务器配对、QQ 通道联调：本执行环境无 Android 设备，未测。
- 冷启动/长聊天帧时间与内存：无设备与 KVM，未采集，不填百分比。
- 记忆管理（查看/搜索/编辑/删除/审计/跨端同步）：服务端权限模型未授权，只保留既有只读入口。
- WebDAV/S3 双设备恢复：未实测。
- 旧 Mate 数据库迁移器：未实现（新工程 schema 不同，需要独立的可预览迁移流程）。
- 多语言文案：基础 Mate 文案已本地化，其余沿用上游翻译。
