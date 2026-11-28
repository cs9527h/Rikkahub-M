# Rikkahub-M

**面向 Android 的開源 AI 用戶端，自由選擇模型、靈活設定助手、掌控自己的資料。**

基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 獨立維護，採用 Kotlin、Jetpack Compose 與 Material 3 Expressive。

[简体中文](README.md) · [English](README_EN.md) · 繁體中文

[下載安裝](https://github.com/cs9527h/Rikkahub-M/releases) · [更新日誌](CHANGELOG_MATE.md) · [使用指南](docs/MATE_USER_GUIDE.md) · [回報問題](https://github.com/cs9527h/Rikkahub-M/issues)

<!-- RELEASE-BLOCK:BEGIN -->
> **最新正式版：v1.1.6**（2026-11-27，commit `cddb5bee`）
>
> 安裝包：[Rikkahub-M-1.1.6.apk](https://github.com/cs9527h/Rikkahub-M/releases/download/v1.1.6/Rikkahub-M-1.1.6.apk) · 18.0 MB · universal（arm64-v8a + x86_64）
>
> SHA-256：`a845185bc145efb777c1b8717ce557a25d1cff702e3fc19cbf37b4a8cf0b5813`
>
> 本版摘要：头像独立管理，修复图片归属延迟、头像替换与备份缺图。
>
> 完整說明見 [v1.1.6 更新日誌](docs/releases/1.1.6.md)。
<!-- RELEASE-BLOCK:END -->

## 功能

| 模組 | 體驗 |
| --- | --- |
| 模型接入 | 直連 OpenAI、Gemini、Claude 相容介面、DeepSeek、NewAPI 及自訂 API 位址，無需額外閘道 |
| AI 助手 | 自訂模型、頭像、人格與提示詞，支援 Markdown、TXT、JSON 規則匯入及注入順序設定 |
| 聊天體驗 | 工作階段搜尋、訊息分支、收藏、翻譯、朗讀、語音辨識及個人化背景 |
| 長期記憶 | 本地記憶擷取、整理、編輯與召回，支援獨立管理其他助手的記憶設定 |
| 檔案管理 | 依助手分類附件、搜尋、篩選、預覽、匯出及關聯保護清理；頭像獨立管理 |
| 文件預覽 | 圖片縮放、PDF 翻頁、文字與 Markdown，以及 DOCX、PPTX、EPUB 文字預覽 |
| 備份遷移 | 本機、WebDAV、S3 與自動備份，支援匯入原版 RikkaHub、Chatbox、DeepSeek、Cherry Studio |
| 應用程式更新 | 多來源備援下載，安裝前驗證 SHA-256、套件名稱、版本碼與簽章 |

## 資料與遷移

完整備份包含設定、聊天記錄、附件與 API 憑證，採用一般 ZIP 格式，無需密碼或還原金鑰。備份未加密，請妥善保管，勿公開分享。舊版加密備份已不受支援；本專案的完整備份不保證能還原至原版 RikkaHub。

## 專案說明

- 保留上游設計語言，不引入贊助、捐贈或推廣服務商入口。
- 獨立套件名稱 `com.rikkahub.mate`、簽章與發佈流程，提供 arm64-v8a + x86_64 通用 APK。
- 正式發佈經過自動化測試、Lint、簽章及覆蓋升級檢查。實機驗收與效能量測的限制見各版本說明。

## 更多文件

[歷史記憶](docs/HISTORY_MEMORY.md) · [與上游的差異](docs/RM_VS_RB.md) · [逐檔對照](docs/RM_VS_RB_FILE_DIFF.md) · [版本說明](docs/releases/) · [品質台帳](docs/QUALITY_LEDGER.md) · [備份架構](docs/STANDALONE_BACKUP_ONLY.md)

## 開源授權

採用 [AGPL-3.0](LICENSE)，保留上游 RikkaHub 版權與署名。修改與散佈須遵守相同授權。
