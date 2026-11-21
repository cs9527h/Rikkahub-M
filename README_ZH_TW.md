# Rikkahub-M

**獨立開源 Android AI 用戶端｜多模型 API 直連｜本機助手與提示詞｜聊天記錄與備份**

**語言 / Language：[简体中文（預設）](README.md) · [English](README_EN.md) · 繁體中文**

> [!CAUTION]
> **v0.4.0 已發佈測試預覽版，但不是穩定版。** [版本說明](https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.4.0)警告，當時的安裝包仍可能在啟動流程中意外刪除舊聊天資料庫和備份目錄。**在完成遷移修復與實機安全驗收前，不建議安裝或啟動該版本。** 請保留原安裝及獨立備份。

## 專案介紹

Rikkahub-M 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**非官方獨立 Fork**，使用 Kotlin、Jetpack Compose 與 Material 3 Expressive。保留原生聊天、多模型供應商、助手設定、搜尋及其他適用的上游功能。

專案朝向**獨立 Android 用戶端**發展：手機直接連接使用者設定的模型 API，本機管理助手身分、提示詞注入、聊天歷史與長期記憶。舊 Mate/AstrBot 專用聊天閘道、配對、雲端人格注入及跨平台記憶依賴已從用戶端架構中退出。

## 功能與開發方向

- **多模型聊天**：沿用上游原生供應商與工具功能，實際支援範圍以目前版本與設定為準。
- **本機助手與提示詞**：自訂助手；持續驗證 Markdown、TXT、JSON 匯入、優先順序與新聊天生效。
- **聊天及長期記憶**：保留本機聊天歷史，完善記憶提取、搜尋、編輯、刪除與跨聊天召回。
- **備份與還原**：已有 WebDAV、S3 用戶端整合；可選的雲端伺服器計畫僅儲存備份，加密與跨裝置還原仍待驗證。
- **體驗最佳化**：持續改善啟動、長聊天、串流輸出、通知、UI 及動畫效能。

上述部分項目仍在開發或待驗收，**不代表目前 APK 已可靠支援全部功能**。

## 發佈與驗收狀態

[PR #16](https://github.com/cs9527h/Rikkahub-M/pull/16)已於 2026 年 11 月 20 日合併；v0.4.0 雖已發佈預覽版，但覆蓋升級安全、資料遷移、加密備份與實機端到端驗收仍未完成。

穩定版之前必須確認舊資料不會誤刪、助手與提示詞保持完整、WebDAV/S3 及加密備份可安全還原、聊天和通知正常，並沿用 `com.rikkahub.mate` 套件名稱、簽章與使用者資料，只發佈一個通用 APK。

> [!WARNING]
> 現有一般 ZIP 備份**不可視為經驗證的端對端加密備份**。請勿將敏感聊天與金鑰上傳至不可信的儲存空間。

## 相關連結

- [版本發佈](https://github.com/cs9527h/Rikkahub-M/releases)
- [更新日誌（簡體中文）](CHANGELOG_MATE.md)
- [獨立架構說明（簡體中文）](docs/STANDALONE_BACKUP_ONLY.md)
- [歷史問題與驗收清單（簡體中文）](docs/LEGACY_BUG_FIX_CHECKLIST.md)
- [上游 RikkaHub](https://github.com/rikkahub/rikkahub)

本專案並非 RikkaHub 官方作品。保留上游著作權與第三方聲明，遵守 [AGPL-3.0 授權](LICENSE)。禁止向公開倉庫提交私人聊天、人格原文、API Key 或伺服器密鑰。
