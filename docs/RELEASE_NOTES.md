Auris 0.1.2 — security hardening preview (Android 8.0 or newer).

- Restricts the exported music session to Auris and system-trusted media controllers. Standard notification and lock-screen controls remain supported; custom commands are restricted to Auris's UID.
- Public tagged previews now require persistent release signing and a non-debuggable `com.auris.app` APK. CI fails if signing secrets are missing; debug artifacts remain explicitly test-only.
- Release lint checks are enabled. Fixed Media3 opt-in annotations and result constants flagged by lint.

Release preparation: configure the signing secrets documented in `docs/RELEASING.md`, complete integrated security regression checks and verify on an unlocked device before publishing a new tag. Existing v0.1.0/v0.1.1 tags must not be overwritten.

The release package (`com.auris.app`) installs separately from older debug previews (`com.auris.app.debug`). Do not uninstall a debug preview without preserving its local data. Source availability still depends on third-party services.
