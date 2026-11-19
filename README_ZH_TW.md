<div align="center">
  <img src="docs/icon.png" alt="App 圖標" width="100" />
  <h1>RikkaHub</h1>

一個原生Android LLM 聊天客戶端，支持切換不同的供應商進行聊天 🤖💬


[English](README.md) | 繁體中文 | [简体中文](README_ZH_CN.md)

</div>

<div align="center">
  <img src="docs/img/poster.png" alt="RikkaHub" width="800" />
</div>

## ✨ 功能特色

- 🎨 現代化安卓APP設計（Material 3 Expressive / 預測性返回）
- 📦 工作區：基於 proot 的 Linux 智能體環境
- 📱 Phone Use：讓 AI 操控手機
- 🎬 媒體生成（圖片、影片）
- 🔄 多種類型的供應商支持，自定義 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖥️ Web多端訪問支持
- 🛠️ MCP 與 Skills 支持
- 📝 Markdown 渲染（支持代碼高亮、數學公式、表格、Mermaid）
- 🔍 搜尋功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 變量（模型名稱、時間等）
- 🤳 二維碼導出和導入提供商
- 🤖 智能體自定義
- 🧠 基於檔案的記憶
- 📝 AI翻譯
- 🌐 自定義HTTP請求頭和請求體
- 💌 SillyTavern 角色卡匯入

## ✨ 開發

> [!IMPORTANT]
> 本項目不接受 Pull Request（PR）。

本項目使用[Android Studio](https://developer.android.com/studio)開發。

技術棧文檔:

- [Kotlin](https://kotlinlang.org/) (開發語言)
- [Koin](https://insert-koin.io/) (依賴注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好數據存儲)
- [Room](https://developer.android.com/training/data-storage/room) (數據庫)
- [Coil](https://coil-kt.github.io/coil/) (圖片加載)
- [Material 3 Expressive](https://m3.material.io/) (UI 設計)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (導航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客戶端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> 你需要在 `app` 資料夾下添加 `google-services.json` 檔案才能構建應用。


---

本倉庫是基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 的獨立 AGPL-3.0 分支，保留原作者版權與 [授權條款](LICENSE)。本分支不顯示贊助廣告或捐款入口。
