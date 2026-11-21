# Rikkahub-M

**Independent open-source Android AI client | Direct model APIs | Local assistants and prompts | Chat history and backups**

**Language / 语言: [简体中文（默认）](README.md) · English · [繁體中文](README_ZH_TW.md)**

> [!CAUTION]
> **v0.4.0 has been published as a test preview, NOT a stable release.** Its [release notes](https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.4.0) warn that the shipped binary still contains a startup path that may implicitly delete an old standalone chat database and local backup directory. **Do not install or launch that preview until the migration and safety fixes have been verified.** Preserve your previous installation and independent backups.

## About

Rikkahub-M is an **unofficial independent fork** of [RikkaHub](https://github.com/rikkahub/rikkahub), built with Kotlin, Jetpack Compose, and Material 3 Expressive. It retains upstream native chat, model providers, assistant configurations, search, and other applicable features.

The project is moving toward a **standalone Android client**: AI requests go directly to user-configured model APIs. Local management of assistant identities, prompt injection, conversations, and long-term memory is the goal. Legacy Mate/AstrBot-specific chat gateway, pairing, cloud persona injection, and cross-platform memory integrations have been retired from the client architecture.

## Features and development direction

- **Multi-model chat:** Native provider and tool integrations inherited from RikkaHub; actual availability depends on the release and configuration.
- **Local assistants and prompts:** User-defined assistants, with ongoing validation of Markdown/TXT/JSON prompt import, priority, and persistence in new chats.
- **Local conversations and memory:** Chat history, with continued work on memory extraction, search, editing, deletion, and cross-chat retrieval.
- **Backups:** WebDAV and S3 client integrations exist; the optional cloud server is intended for backup storage only. Encryption, recovery keys, and rollback still require end-to-end validation.
- **User experience:** Startup speed, long conversations, streaming, notifications, consistent UI, and smooth animation are ongoing priorities.

Some items above are **in development or not fully validated**. This list does not claim that the current APK supports them reliably.

## Release status and acceptance criteria

[PR #16](https://github.com/cs9527h/Rikkahub-M/pull/16) was merged on November 20, 2026. The v0.4.0 preview was published, but **upgrade safety, data migration, encrypted backup, and on-device acceptance testing remain unverified**.

Before declaring a stable release, the project must:

- Remove any risk of unintended old database or backup-directory deletion, then verify in-place upgrades, data preservation, and failure recovery on real devices.
- Verify local prompt injection, memory, migration of provider settings, image/attachment handling, and tools.
- Test WebDAV/S3 upload, download, restoration, encryption, and key recovery; protect chat content and credentials.
- Verify startup, long-chat performance, streaming, notification identity, keyboard, and animation.
- Preserve application ID `com.rikkahub.mate`, signing continuity, and user data; distribute only one universal APK.

> [!WARNING]
> **Do not assume ordinary ZIP backups are end-to-end encrypted.** Do not upload sensitive chats or credentials to untrusted storage until client-side encryption and recovery have been verified.

## Project links

- [Releases](https://github.com/cs9527h/Rikkahub-M/releases) — read each release's safety notes first
- [Changelog (Chinese)](CHANGELOG_MATE.md)
- [Standalone architecture and migration (Chinese)](docs/STANDALONE_BACKUP_ONLY.md)
- [Legacy bugs and release checklist (Chinese)](docs/LEGACY_BUG_FIX_CHECKLIST.md)
- [Upstream RikkaHub](https://github.com/rikkahub/rikkahub)

## License and privacy

This is **not** an official RikkaHub release. The project retains upstream copyright and applicable third-party notices under the existing [AGPL-3.0 license](LICENSE). Do not commit personal chats, private persona prompts, API keys, device tokens, or server credentials to this public repository.
