# Rikkahub-M

**An open-source AI client for Android. Choose your models, customize your assistants, and manage your data.**

Independently maintained, based on [RikkaHub](https://github.com/rikkahub/rikkahub), built with Kotlin, Jetpack Compose and Material 3 Expressive.

[简体中文](README.md) · English · [繁體中文](README_ZH_TW.md)

[Download](https://github.com/cs9527h/Rikkahub-M/releases) · [Changelog](CHANGELOG_MATE.md) · [User guide](docs/MATE_USER_GUIDE.md) · [Issues](https://github.com/cs9527h/Rikkahub-M/issues)

<!-- RELEASE-BLOCK:BEGIN -->
> **Latest stable release: v1.1.6** (2026-11-27, commit `cddb5bee`)
>
> APK: [Rikkahub-M-1.1.6.apk](https://github.com/cs9527h/Rikkahub-M/releases/download/v1.1.6/Rikkahub-M-1.1.6.apk) · 18.0 MB · universal (arm64-v8a + x86_64)
>
> SHA-256: `a845185bc145efb777c1b8717ce557a25d1cff702e3fc19cbf37b4a8cf0b5813`
>
> Summary: separate avatar storage, improved attachment ownership tracking, and fixes for missing backup images.
>
> Details: [v1.1.6 release notes](docs/releases/1.1.6.md).
<!-- RELEASE-BLOCK:END -->

## Features

| Area | Capabilities |
| --- | --- |
| Models | Direct OpenAI-, Gemini- and Claude-compatible APIs, DeepSeek, NewAPI and custom endpoints; no extra gateway required |
| Assistants | Custom models, avatars, personas and prompts; Markdown, TXT and JSON rule imports with configurable injection order |
| Chat | Conversation search, message branching, favorites, translation, text-to-speech, speech recognition and custom backgrounds |
| Memory | Local long-term memory extraction, organization, editing and recall; independent assistant memory settings |
| Files | Assistant-based attachment organization, search, filters, previews, export and reference-aware cleanup; separate avatar storage |
| Documents | Image zoom, PDF pages, text and Markdown previews, and text previews for DOCX, PPTX and EPUB |
| Migration | Local, WebDAV, S3 and automatic backups; imports from upstream RikkaHub, Chatbox, DeepSeek and Cherry Studio |
| Updates | Multiple download sources with fallback; SHA-256, package name, version code and signature verification |

## Data and migration

Full backups include settings, chats, attachments and API credentials in an ordinary ZIP archive. No passphrase or recovery key is required. Backups are not encrypted: store them securely and never share them publicly. Legacy encrypted backups are no longer supported. Full backups are not guaranteed to restore into upstream RikkaHub.

## Project notes

- Retains the upstream design language without sponsorship, donation or promoted-provider entries.
- Independent package name `com.rikkahub.mate`, signing and release pipeline; one universal arm64-v8a + x86_64 APK.
- Releases undergo automated tests, Lint, signature and upgrade checks. See release notes for physical-device testing and performance limitations.

## Documentation

[Memory](docs/HISTORY_MEMORY.md) · [Upstream differences](docs/RM_VS_RB.md) · [File-level comparison](docs/RM_VS_RB_FILE_DIFF.md) · [Release notes](docs/releases/) · [Quality ledger](docs/QUALITY_LEDGER.md) · [Backup architecture](docs/STANDALONE_BACKUP_ONLY.md)

## License

[AGPL-3.0](LICENSE), retaining upstream RikkaHub copyright and attribution. Modifications and distributions must comply with the license.
