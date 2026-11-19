# Mate x RikkaHub migration ledger

**Foundation branch:** `feat/mate-gateway-foundation-20261119`. This is an experimental fork, **not a shippable Mate APK**.

## Design decisions

- RikkaHub is the base. Keep native Compose UI, animation system, existing AI providers and chat sessions.
- Mate protocol-v3 gateway is a **separate authenticated service**, not an OpenAI-compatible provider; do not put `/pair` or `/chat/stream` under regular model settings.
- Old `cs9527h/dafayu-android` remains untouched. No private keys, AstrBot credentials, memory dumps, chat history or production configuration may enter this **public** fork.
- AGPL-3.0 licensing and original copyright notices remain intact. Distribution must comply with the applicable license.
- During migration, use a new application ID and independent signing so that test builds cannot overwrite RikkaHub or old Mate. Before packaging, audit Firebase, application ID, deep links, provider authorities, and updates. **No packaging change has been made yet.**

## Current implemented code (source-only)

1. `mate/MateGateway.kt`: HTTPS-only URL normalization, pairing POST `/pair`, authenticated `/capabilities` protocol-3 + instance verification, bounded JSON, no redirects, Android Keystore AES-GCM credential storage.
2. `mate/MateConnectionPage.kt`: pairing and manual health check in a native Compose page. Pairing codes are never intentionally persisted.
3. `RouteActivity.kt` + `SettingPage.kt`: unconditional Settings → 李鱼 · AstrBot → 连接李鱼服务器 navigation, even when no chat history exists.
4. `MateGatewayAddressTest.kt`: basic URL security checks.

**NOT implemented:** full chat integration, gateway SSE parsing, authenticated media/history, QR scanner, device issuance/revocation UI, AstrBot dashboard plugin, personas, peak/valley status, remote memories, backup/sync migration, old Mate data import, valid installable APK.

**Not tested:** Gradle compilation, Android UI instrumentation, production pairing, real device, CI, signing or Release. The development environment lacks direct GitHub clone/network access and Android SDK; this branch was edited through GitHub's authenticated contents API.

## Required next implementation sequence

- P0: Run Gradle format/lint/unit tests and fix any compile issues. Build a separate test package with clear branding, no RikkaHub production ID. Do not ship before signing and third-party service review.
- P1: implement a `MateChatBridge` in RikkaHub's `ChatService` session workflow using Mate gateway `/chat/stream`, a request id, bounded SSE snapshots, cancellation and no automatic duplicate generation. Bind it to a dedicated Mate assistant, not to generic OpenAI providers.
- P1: implement server message/history adapters and explicit distinction between local and server memory. Never delete or mutate AstrBot production memories without scoped owner authorization.
- P2: authorized web pairing-code issuance with server-side authentication, one-use expiration and revocation; Android camera QR scanning.
- P2: add opt-in migration from old Mate's exported backup and test idempotence, attachment integrity, history ordering, and rollback. Never assume Room and SQLite schemas are identical.
- P3: add notification, diagnostics, change log, dynamic peak/valley state, and only genuinely missing Mate features.
- P4: verify real-device startup, 500/5000/20000-message scroll, 60/120 Hz frame pacing, IME animations, battery, network interruption and offline access. Preserve RikkaHub animations rather than disabling them.

## Security checklist

- Never save or log one-time codes. Device tokens stored using Android Keystore AES-GCM; invalid restored keys must fail closed.
- HTTPS validation must not permit redirect-based credential leakage.
- Validate gateway protocol 3 and unchanged `instance_id` before storing a new pairing.
- Temporary pairing is not proof of historical-data ownership; server-side owner restrictions remain authoritative.
- Device removal on this phone does not revoke authorization on the server; indicate that to users.
- No production server writes or remote platform changes without explicit permission.

## Definition of done

A feature is **DONE** only after code, tests and a reproducible compiled artifact or integration result exist. Experimental UI alone is **PARTIAL**, not a functioning server integration.
