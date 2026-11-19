# Rikkahub-M — no sponsored promotions

This fork does not include in-app advertisements, affiliate registrations, sponsor lists or donation solicitations.

## Removed

- Sponsor and donation sections from the three top-level READMEs.
- On-launch donation solicitation dialog, Donate settings item, Donate route/page.
- SponsorAPI binding, sponsor data model and sponsor photos.
- GitHub FUNDING.yml.
- Sponsored provider recommendation sheet and preinstalled sponsor/affiliate default-provider entries, including affiliate registrations.
- Unused sponsor dismissal preferences, debug controls, tests and localized strings.
- Original project's community promotion buttons from Settings and promotional external links from the public-facing About page. Original upstream project and AGPL license credits stay visible.

## Compatibility

- Previously saved provider configurations are not deleted. Removing the recommended/default entries stops automatic addition and endorsement, not access to user-created configurations.
- Settings migrations must continue to read older persisted data without a destructive reset.
- Existing AGPL-3.0 licensing, source history, copyright and origin notices are preserved. These are not advertisements.
- This patch is independent of the Mate AstrBot gateway integration branch. Merge/rebase onto that branch after validation.

## Verification needed before shipping

- `./gradlew :app:compileGithubDebugKotlin :app:testGithubDebugUnitTest :app:lintGithubDebug` (or the available equivalent tasks)
- Open Settings, About, Provider selection, Quick Start and Debug pages, verify no solicitations or sponsored recommendations.
- Upgrade from a test build with an existing sponsor-default provider account and verify saved configuration remains.
- Confirm no AGPL notices were removed.

**State:** source-level removals committed. Android compilation and visual checks are not yet verified.
