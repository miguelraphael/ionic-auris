Auris 0.1.12 — Polished music pages (Android 8.0 or newer).

- Now Playing now reads in order: artwork, track/artist/album, then seek, then transport; touch targets are at least 48dp.
- Artist page plays songs from a button directly under the name; About sits below; back and "Show all" controls are at least 48dp.
- Album and playlist details now use a left-aligned header that shares one axis with the track list — the name leads, not the cover.
- Playlist sheet rows show artwork and titles with full-width 48dp targets.
- Keeps the 0.1.11 sharper album art, automatic queue advance and Recently Played recording, stop-on-sign-out, and the JSON-null search fix.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
