# Rikkahub-M

**Independent Android AI client — direct model APIs, local personas and memory, restorable backups.**

> **Version 0.4.0 is in development, not released.** Draft PR #16 currently needs build fixes and device verification; this README describes the intended standalone architecture rather than a stable release.

Rikkahub-M is an unofficial fork of [RikkaHub](https://github.com/rikkahub/rikkahub), retaining Kotlin / Jetpack Compose / Material 3 Expressive, native chat, multiple providers, assistant configuration, search and other useful upstream features.

## Standalone architecture

- The Android app connects directly to configured AI providers; assistant identities, prompt injections, chat history and long-term memory are local.
- The optional cloud server stores backup archives only. Existing client backup integrations include WebDAV and S3. An S3-compatible self-hosted storage service is being evaluated.
- Legacy Mate/AstrBot server chat, pairing, server-side persona injection and cross-platform memory are being retired.

## 0.4.0 outstanding work

- Fix CI compilation and residual gateway references; migrate invalid legacy model settings safely.
- Validate local Markdown/TXT/JSON prompt injections across fresh chats, app restarts and multiple assistants.
- Make local memory create/edit/delete/search/retrieve and conversational extraction genuinely functional.
- Implement frequent lightweight settings backups, separately scheduled chat database snapshots, and no default automatic attachment backup.
- Implement portable client-side encryption for secrets including API keys, reliable restore key handling, retention and deduplication.
- Validate S3/WebDAV upload and restore plus startup, long-chat, streaming, notification identity, UI and animation fixes.
- Preserve package `com.rikkahub.mate`, signing continuity, app data and a single universal APK.

**Existing portable ZIP backups are not verified end-to-end encrypted.** Do not upload sensitive personal data to untrusted storage.

## Project and status

- [Standalone client PR #16](https://github.com/cs9527h/Rikkahub-M/pull/16) — draft, unmerged.
- [Prior UI/features PR #15](https://github.com/cs9527h/Rikkahub-M/pull/15) — selectively migrate useful changes, excluding retired gateway code.
- [Legacy bug and release checklist](docs/LEGACY_BUG_FIX_CHECKLIST.md)
- [Architecture and migration](docs/STANDALONE_BACKUP_ONLY.md)
- [Verified releases](https://github.com/cs9527h/Rikkahub-M/releases)

No private persona text, chats, API tokens or server keys should be committed. Independent unofficial [RikkaHub](https://github.com/rikkahub/rikkahub) fork; existing [AGPL-3.0 license](LICENSE) and upstream attribution are preserved.