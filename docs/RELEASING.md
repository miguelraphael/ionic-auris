# Release signing and verification

Public tagged previews must be signed with a persistent, privately held release key and must not be debuggable. No production signing key is generated or committed by this project.

Configure repository Actions secrets `AURIS_KEYSTORE_BASE64`, `AURIS_KEYSTORE_PASSWORD`, `AURIS_KEY_ALIAS`, and `AURIS_KEY_PASSWORD`. Base64 is only transport encoding; keep the keystore private and backed up securely. Preserve the same key for future APK updates.

Local release builds require `AURIS_KEYSTORE_PATH` (path to an existing keystore), `AURIS_KEYSTORE_PASSWORD`, `AURIS_KEY_ALIAS`, and `AURIS_KEY_PASSWORD` environment variables. Run `./scripts/release-build.sh :app:assembleRelease :app:lintRelease --no-configuration-cache` after configuring Supabase client settings. Missing signing configuration fails the build; it never falls back to a debug key.

The tag workflow first requires the debug regression test job, then builds release, runs release lint, verifies the APK signature, and rejects a debuggable manifest before publishing. Debug CI artifacts are for testing only.

Before tagging:

- Review integrated unit tests, debug/release lint and a signed release APK.
- Verify login/account switching and media controls on an unlocked device.
- Confirm deployed Supabase RLS separately; client filters do not enforce server security.
- Confirm the new version code/name and release notes. Create a new tag; never overwrite an existing tag.

Release APKs use `com.auris.app`, separate from `com.auris.app.debug`. A signing key change prevents in-place upgrades of the same package. The first signed production-package release is a separate install from historical debug previews; do not promise automatic device-local data migration.
