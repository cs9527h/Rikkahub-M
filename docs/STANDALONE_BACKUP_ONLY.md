# Standalone Rikkahub-M (development branch)

The Mate/AstrBot chat gateway, pairing, device management and remote memory UI are retired. All conversation model requests use native provider integrations. Local prompt injection, local memory consolidation, chat history, and WebDAV/S3 backups remain.

Migration drops only the retired built-in gateway provider and clears pointers to its legacy model ID; it retains assistant identity, system prompts, and local injection configurations. Users who relied on the gateway model must select a direct API model in app settings.

Before shipping: run Gradle compilation and unit tests, install over an existing APK, verify restored local conversations and prompt injection (including new chats), verify local memory, WebDAV and S3 restore, and verify release signing continuity.

**This commit does not stop or delete any production cloud server.** Server cleanup requires separate authorized production actions. Existing server archives and the backup-only service must be explicitly provisioned. No personal chats, credentials, tokens, or persona contents are committed here.
