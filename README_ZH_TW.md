# Rikkahub-M

**獨立 Android AI 用戶端｜模型 API 直連｜本機人格與記憶｜雲端備份**

> **0.4.0 正在開發，尚未正式發佈。** PR #16 仍須修復編譯問題及實機驗證；本文不表示所有功能都已完成。

Rikkahub-M 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的非官方獨立 Fork，保留 Kotlin / Jetpack Compose / Material 3 Expressive、原生聊天、動畫、多模型、搜尋、MCP 和其他可用功能。

## 新架構

- 手機直接連接模型 API；人格提示詞、聊天、長期記憶和多助手設定由本機管理。
- 伺服器只負責備份儲存。用戶端已有 WebDAV/S3 功能，計畫評估 S3 相容物件儲存。
- 移除舊 Mate/AstrBot 聊天網關、配對碼、雲端人格注入與跨平台記憶依賴。

## 0.4.0 待修復

- 修復編譯與舊網關殘留引用，保留使用者既有設定。
- 驗證 Markdown/TXT/JSON 本機提示詞注入，完善本機記憶管理與跨會話召回。
- 高頻設定備份與獨立低頻聊天備份；預設不自動備份附件。
- 實作含 API Key 的加密備份及跨裝置恢復，測試 S3/WebDAV。
- 修復長聊天、串流、通知身份及動畫效能。
- 保留套件名稱 `com.rikkahub.mate`、既有簽章與資料，最後只發行一個通用 APK。

目前的 ZIP 備份**未經驗證具有端對端加密**，敏感資料請勿上傳至不受信任的儲存空間。

[用戶端 PR #16](https://github.com/cs9527h/Rikkahub-M/pull/16) · [舊功能 PR #15](https://github.com/cs9527h/Rikkahub-M/pull/15) · [問題清單](docs/LEGACY_BUG_FIX_CHECKLIST.md) · [版本發佈](https://github.com/cs9527h/Rikkahub-M/releases)

本專案為非官方 Fork，保留上游著作權及現有 [AGPL-3.0 授權](LICENSE)。不得公開真實人格、私人聊天或密鑰。