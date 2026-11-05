Auris brings music, anime, and manga into one Android app (Android 8.0 or newer).

Highlights:
- Music search, album and artist pages, local playlists, favorites, radio, and continuous playback.
- Background playback, shuffle/repeat, equalizer, and sleep timer.
- Album and artist playback starts the selected song before resolving the remaining queue.
- Mini-player controls on music, album, and artist pages, with system-navigation inset handling.
- Built-in HiAnime playback and MangaDex reading.
- A consistent dark interface with coordinated transitions and press feedback.

This is a **debug-signed preview APK** (`com.auris.app.debug`), not a production-signed release.
CI requires the Supabase client configuration and passes unit tests before publishing.
Debug signing keys may differ between build environments, which can prevent installing over an existing copy. Preserve local data before any uninstall.

Verification limits: recent mini-player layout changes and live album/artist playback still need on-device verification. Source availability depends on third-party services. The attached test phone supports 60 Hz; higher-refresh-rate coverage is unverified.
