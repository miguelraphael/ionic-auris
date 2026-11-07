# Security audit remediation after 0.1.4

Addresses the fourth security-audit pass at `ff8ce4e` and its still-open sync findings.
Changes were developed in three `wt` worktrees with separate agents, reviewed and integrated
on main. No database reset, destructive migration or release publication is part of this work.

| Finding | Change |
| --- | --- |
| Progress uploads discarded | Progress writes include the latest payload; the worker reads the live owned row, including legacy payloadless operations. |
| Pull truncation and unsafe watermark | Fetch all pages using an ascending `(updated_at, id)` cursor; retain exact server timestamp precision within a pass and inclusive millisecond boundaries between passes. Save remote time rather than the device clock. |
| Pull resurrects local deletions or overwrites pending changes | Skip local tombstones and unsynced/conflicted rows during merge. |
| Tombstones and stale queued work retained | After a confirmed server delete, remove that entry's queue rows, units and tombstone. |
| Missing-entry DELETE loops | Dequeue missing rows if encountered; account deletion also cleans provably owned orphan payloads. |
| Account deletion relies on FK cascade | Explicitly delete owned units transactionally, with tests that disable FK enforcement. |
| Orphan queue payloads survive account deletion | Delete orphan payloads only where their recorded owner matches the deleted account; retain unknown ownership. |
| Favorite setter loses uploads | Queue the updated owned row. |
| Repository package path traversal | Validate package IDs during decoding and enforce canonical file containment during file operations. |
| Update re-enables disabled extension | Preserve the installed enabled choice. |
| Startup loads unchecked bytes | Verify file size, canonical path, package identity, stored hash and pinned signer before installed code loads. Rejected files remain on disk; their rows are disabled. |
| Disable/remove leaves registry entries | Remove per-package registry references. Already-running code still requires a restart to fully stop. |
| Invidious accepts HTTP/credentials | Require credential-free HTTPS base URLs; remove unsafe legacy preference values. |
| Previous email retained at sign-out gate | Clear email when the authentication gate reopens. |
| CI checkout retains token | Disable checkout credential persistence in both jobs. |

## Retained behavior and external gaps

Sign-out deliberately retains owner-scoped local data and pending changes; it is not a disk
wipe. Database backup still includes email/library/history and quarantined legacy data.
Android backup rules cannot exclude one table or column. This behavior and the local-only
unit progress contract are documented in [local data privacy](local-data-privacy.md), and
backup comments now describe the contents accurately. Existing backups are not erased.

Unknown-owner legacy/orphan data is retained rather than attributed to or deleted with an
arbitrary account. Fully revoking in-process extension execution requires process restart;
TOFU publisher trust and the in-process plugin architecture remain limitations. Offline
access still requires an SDK session; the authentication gate has not been weakened.

Deployed Supabase RLS, real-account flows, device upgrade/migration behavior and dependency
CVE scanning remain separate verification gaps. No dependency upgrades are included.

## Verification

Final serial command: `./gradlew :app:testDebugUnitTest :app:assembleDebug
:app:compileDebugAndroidTestKotlin :app:lintDebug --offline --no-configuration-cache`.
All tasks passed: 474 tests, zero failures/errors, seven existing skips; lint zero errors
and 111 warnings. Regression coverage includes real PostgREST pagination requests, Room
sync passes, owner isolation, cleanup with FK enforcement disabled, atomic cleanup rollback,
extension path/signer/hash checks and unsafe saved-instance preference cleanup.

Instrumentation tests were compiled only; no everyday device installation or data-clearing
test runner was used. Existing D8/Kotlin metadata compatibility warnings remain. No signed
release build, live backend test or device migration run was performed for this change.
