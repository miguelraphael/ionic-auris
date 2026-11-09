Auris 0.1.11 — Sharper album art, gapless queue playback, and sign-out stops music (Android 8.0 or newer).

- Fixes AllAnime search failures (e.g. "One Piece", "Naruto", "Dragon Ball Z") when a result has a null season or year; the catalogue and AllManga now tolerate null fields instead of crashing.
- Upgrades Music home artwork to display-sized images, so album cards are no longer blurry.
- Playback queue now advances to the next song, and every played track appears in Recently Played.
- Signing out stops music playback and removes the playback notification; the same applies to account deletion and session expiry.
- Keeps the Asura Scans and AllManga sources, on-device stream resolution, and the 0.1.10 playback/navigation improvements.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
