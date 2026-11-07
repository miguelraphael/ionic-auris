# 0.1.2 release candidate

Superseded by [0.1.3](https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.3) after a minified startup crash. See `RELEASE_STARTUP_HOTFIX.md` for the fix and signed-device verification.

- App version: 0.1.2; version code: 3.
- Source candidate: `3418583` plus release documentation updates.
- Proposed new tag: `v0.1.2`. Existing `v0.1.0` and `v0.1.1` tags must remain unchanged.
- Published as a preview: https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.2 ; tag targets `9270a18`.

## Publication gates

- [x] Current-source debug APK, unit tests, and lint verification recorded below.
- [x] Persistent production signing key created outside the repository and GitHub Actions signing secrets configured. Keep an encrypted off-device backup of the private key and recovery credentials.
- [x] Signed/minified release build and release lint passed in CI; downloaded APK signature, certificate, non-debuggable manifest, version and checksum verified.
- [ ] Verify login, account switching, library migration, notification/lock-screen controls, music metadata/artist navigation, Animegg playback and manga images on unlocked devices.
- [ ] Verify motion on a high-refresh-rate device; the Huawei is 60 Hz.
- [ ] Verify deployed Supabase RLS with the two-account procedure in `docs/security/README.md`; rotate or remove the previously exposed remote demo account where applicable.

Configuration and artifact verification are enforced by the tag workflow. Device checks and backend verification remain outstanding; this is a preview release. Debug test artifacts must not be advertised as the production release.

## Verification

Checks against source `3418583` completed on 2026-11-07:

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: passed; 448 tests total, 441 passed, 7 skipped; lint 0 errors / 111 warnings.
- Opt-in production checks: MangaPill catalog → chapter → page → actual image bytes passed. Animegg initially timed out; one end-to-end recheck passed through catalog, episodes, stream resolution and actual MP4 bytes. This confirms build-machine delivery, not device decoding or reliable availability on another network.
- No connected ADB device was available. No device tests ran.
- Local production signing variables were absent, so no signed/minified APK was built or verified and release lint was not run during this preparation.

The initial Animegg timeout remains an availability caveat. No provider substitution or timeout suppression was introduced.

## Published artifact verification

- CI: https://github.com/miguelraphael/ionic-auris/actions/runs/42348592043 — regression build/tests and signed release build/lint/publication succeeded.
- Downloaded APK: `auris-v0.1.2.apk`, package `com.auris.app`, version `0.1.2`, code `3`, non-debuggable.
- APK SHA-256: `c86104b650dc3ca05033621f827ab336cf611fb9e906c1ffc8e293996000c1a0` (matches published checksum).
- Signing certificate SHA-256: `5a43f5baf2439c20ab4c52bea9f76267ae76fc1149a334d27c8c0bc786b78993` (matches persistent production key).
- `apksigner verify --print-certs` and `aapt dump badging` passed local verification of the downloaded asset.
- No device installation or backend policy verification performed. Prior debug package installs remain separate.
