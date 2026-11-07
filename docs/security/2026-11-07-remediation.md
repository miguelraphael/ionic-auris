# Security audit remediation — 2026-11-07

Revalidated the second pass of the 2026-11-07 audit against commit `8cf9539` before changes. The prior bounded repository downloads and DEX extraction, token-free Room session writes, and persisted signer checks were already implemented.

## Confirmed findings addressed

- Account isolation: owner-scoped library reads/writes, account-specific source identities, owner-filtered outbox work, authenticated SDK sync gate, and guarded account transitions. Unknown legacy ownership must never be inferred from the last session mirror.
- Restored database login bypass: the SDK session is authoritative; a Room identity mirror does not authenticate the app or authorize sync.
- Legacy tokens: database upgrade clears old mirrored credentials.
- Exported media service: reject untrusted controllers while retaining same-UID and system-trusted controls; custom commands require the app UID.
- Public debug APKs: new tag releases require persistent production signing, release lint, signature verification and a non-debuggable manifest. Debug artifacts remain explicitly test-only.
- Public demo credentials: remove the demo shortcut and embedded credentials. Rotating/deleting the remote demo account requires separate backend administration.
- Password retention: clear password state after successful authentication and when the gate resets.
- Diagnostics: redact buffered logs and app-state text in addition to logcat, including generic credential keys.
- Repository configuration: both entry paths use shared HTTPS URL validation; stale signature-verification copy is corrected.
- First-use publisher trust: require consent to the exact observed certificate before pinning/loading unkeyed extensions. Concurrent installs of the same package are blocked through approval.

## Deployment limits

Consent does not authenticate a compromised repository's first publisher key. No out-of-band trusted default key was available, and none was invented. Extensions still execute inside the host process. These architectural trust limits remain.

Backend RLS cannot be established from client source. See [backend verification](README.md). No backend changes, account deletion, credential rotation, production key generation, or release publication were performed.

A signed/minified release APK and device behavior require separate verification. The release signing configuration deliberately fails when required credentials are missing. Existing debug installations use a different package from the production release.
