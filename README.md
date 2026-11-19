<div align="center">
  <h1>Rikkahub-M · Mate</h1>
  <p><strong>An expressive Android AI client with private server connectivity.</strong></p>
  <p>RikkaHub's native experience × Mate's personal AstrBot connection and memory roadmap.</p>
  <p><a href="README_ZH_CN.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · English</p>
</div>

> [!IMPORTANT]
> **In development.** This fork currently has an experimental Mate gateway pairing and verification screen **on its integration branch**. Pairing is not yet connected to chat generation; server memories, cross-device synchronization, legacy Mate import, and a verified installable Mate release are **not complete**. Do not uninstall the old Mate app.

## What is Mate?

**Mate** is the app identity. **Rikkahub-M** is the repository, based on the [RikkaHub](https://github.com/rikkahub/rikkahub) open-source Android client. The goal is to retain RikkaHub's Compose UI, responsive navigation, native animations and flexible LLM capabilities while adding the personal Mate experience around an existing AstrBot deployment.

### What works in the upstream-based client

- Chat with separately configured AI providers and models, streaming responses and searchable conversation history.
- Compose / Material 3 Expressive interface, dark and light themes, adaptive layouts and transitions.
- Markdown, code, equations, images, files, search, assistants and model settings.
- Additional upstream modules such as MCP, Skills, workspace, voice and backup features (availability depends on configuration).

### Mate-specific work

| Capability | Current integration status |
|---|---|
| Always-visible **Connect server** entry | Source implemented on the Mate integration branch |
| One-time pairing, HTTPS gateway protocol v3 verification, encrypted local device credentials | Source implemented; build and server test pending |
| Send/receive server chat through AstrBot | Not yet integrated |
| Remote persona, verified memory ownership, gradual growth | Planned; server authorization required |
| Server chat history, media, tombstones, cross-device sync and backup | Planned |
| Pairing QR / server device administration | Planned |
| Import old Mate chat and settings without data loss | Planned, no destructive migration |
| Signed APK, performance benchmarks and GitHub Release | Pending |

**Important:** RikkaHub's local file memory is distinct from the AstrBot server's memory. This fork does not silently mix or delete either.

## Getting started (development preview)

1. For ordinary AI chat, go to **Quick start → Independent API** and configure a provider and a model.
2. For the private server, go to **Quick start → Mate server** or **Settings → Connect server**. Pairing and the native provider need real-server end-to-end verification.
3. Existing Mate users should keep their current app and data until a tested import/upgrade is released.

## UI and product principles

No advertisements, sponsor lists, affiliate recommendations or donation prompts. Keep meaningful animations rather than removing them to disguise jank. Minimize layout noise, retain accessible controls, do not overwrite personal data, and verify real-device performance before release.

## Development and release

- Kotlin, Jetpack Compose, Material 3 Expressive, Koin, Room and Navigation 3.
- Build commands from the upstream project: `./gradlew assembleGithubDebug` and `./gradlew test`.
- The upstream project expects `app/google-services.json` in its configured build environment; use a properly configured own project file instead of the upstream production config.
- Review `docs/MATE_FORK_MIGRATION.md` and `docs/MATE_USER_GUIDE.md` for migration scope and current limitations.
- This fork's package name and signing strategy for test releases must be verified before publishing. Never replace the existing Mate installation with an unverified APK.

## Origin and license

Independent fork of [RikkaHub](https://github.com/rikkahub/rikkahub), licensed under **AGPL-3.0**. The original author's copyright and licensing notices are preserved; this fork is not an official RikkaHub release. See [LICENSE](LICENSE) and the upstream source for attribution. No sponsorship or donation is requested by this fork.

Source: https://github.com/cs9527h/Rikkahub-M
