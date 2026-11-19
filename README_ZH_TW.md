<div align="center">
  <h1>Rikkahub-M · Mate</h1>
  <p><strong>保留 RikkaHub 的原生體驗，逐步將李魚整合進 App。</strong></p>
  <p>原生 Android AI 用戶端 · 自訂模型 · AstrBot／李魚專屬連接</p>
  <p><a href="README_ZH_CN.md">简体中文</a> · 繁體中文 · <a href="README.md">English</a></p>
</div>

> [!IMPORTANT]
> **目前仍在重構。** 「連接李魚」入口、一次性配對與網關驗證已在開發分支提交原始碼，但**尚未接入李魚實際聊天**。伺服器記憶、多裝置同步、舊 Mate 資料匯入及穩定 APK 尚未完成。請保留原有 Mate App 與資料。

## Mate 是什麼？

**Mate 是 App 名稱，Rikkahub-M 是開發倉庫。** 以 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 Kotlin／Jetpack Compose 原生架構為基礎，保留聊天畫面、動畫、多模型 API、Markdown、搜尋、助手、MCP、Skills 與備份等功能，再逐步加入 Mate 原有的 **AstrBot 私人網關、李魚人格、伺服器記憶、裝置配對與跨端同步**。

獨立 API 與李魚伺服器連線會是不同模式，避免互相覆寫設定。

## 功能與進度

| 功能 | 狀態 |
|---|---|
| 原生 AI 聊天、模型管理、Markdown 與現有上游工具 | RikkaHub 底座功能 |
| 「連接李魚伺服器」入口與 HTTPS 協定 3 配對 | 已提交原始碼，待編譯與實機驗證 |
| 李魚流式聊天與服務端歷史記錄 | 開發中 |
| 李魚人格、長期記憶與自然成長 | 開發中，須驗證服務端授權 |
| QR 配對、裝置管理、跨端同步與備份 | 規劃中 |
| 舊 Mate 無損匯入、穩定簽名 APK | 尚未完成 |

RikkaHub 本機記憶與 AstrBot 伺服器記憶是不同系統，不會在未確認權限時合併或刪除。

## 如何開始

1. **獨立 API：** 在「快速開始 → 獨立 API」新增提供商及模型。
2. **李魚：** 在「快速開始 → 李魚 · Mate」或設定頁開啟配對頁。**目前配對不等於已接通聊天。**
3. **舊 Mate：** 暫勿解除安裝；新舊資料庫需要獨立的安全遷移程序。

## 設計原則

保留自然動畫並以真機效能測量改善卡頓。介面精簡、入口清晰、尊重資料與記憶所有權。**不展示廣告、贊助名單、返佣推薦或捐款彈窗。**

## 專案與授權

- [Mate 整合記錄](docs/MATE_FORK_MIGRATION.md)
- [Mate 使用指南](docs/MATE_USER_GUIDE.md)
- [倉庫原始碼](https://github.com/cs9527h/Rikkahub-M)

本專案是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**非官方獨立 Fork**，遵循 **AGPL-3.0**。原作者版權與 [授權條款](LICENSE) 均予以保留。
