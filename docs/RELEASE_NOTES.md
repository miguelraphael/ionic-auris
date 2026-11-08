Auris 0.1.8 — music search, navigation, artwork, and playback fixes (Android 8.0 or newer).

- Fixes artist search by parsing artist results as first-class domain models so searching for an artist returns the artist as the top result with round artist artwork and direct navigation to the artist page.
- Fixes playlist navigation: tapping playlists now opens the playlist details and track listing instead of immediately initiating track playback.
- Fixes mini-player clipping on Android 15 edge-to-edge: applies complete window insets (safe drawing) inside album and artist pages so the mini-player is never cut off by the system navigation bar.
- Fixes album art distortion and resolution: applies 1:1 square aspect ratio for music items in Home and Library shelves and upgrades artwork URLs to full display resolution.
- Adds persistent mini-player to the Home screen when music is actively playing with seamless controls and smooth animations.
- Resolves simultaneous anime and music audio: configures ExoPlayer with movie media audio attributes and auto audio-focus handling, and automatically pauses music when launching an anime episode.

The security, authentication, navigation, and playback changes from previous previews remain included. Older debug previews (`com.auris.app.debug`) are a separate installation; local data is not automatically transferred between packages.

This remains a preview. Provider availability varies by network; backend authorization and broader playback/device checks remain separate from the regression checks.
