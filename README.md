<div align="center">
  <h1>Rikkahub-M · Mate</h1>
  <p><strong>An expressive Android AI client with private server connectivity.</strong></p>
  <p>RikkaHub's native experience × Mate's personal AstrBot connection and memory roadmap.</p>
  <p><a href="README_ZH_CN.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · English</p>
</div>

> [!IMPORTANT]
> **0.3.0 release line.** Chat goes through your own AstrBot Mate gateway, long-term memory is read/written through a signed per-device API, and attachments upload as raw bytes. The app keeps package `com.rikkahub.mate` and the 0.2.4 signing key, so it upgrades over 0.2.4 without losing chats or settings. Old Mate (`com.dafayu.chat.preview`) is a separate app and is never overwritten.

## What is Mate?

**Mate** is the app identity. **Rikkahub-M** is the repository, based on the [RikkaHub](https://github.com/rikkahub/rikkahub) open-source Android client. It keeps RikkaHub's Compose UI, navigation and model capabilities, and adds a private AstrBot connection with 李鱼 persona, long-term memory and diagnostics.

### What works

- Chat with separately configured AI providers and models, streaming responses and searchable conversation history.
- Compose / Material 3 Expressive interface, dark and light themes, adaptive layouts and transitions.
- Markdown, code, equations, images, files, search, assistants and model settings.
- Additional upstream modules such as MCP, Skills, workspace, voice and backup features.

### Mate-specific status in 0.3.0

| Capability | Status in 0.3.0 |
|---|---|
| Always-visible **Connect server** entry | Implemented |
| One-time pairing, gateway protocol v3 verification, encrypted device credentials | Implemented; QR pairing fixed |
| Server chat with SSE snapshot streaming, stop and cancel | Implemented |
| Attachments (image / PDF / document), raw-byte upload, 8MB × 4 limit | Implemented |
| Long-term memory center: list, search, filter, create, edit, delete, trash, restore, bulk delete, audit | Implemented, needs `APP_MEMORY_WRITE_ENABLED=true` on the gateway |
| Save chat message as long-term memory | Implemented |
| Peak/valley status and DeepSeek balance | Implemented, read from gateway `/status` and `/billing/deepseek` |
| Consent-based diagnostics upload | Implemented, event names only, no chat text or tokens |
| Backup of settings, chats and profile via WebDAV/S3 | Implemented; provider keys must be re-entered after restore |
| Signed universal APK `Rikkahub-M-0.3.0.apk` | Built by GitHub Actions release workflow |

## Getting started (development preview)

1. For ordinary AI chat, go to **Quick start → Independent API** and configure a provider and a model.
2. For the private server, go to **Quick start → Mate server** or **Settings → Connect server**, then scan the one-time QR code or enter the pairing code. Server chat, attachments and the memory center run through that gateway.
3. Download the signed APK `Rikkahub-M-0.3.0.apk` from the [Releases](https://github.com/cs9527h/Rikkahub-M/releases) page. It upgrades over 0.2.4 in place.

## UI and product principles

No advertisements, sponsor lists, affiliate recommendations or donation prompts. Keep meaningful animations rather than removing them to disguise jank. Minimize layout noise, retain accessible controls, do not overwrite personal data, and verify real-device performance before release.

## Development and release

- Kotlin, Jetpack Compose, Material 3 Expressive, Koin, Room and Navigation 3.
- Build commands from the upstream project: `./gradlew assembleGithubDebug` and `./gradlew test`.
- The upstream project expects `app/google-services.json` in its configured build environment; use a properly configured own project file instead of the upstream production config.
- Review `docs/MATE_FORK_MIGRATION.md` and `docs/MATE_USER_GUIDE.md` for migration scope and current limitations.
- Release workflow: `.github/workflows/release.yml` builds one universal APK with the 0.2.4 signing key, verifies package name, `versionName`/`versionCode`, ABI and certificate, then publishes a GitHub Release with SHA-256. Never install an APK whose certificate differs from `e4b90a5f…8f2c94a`.

## Origin and license

Independent fork of [RikkaHub](https://github.com/rikkahub/rikkahub), licensed under **AGPL-3.0**. The original author's copyright and licensing notices are preserved; this fork is not an official RikkaHub release. See [LICENSE](LICENSE) and the upstream source for attribution. No sponsorship or donation is requested by this fork.

Source: https://github.com/cs9527h/Rikkahub-M
