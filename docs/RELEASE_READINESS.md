# 0.1.2 release candidate

- App version: 0.1.2; version code: 3.
- Source candidate: `3418583` plus release documentation updates.
- Proposed new tag: `v0.1.2`. Existing `v0.1.0` and `v0.1.1` tags must remain unchanged.
- Preparation only: no tag or release published.

## Publication gates

- [x] Current-source debug APK, unit tests, and lint verification recorded below.
- [ ] Persistent production signing key configured locally or in GitHub Actions secrets (`docs/RELEASING.md`). No signing variables were present locally during preparation.
- [ ] Build and inspect the signed/minified release APK; run release lint; verify signature, non-debuggable manifest, version and checksum.
- [ ] Verify login, account switching, library migration, notification/lock-screen controls, music metadata/artist navigation, Animegg playback and manga images on unlocked devices.
- [ ] Verify motion on a high-refresh-rate device; the Huawei is 60 Hz.
- [ ] Verify deployed Supabase RLS with the two-account procedure in `docs/security/README.md`; rotate or remove the previously exposed remote demo account where applicable.

Configuration and artifact verification are enforced by the tag workflow. Local production signing, device checks, and backend verification are still outstanding. Debug test artifacts must not be advertised as the production release.

## Verification

Checks against source `3418583` completed on 2026-11-07:

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: passed; 448 tests total, 441 passed, 7 skipped; lint 0 errors / 111 warnings.
- Opt-in production checks: MangaPill catalog → chapter → page → actual image bytes passed. Animegg initially timed out; one end-to-end recheck passed through catalog, episodes, stream resolution and actual MP4 bytes. This confirms build-machine delivery, not device decoding or reliable availability on another network.
- No connected ADB device was available. No device tests ran.
- Local production signing variables were absent, so no signed/minified APK was built or verified and release lint was not run during this preparation.

The initial Animegg timeout remains an availability caveat. No provider substitution or timeout suppression was introduced.
