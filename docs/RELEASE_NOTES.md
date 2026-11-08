Auris 0.1.9 — artist search, playlist artwork, and mini-player insets fixes (Android 8.0 or newer).

- Fixes artist search result navigation: tapping on an artist now loads the artist's official details and releases instead of searching for the generic keyword "Artist".
- Fixes playlist track artwork and cover retention: playlists now retain their original cover art (including multi-album grid covers) and display each song's individual album art rather than overwriting all songs with the first track's cover.
- Fixes mini-player insets in album and artist dialogs: correctly resolves host window system navigation bar and status bar insets so the mini-player floats above the Android system gesture bar without clipping.
- Cleans up duplicate artist metadata subtitle on top search result cards.
- Refines full-screen music player gestures, artwork palette transitions, and track scrubbing.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
