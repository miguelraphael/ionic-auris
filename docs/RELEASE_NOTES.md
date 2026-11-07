Auris 0.1.2 — security and playback preview (Android 8.0 or newer).

- Keeps synced libraries and queued uploads separate between accounts. Restoring the library database no longer bypasses sign-in.
- Requires explicit publisher-fingerprint approval before first installing an extension without a known signing key. Rejects concurrent installs of the same extension and requires HTTPS repository settings.
- Restricts music-session access to Auris and system-trusted media controllers. Clears retained passwords, removes the shared demo-login shortcut, and expands diagnostics redaction.
- Preserves song metadata during playback and unifies artist navigation from music views.
- Updates motion timing and transitions; high-refresh-rate behavior still needs device verification.
- Replaces AnimeKhor with Animegg alongside HiAnime. Manga sources remain MangaDex and MangaPill. Saved titles stay tied to their original provider; switching sources does not migrate progress.
- Public previews now use a signed, non-debuggable release APK. Release checks reject missing signing configuration and tags that do not match the app version.

Upgrade notes:

- The release package (`com.auris.app`) installs separately from older debug previews (`com.auris.app.debug`). Keep the old installation until its device-local data is preserved; local playlists are not automatically transferred.
- Room v5 retains legacy library items whose account owner is unknown but hides them from account libraries and prevents upload to another user. Cloud-owned items can reload after sign-in. Unsynced legacy items may need manual recovery; do not clear app storage to troubleshoot them. See `docs/security/account-migration.md`.
- Extensions run with Auris's privileges; first-use approval does not independently authenticate a compromised repository. Provider availability and playback vary by service, region, and network.

This is a preview. Signed/minified APK, device behavior, and deployed backend authorization must pass the checks in `docs/RELEASING.md` before publication.
