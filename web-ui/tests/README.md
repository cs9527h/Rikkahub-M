# Web chat regression tests

Run from `web-ui`:

```sh
pnpm run test   # Node test runner + tsx; no browser, server, or extra dependencies
pnpm run check  # tests + React Router/TypeScript typecheck + read-only oxlint
```

The tests import the production draft store, submission/upload helpers, detail
subscription, node reducer, and real SSE parser/HTTP client. They do not extract
source snippets or assert source-code strings. Deferred promises control request
completion; injected transports control reconnect ordering, and a mocked fetch
exercises the real SSE transport (including clean EOF).

Coverage:

- Audit 21: failed first send retains the visible home draft and attachments.
- Audit 22: only an unchanged submitted draft is cleared; in-flight text and
  attachment edits survive, including edit submissions and home-to-chat migration.
- Audit 23: capture the input before async upload; reset after success/failure so
  the same file can be selected again even after React clears `currentTarget`.
- Audit 24: reconnect on EOF and errors, bounded exponential backoff, cancellation,
  and a fresh authoritative snapshot before applying deltas on every connection.
- Audit 25: detail initialization uses only the stream's snapshot, eliminating the
  independent HTTP response that previously overwrote newer stream state; stale
  sequence numbers are ignored and new connections reset their sequence baseline.

These are executable production-logic/transport regressions, not browser E2E or
Android integration tests. Audit 07's server-side cancellation/deletion invariant
is tested with the ChatService/ConversationSession changes; the existing Web API
continues to await `chatService.deleteMessage` before acknowledging deletion.
